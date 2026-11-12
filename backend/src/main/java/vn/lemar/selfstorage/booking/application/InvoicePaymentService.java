package vn.lemar.selfstorage.booking.application;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.lemar.selfstorage.booking.application.exception.BookingConflictException;
import vn.lemar.selfstorage.booking.application.exception.BookingNotFoundException;
import vn.lemar.selfstorage.booking.application.exception.DepositNotAllowedException;
import vn.lemar.selfstorage.booking.domain.Invoice;
import vn.lemar.selfstorage.booking.domain.InvoiceStatus;
import vn.lemar.selfstorage.booking.domain.RentalOrder;
import vn.lemar.selfstorage.booking.domain.RentalOrderStatus;
import vn.lemar.selfstorage.booking.repository.InvoiceRepository;
import vn.lemar.selfstorage.booking.repository.RentalOrderRepository;
import vn.lemar.selfstorage.payment.application.PaymentService;
import vn.lemar.selfstorage.payment.application.PaymentSessionQuery;

/**
 * Khách bấm "Tiến hành thanh toán" một hoá đơn ({@code POST /api/invoices/{id}/pay}).
 *
 * <ul>
 *   <li>Hoá đơn cọc ({@code Deposit}/{@code DepositSupplement}): giao cho {@link DepositPaymentService} — năm nhánh
 *       kiểm của spec 1.4, từ chối là {@code DepositNotAllowedException} (409).</li>
 *   <li>Hoá đơn gắn hợp đồng (tháng đầu, gia hạn, tiền thuê, phạt…): hoá đơn còn {@code Unpaid}, chưa quá
 *       {@code due_date}, đơn chưa đóng / chưa quá hạn (trừ hoá đơn sau bàn giao trên đơn {@code Done}).</li>
 * </ul>
 *
 * <p>Cả hai nhánh cùng luật phiên của payment: đã có {@code PAY/Success} → 409; còn phiên {@code Pending} trong
 * cửa sổ 15' → trả lại URL cũ (cache / dựng lại), không mở phiên thứ hai; không dựng lại được → 409.
 */
@Service
public class InvoicePaymentService {

    static final String MSG_ORDER_EXPIRED = "Đơn thuê kho đã hết hạn. Vui lòng liên hệ cơ sở để được hỗ trợ.";
    static final String MSG_OWN_SESSION_OPEN = "Hoá đơn đang có phiên thanh toán chưa kết thúc. Vui lòng hoàn tất "
            + "phiên đang mở hoặc thử lại sau ít phút.";
    static final String MSG_ALREADY_PAID =
            "Hóa đơn này đã được thanh toán thành công. Vui lòng chờ hệ thống xác nhận.";
    static final String MSG_NOT_FOUND = "Không tìm thấy hoá đơn";
    static final String MSG_NOT_UNPAID = "Hoá đơn không còn ở trạng thái chờ thanh toán";
    static final String MSG_OVERDUE = "Hoá đơn đã quá hạn thanh toán";
    static final String MSG_ORDER_CLOSED = "Đơn thuê đã đóng, không thể thanh toán hoá đơn này";

    private final InvoiceRepository invoiceRepository;
    private final RentalOrderRepository orderRepository;
    private final PaymentService paymentService;
    private final PaymentSessionQuery sessions;
    private final DepositPaymentService depositPayments;
    private final TransactionTemplate transaction;
    private final AppointmentBookingService bookingService;

