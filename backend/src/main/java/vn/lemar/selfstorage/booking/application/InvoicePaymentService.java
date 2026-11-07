package vn.lemar.selfstorage.booking.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.lemar.selfstorage.audit.application.AuditEntry;
import vn.lemar.selfstorage.audit.application.AuditRecorder;
import vn.lemar.selfstorage.audit.domain.AuditAction;
import vn.lemar.selfstorage.audit.domain.AuditEntityType;
import vn.lemar.selfstorage.booking.application.exception.BookingConflictException;
import vn.lemar.selfstorage.booking.application.exception.BookingNotFoundException;
import vn.lemar.selfstorage.booking.domain.Invoice;
import vn.lemar.selfstorage.booking.domain.InvoiceStatus;
import vn.lemar.selfstorage.booking.domain.RentalOrder;
import vn.lemar.selfstorage.booking.repository.InvoiceRepository;
import vn.lemar.selfstorage.booking.repository.RentalOrderRepository;
import vn.lemar.selfstorage.facility.application.StorageUnitQuery;
import vn.lemar.selfstorage.facility.application.StorageUnitView;
import vn.lemar.selfstorage.notification.application.NotificationType;
import vn.lemar.selfstorage.payment.application.PaymentService;
import vn.lemar.selfstorage.payment.application.PaymentSessionCloser;

/**
 * Spec 1.4 — khách bấm "Tiến hành thanh toán" một hoá đơn. Kiểm theo đúng thứ tự spec:
 *
 * <ol>
 *   <li>đơn quá {@code expires_at} → từ chối, không đổi gì;</li>
 *   <li>khoang đã {@code Reserved}/{@code Rented} → huỷ hoá đơn, báo FM đề xuất lại, đơn giữ nguyên;</li>
 *   <li>khoang {@code Maintenance} → như trên với thông báo bảo trì;</li>
 *   <li>khoang đang có phiên thanh toán khác chưa hết 15' → từ chối;</li>
 *   <li>còn lại → ghi {@code PaymentTransaction(Pending)} rồi trả URL cổng.</li>
 * </ol>
 *
 * <p>Khoá: đơn → hoá đơn → khoang (cùng thứ tự với đồng ý đề xuất và Cancellation Cascade). Khoá row khoang
 * trước khi đọc phiên đang mở để hai khách bấm cùng lúc không cùng mở được phiên.
 *
 * <p>Nhánh 2/3 đổi dữ liệu (huỷ hoá đơn) nhưng vẫn là kết quả từ chối: trả {@link PayResult} thay vì ném lỗi
 * để transaction commit.
 *
 * <p>Phiên đang mở = còn giao dịch {@code Pending} cho tới khi cổng xác nhận phiên đóng (không tự hết theo đồng hồ
 * của mình) — khoang không thể có hai phiên trả được tiền cùng lúc.
 */
@Service
public class InvoicePaymentService {

    static final String MSG_ORDER_EXPIRED = "Đơn thuê kho đã hết hạn. Vui lòng liên hệ cơ sở để được hỗ trợ.";
    static final String MSG_UNIT_TAKEN =
            "Khoang chứa đã được đặt cọc bởi khách hàng khác. Hóa đơn này đã hết hiệu lực.";
    static final String MSG_UNIT_MAINTENANCE =
            "Khoang chứa hiện không khả dụng. Vui lòng liên hệ cơ sở để được hỗ trợ.";
    static final String MSG_UNIT_BUSY = "Khoang chứa này hiện đang trong quá trình xử lý thanh toán "
            + "(đặt cọc / gia hạn) bởi một khách hàng khác. "
            + "Vui lòng quay lại thử lại sau ít phút hoặc chọn khoang chứa khác!";
    static final String MSG_OWN_SESSION_OPEN = "Hoá đơn đang có phiên thanh toán chưa kết thúc. Vui lòng hoàn tất "
            + "phiên đang mở hoặc thử lại sau ít phút.";
    static final String MSG_NOT_FOUND = "Không tìm thấy hoá đơn";
    static final String MSG_NOT_UNPAID = "Hoá đơn không còn ở trạng thái chờ thanh toán";
    static final String MSG_OVERDUE = "Hoá đơn đã quá hạn thanh toán";
    static final String MSG_ORDER_CLOSED = "Đơn thuê đã đóng, không thể thanh toán hoá đơn này";