    InvoicePaymentService(InvoiceRepository invoiceRepository, RentalOrderRepository orderRepository,
                          PaymentService paymentService, PaymentSessionQuery sessions,
                          DepositPaymentService depositPayments, PlatformTransactionManager transactionManager,
                          AppointmentBookingService bookingService) {
        this.invoiceRepository = invoiceRepository;
        this.orderRepository = orderRepository;
        this.paymentService = paymentService;
        this.sessions = sessions;
        this.depositPayments = depositPayments;
        this.transaction = new TransactionTemplate(transactionManager);
        this.bookingService = bookingService;
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

    /**
     * Trạng thái thanh toán của hoá đơn sau khi hỏi cổng.
     *
     * @param bookCheckinOrderId đơn vừa trả cọc xong và đặt được lịch check-in ngay → FE chuyển khách sang đặt lịch
     *                           (spec 1.4 → 1.5); {@code null} nếu không áp dụng
     */
    public record PaymentCheck(Long invoiceId, String invoiceStatus, String paymentStatus, String failureReason,
                               Long bookCheckinOrderId) {
    }

    public PayResult start(Long invoiceId, Long customerId, String ipAddr) {
        Invoice peek = invoiceRepository.findById(invoiceId)
                .filter(i -> i.getCustomerId().equals(customerId))
                .orElseThrow(() -> new BookingNotFoundException(MSG_NOT_FOUND));
        if (peek.isDepositKind()) {
            try {
                return PayResult.redirect(depositPayments.startDeposit(invoiceId, ipAddr));
            } catch (DepositNotAllowedException rejected) {
                // Nhánh khoang mất / bảo trì đã huỷ hoá đơn (commit trước khi ném) — báo FE để tải lại.
                boolean canceledNow = peek.getStatus() != InvoiceStatus.CANCELED
                        && invoiceRepository.findById(invoiceId)
                                .map(found -> found.getStatus() == InvoiceStatus.CANCELED).orElse(false);
                return PayResult.rejected(rejected.getMessage(), canceledNow);
            }
        }
        return transaction.execute(status -> startContractInvoice(invoiceId, customerId, ipAddr));
    }

    /** Khoá đơn → hoá đơn (cùng thứ tự với listener thanh toán và cascade huỷ). */
    private PayResult startContractInvoice(Long invoiceId, Long customerId, String ipAddr) {
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
        boolean afterHandover = order != null && order.getStatus() == RentalOrderStatus.DONE;
        if (order != null && !afterHandover) {
            if (order.getStatus().isClosed()) {
                throw new BookingConflictException(MSG_ORDER_CLOSED);
            }
            if (order.isExpiredAt(now)) {
                return PayResult.rejected(MSG_ORDER_EXPIRED, false);
            }
        }
        // IPN đã ghi PAY/Success nhưng listener chưa chạy: không mở phiên để khách trả lần hai.
        if (sessions.hasSuccessfulPayment(invoiceId)) {
            throw new BookingConflictException(MSG_ALREADY_PAID);
        }
        // Khách bấm lại khi phiên cũ còn trong cửa sổ: trả URL cũ, không mở phiên thứ hai.
        if (sessions.hasOpenSessionForInvoice(invoiceId)) {
            Optional<String> url = sessions.openPaymentUrl(invoiceId)
                    .or(() -> paymentService.resumePayment(invoiceId, ipAddr));
            return url.map(PayResult::redirect).orElseGet(() -> PayResult.rejected(MSG_OWN_SESSION_OPEN, false));
        }
        String url = paymentService.startInvoicePayment(invoiceId, invoice.getAmount(),
                "TT hoa don " + invoiceId, ipAddr);
        return PayResult.redirect(url);
    }

    /** {@link #check(Long, Long, String, Map)} không kèm tham số cổng. */
    public PaymentCheck check(Long invoiceId, Long customerId, String ipAddr) {
        return check(invoiceId, customerId, ipAddr, Map.of());
    }

    /**
     * Khách quay về từ cổng: chốt bằng tham số VNPay có chữ ký trên Return URL (nếu FE gửi kèm), rồi hỏi cổng về giao
     * dịch mới nhất (nguồn dự phòng khi IPN / webhook chưa tới), rồi đọc lại trạng thái hoá đơn. Không chạy trong
     * transaction riêng — listener chốt thanh toán chạy sau commit của từng bước, nên đọc lại phải sau đó.
     *
     * @param gatewayParams tham số {@code vnp_*} trên Return URL; rỗng thì chỉ hỏi cổng
     */
    public PaymentCheck check(Long invoiceId, Long customerId, String ipAddr, Map<String, String> gatewayParams) {
        Invoice before = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new BookingNotFoundException(MSG_NOT_FOUND));
        if (!before.getCustomerId().equals(customerId)) {
            throw new BookingNotFoundException(MSG_NOT_FOUND);
        }
        if (gatewayParams != null && !gatewayParams.isEmpty()) {
            paymentService.confirmFromReturn(invoiceId, gatewayParams);
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