    static final String REOPEN_REASON = "Khach mo phien thanh toan moi";

    private final InvoiceRepository invoiceRepository;
    private final RentalOrderRepository orderRepository;
    private final StorageUnitQuery storageUnitQuery;
    private final PaymentService paymentService;
    private final PaymentSessionCloser sessionCloser;
    private final AuditRecorder auditRecorder;
    private final OrderNotifier orderNotifier;
    private final TransactionTemplate transaction;
    private final AppointmentBookingService bookingService;

    InvoicePaymentService(InvoiceRepository invoiceRepository, RentalOrderRepository orderRepository,
                          StorageUnitQuery storageUnitQuery, PaymentService paymentService,
                          PaymentSessionCloser sessionCloser, AuditRecorder auditRecorder,
                          OrderNotifier orderNotifier, PlatformTransactionManager transactionManager,
                          AppointmentBookingService bookingService) {
        this.bookingService = bookingService;
        this.invoiceRepository = invoiceRepository;
        this.orderRepository = orderRepository;
        this.storageUnitQuery = storageUnitQuery;
        this.paymentService = paymentService;
        this.sessionCloser = sessionCloser;
        this.auditRecorder = auditRecorder;
        this.orderNotifier = orderNotifier;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /** Kết quả bấm thanh toán: có URL cổng, hoặc bị từ chối kèm message hiển thị cho khách. */
    public record PayResult(String paymentUrl, String rejectionMessage, boolean invoiceCanceled) {

        static PayResult redirect(String url) {
            return new PayResult(url, null, false);
        }

        static PayResult rejected(String message, boolean invoiceCanceled) {
            return new PayResult(null, message, invoiceCanceled);
        }

        public boolean rejected() {
            return paymentUrl == null;
        }
    }

    /** Trạng thái thanh toán của hoá đơn sau khi đối soát. */
    /**
     * @param bookCheckinOrderId đơn vừa trả cọc xong và đặt được lịch check-in ngay → FE chuyển khách sang đặt lịch
     *                           (spec 1.4 → 1.5); {@code null} nếu không áp dụng
     */
    public record PaymentCheck(Long invoiceId, String invoiceStatus, String paymentStatus, String failureReason,
                               Long bookCheckinOrderId) {
    }

    /**
     * Bước 0 (ngoài transaction, chưa khoá gì): khách bấm thanh toán lại khi phiên cũ của chính hoá đơn còn mở →
     * hỏi cổng (đã trả thì chốt luôn) rồi huỷ phiên cũ. Phải chạy trước khi khoá đơn: nếu phiên cũ hoá ra đã trả,
     * listener chốt thanh toán cần khoá đơn trong transaction riêng.
     */
    public PayResult start(Long invoiceId, Long customerId, String ipAddr) {
        Invoice peek = invoiceRepository.findById(invoiceId)
                .filter(i -> i.getCustomerId().equals(customerId))
                .orElseThrow(() -> new BookingNotFoundException(MSG_NOT_FOUND));
        if (peek.isUnpaid() && !paymentService.invoicesWithOpenSession(List.of(invoiceId)).isEmpty()) {
            sessionCloser.closeNow(invoiceId, REOPEN_REASON);
        }
        return transaction.execute(status -> startLocked(invoiceId, customerId, ipAddr));
    }

    private PayResult startLocked(Long invoiceId, Long customerId, String ipAddr) {
        Long orderId = invoiceRepository.findOrderIdById(invoiceId).orElse(null);
        RentalOrder order = orderId == null ? null
                : orderRepository.findByIdForUpdate(orderId)
                        .orElseThrow(() -> new BookingNotFoundException(MSG_NOT_FOUND));
        Invoice invoice = invoiceRepository.findByIdForUpdate(invoiceId)
                .orElseThrow(() -> new BookingNotFoundException(MSG_NOT_FOUND));
        if (!invoice.getCustomerId().equals(customerId)) {
            // Hoá đơn của người khác: 404 để không lộ sự tồn tại.
            throw new BookingNotFoundException(MSG_NOT_FOUND);
        }
        if (!invoice.isUnpaid()) {
            throw new BookingConflictException(MSG_NOT_UNPAID);
        }
        Instant now = Instant.now();
        if (invoice.getDueDate() != null && invoice.getDueDate().isBefore(now)) {
            throw new BookingConflictException(MSG_OVERDUE);
        }

        // Hoá đơn gắn hợp đồng sau bàn giao (gia hạn, phạt, dịch vụ): đơn đã Done là bình thường — bỏ kiểm đơn.
        boolean afterHandover = order != null && invoice.getContractId() != null
                && order.getStatus() == vn.lemar.selfstorage.booking.domain.RentalOrderStatus.DONE;
        if (order != null && !afterHandover) {
            if (order.getStatus().isClosed()) {
                throw new BookingConflictException(MSG_ORDER_CLOSED);
            }
            if (order.isExpiredAt(now)) {
                return PayResult.rejected(MSG_ORDER_EXPIRED, false);
            }
            if (invoice.isDepositKind()) {
                PayResult unitCheck = checkUnit(order, invoice, customerId);
                if (unitCheck != null) {
                    return unitCheck;
                }
            }
        } else if (!paymentService.invoicesWithOpenSession(List.of(invoiceId)).isEmpty()) {
            return PayResult.rejected(MSG_OWN_SESSION_OPEN, false);
        }

        String url = paymentService.startInvoicePayment(invoiceId, invoice.getAmount(),
                "TT hoa don " + invoiceId, ipAddr);
        return PayResult.redirect(url);
    }

    /** Nhánh 2–4 của spec 1.4; {@code null} = khoang sẵn sàng thanh toán. */
    private PayResult checkUnit(RentalOrder order, Invoice invoice, Long customerId) {
        if (order.getUnitId() == null) {
            throw new BookingConflictException("Đơn chưa được gán khoang chứa");
        }
        StorageUnitView unit = storageUnitQuery.lock(order.getUnitId())
                .orElseThrow(() -> new BookingConflictException("Khoang chứa không còn tồn tại"));
        boolean maintenance = "Maintenance".equals(unit.status());
        if (!unit.isAvailable()) {
            invoice.cancel();
            String reason = maintenance ? MSG_UNIT_MAINTENANCE : MSG_UNIT_TAKEN;
            auditRecorder.record(new AuditEntry(AuditAction.INVOICE_CANCELED, AuditEntityType.INVOICE,
                    String.valueOf(invoice.getId()), Map.of("status", InvoiceStatus.UNPAID.dbValue()),
                    Map.of("status", invoice.getStatus().dbValue()), reason, customerId));
            orderNotifier.notifyFacilityManager(order.getId(), NotificationType.PROPOSAL_REPROPOSAL_REQUIRED,
                    Map.of("orderCode", OrderNotifier.orderCode(order), "unitCode", unit.code()));
            return PayResult.rejected(reason, true);
        }
        List<Long> open = paymentService.invoicesWithOpenSession(
                invoiceRepository.findUnpaidDepositIdsByUnitId(unit.id()));
        if (open.contains(invoice.getId())) {
            return PayResult.rejected(MSG_OWN_SESSION_OPEN, false);
        }
        if (!open.isEmpty()) {
            return PayResult.rejected(MSG_UNIT_BUSY, false);
        }
        return null;
    }

    /**
     * Khách quay về từ cổng: đối soát giao dịch mới nhất bằng {@code querydr} (nguồn dự phòng khi IPN chưa tới),
     * rồi đọc lại trạng thái hoá đơn. Không chạy trong transaction riêng — listener chốt thanh toán chạy sau commit
     * của bước đối soát, nên đọc lại phải sau đó.
     */
    public PaymentCheck check(Long invoiceId, Long customerId, String ipAddr) {
        Invoice before = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new BookingNotFoundException(MSG_NOT_FOUND));
        if (!before.getCustomerId().equals(customerId)) {
            throw new BookingNotFoundException(MSG_NOT_FOUND);
        }
        var snapshot = paymentService.reconcileLatest(invoiceId, ipAddr).orElse(null);
        Invoice after = invoiceRepository.findById(invoiceId).orElse(before);
        boolean depositPaid = after.getStatus() == InvoiceStatus.PAID && after.isDepositKind()
                && after.getOrderId() != null;
        Long bookOrderId = depositPaid && bookingService.bookable(after.getOrderId()) ? after.getOrderId() : null;
        return new PaymentCheck(invoiceId, after.getStatus().dbValue(),
                snapshot == null ? null : snapshot.status(),
                snapshot == null ? null : snapshot.failureReason(), bookOrderId);
    }

}
