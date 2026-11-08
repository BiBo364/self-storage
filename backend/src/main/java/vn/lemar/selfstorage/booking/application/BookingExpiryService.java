package vn.lemar.selfstorage.booking.application;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.lemar.selfstorage.audit.application.AuditEntry;
import vn.lemar.selfstorage.audit.application.AuditRecorder;
import vn.lemar.selfstorage.audit.domain.AuditAction;
import vn.lemar.selfstorage.audit.domain.AuditEntityType;
import vn.lemar.selfstorage.booking.domain.Invoice;
import vn.lemar.selfstorage.booking.domain.InvoiceStatus;
import vn.lemar.selfstorage.booking.domain.InvoiceType;
import vn.lemar.selfstorage.booking.domain.RentalOrder;
import vn.lemar.selfstorage.booking.domain.RentalOrderStatus;
import vn.lemar.selfstorage.booking.domain.RentalRequestStatus;
import vn.lemar.selfstorage.booking.repository.InvoiceRepository;
import vn.lemar.selfstorage.booking.repository.RentalOrderRepository;
import vn.lemar.selfstorage.booking.repository.RentalRequestRepository;
import vn.lemar.selfstorage.notification.application.NotificationService;
import vn.lemar.selfstorage.notification.application.NotificationType;
import vn.lemar.selfstorage.payment.application.PaymentService;
import vn.lemar.selfstorage.pricing.application.PricingQuery;

/**
 * Cron Flow 1 ({@code specs/draft.md} › Scheduled Jobs). Mỗi hàm public xử lý <b>một</b> bản ghi trong transaction
 * riêng — một bản ghi lỗi không chặn các bản ghi khác; điều kiện được kiểm lại sau khi khoá nên chạy lặp an toàn.
 *
 * <ul>
 *   <li>{@code RentalRequest} {@code Pending}/{@code Approved} quá {@code expires_at} → {@code Expired}.</li>
 *   <li>{@code Invoice} {@code Unpaid} quá {@code due_date}: {@code Deposit} → {@code Expired} + cascade "Quá hạn
 *       thanh toán cọc"; {@code DepositSupplement} → {@code Expired} + FM đề xuất lại; hoá đơn gắn hợp đồng →
 *       {@code Expired} + {@link ContractInvoiceExpired} cho module sở hữu hợp đồng. Hoá đơn còn phiên thanh toán
 *       mở (giao dịch {@code Pending} &lt; 15') thì đợi phiên đóng mới xử lý.</li>
 *   <li>{@code RentalOrder} quá {@code expires_at}, chưa đóng (catch-all) → cascade với lý do theo trạng thái.</li>
 *   <li>{@code RentalOrder} sắp hết hạn trong {@code order.expiry_reminder_days} → {@code RENTAL_ORDER_EXPIRING_SOON},
 *       mỗi đơn một lần (dedup qua {@code OrderNotification}).</li>
 * </ul>
 */
@Service
public class BookingExpiryService {

    static final String REASON_DEPOSIT_OVERDUE = "Quá hạn thanh toán cọc";
    static final String REASON_PROPOSAL_OVERDUE = "Quá hạn xác nhận đề xuất";
    static final String REASON_CHECKIN_BOOKING_OVERDUE = "Quá hạn đặt lịch check-in";
    static final String REASON_NO_SHOW_HOLD = "Không đến nhận kho quá hạn giữ";
    static final String REASON_HANDOVER_OVERDUE = "Chưa hoàn tất bàn giao trong hạn";
    /** Lý do huỷ do quá hạn — notification {@code RENTAL_ORDER_CANCELED} ghi "đã hết hạn" thay cho "đã bị hủy". */
    static final java.util.Set<String> EXPIRY_REASONS = java.util.Set.of(REASON_DEPOSIT_OVERDUE,
            REASON_PROPOSAL_OVERDUE, REASON_CHECKIN_BOOKING_OVERDUE, REASON_NO_SHOW_HOLD, REASON_HANDOVER_OVERDUE);
    static final String POLICY_REMINDER_DAYS = "order.expiry_reminder_days";

    private static final Set<RentalRequestStatus> OPEN_REQUEST = EnumSet.of(RentalRequestStatus.Pending,
            RentalRequestStatus.Approved);
    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter DISPLAY = DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy").withZone(ZONE);

    private final RentalRequestRepository requestRepository;
    private final RentalOrderRepository orderRepository;
    private final InvoiceRepository invoiceRepository;
    private final RentalOrderCancellation cancellation;
    private final PaymentService paymentService;
    private final PricingQuery pricingQuery;
    private final NotificationService notificationService;
    private final OrderNotifier orderNotifier;
    private final AuditRecorder auditRecorder;
    private final ApplicationEventPublisher events;

    BookingExpiryService(RentalRequestRepository requestRepository, RentalOrderRepository orderRepository,
                         InvoiceRepository invoiceRepository, RentalOrderCancellation cancellation,
                         PaymentService paymentService, PricingQuery pricingQuery,
                         NotificationService notificationService, OrderNotifier orderNotifier,
                         AuditRecorder auditRecorder, ApplicationEventPublisher events) {
        this.requestRepository = requestRepository;
        this.orderRepository = orderRepository;
        this.invoiceRepository = invoiceRepository;
        this.cancellation = cancellation;
        this.paymentService = paymentService;
        this.pricingQuery = pricingQuery;
        this.notificationService = notificationService;
        this.orderNotifier = orderNotifier;
        this.auditRecorder = auditRecorder;
        this.events = events;
    }

    // ---------- RentalRequest ----------

    @Transactional(readOnly = true)
    public List<Long> expiredRequestIds() {
        return requestRepository.findExpiredIds(OPEN_REQUEST, Instant.now());
    }

    @Transactional
    public boolean expireRequest(Long requestId) {
        RentalRequestStatus before = requestRepository.findStatusById(requestId).orElse(null);
        if (before == null || requestRepository.expireIfOpen(requestId, OPEN_REQUEST, Instant.now()) == 0) {
            return false;
        }
        auditRecorder.record(new AuditEntry(AuditAction.RENTAL_REQUEST_EXPIRED, AuditEntityType.RENTAL_REQUEST,
                String.valueOf(requestId), Map.of("status", before.name()),
                Map.of("status", RentalRequestStatus.Expired.name()), "Quá hạn xử lý yêu cầu", null));
        return true;
    }

    // ---------- Invoice ----------

    @Transactional(readOnly = true)
    public List<Long> overdueInvoiceIds() {
        return invoiceRepository.findOverdueUnpaidIds(Instant.now());
    }

    @Transactional
    public boolean expireInvoice(Long invoiceId) {
        // Phiên thanh toán còn mở thì đợi phiên đóng (IPN/querydr chốt) rồi mới xử lý.
        if (!paymentService.invoicesWithOpenSession(List.of(invoiceId)).isEmpty()) {
            return false;
        }
        Long orderId = invoiceRepository.findOrderIdById(invoiceId).orElse(null);
        RentalOrder order = orderId == null ? null : orderRepository.findByIdForUpdate(orderId).orElse(null);
        Invoice invoice = invoiceRepository.findByIdForUpdate(invoiceId).orElse(null);
        Instant now = Instant.now();
        if (invoice == null || !invoice.isUnpaid() || invoice.getDueDate() == null
                || !invoice.getDueDate().isBefore(now)) {
            return false;
        }
        invoice.expire();
        auditRecorder.record(new AuditEntry(AuditAction.INVOICE_EXPIRED, AuditEntityType.INVOICE,
                String.valueOf(invoice.getId()), Map.of("status", InvoiceStatus.UNPAID.dbValue()),
                Map.of("status", invoice.getStatus().dbValue()), "Quá hạn thanh toán", null));

        if (order != null && invoice.getType() == InvoiceType.DEPOSIT && !order.getStatus().isClosed()) {
            cancellation.cancel(order, REASON_DEPOSIT_OVERDUE, null);
        } else if (order != null && invoice.getType() == InvoiceType.DEPOSIT_SUPPLEMENT
                && !order.getStatus().isClosed()) {
            order.requireReproposal();
            orderNotifier.notifyFacilityManager(order.getId(), NotificationType.PROPOSAL_REPROPOSAL_REQUIRED,
                    Map.of("orderCode", OrderNotifier.orderCode(order), "unitCode", ""));
        } else if (invoice.getContractId() != null) {
            events.publishEvent(new ContractInvoiceExpired(invoice.getId(), invoice.getContractId(),
                    invoice.getType().dbValue()));
        }
        return true;
    }

    // ---------- RentalOrder catch-all ----------

    @Transactional(readOnly = true)
    public List<Long> expiredOrderIds() {
        return orderRepository.findExpiredOpenIds(Instant.now());
    }

    @Transactional
    public boolean cancelExpiredOrder(Long orderId) {
        RentalOrder order = orderRepository.findByIdForUpdate(orderId).orElse(null);
        Instant now = Instant.now();
        if (order == null || order.getStatus().isClosed() || !order.isExpiredAt(now)) {
            return false;
        }
        // Phiên thanh toán cọc còn mở → đợi phiên đóng.
        List<Long> unpaid = invoiceRepository.findByOrderIdAndStatus(orderId, InvoiceStatus.UNPAID).stream()
                .map(Invoice::getId).toList();
        if (!paymentService.invoicesWithOpenSession(unpaid).isEmpty()) {
            return false;
        }
        cancellation.cancel(order, catchAllReason(order), null);
        return true;
    }

    private String catchAllReason(RentalOrder order) {
        RentalOrderStatus status = order.getStatus();
        if (status == RentalOrderStatus.PENDING) {
            String proposal = orderRepository.findLatestProposalStatus(order.getId()).orElse(null);
            return "Agreed".equals(proposal) ? REASON_DEPOSIT_OVERDUE : REASON_PROPOSAL_OVERDUE;
        }
        if (status == RentalOrderStatus.DEPOSITED) {
            return REASON_CHECKIN_BOOKING_OVERDUE;
        }
        return orderRepository.hasArrivedAppointment(order.getId()) ? REASON_HANDOVER_OVERDUE : REASON_NO_SHOW_HOLD;
    }

    // ---------- Nhắc đơn sắp hết hạn ----------

    @Transactional(readOnly = true)
    public List<Long> expiringOrderIds() {
        Instant now = Instant.now();
        return orderRepository.findExpiringOpenIds(now,
                now.plus(Duration.ofDays(pricingQuery.intPolicy(POLICY_REMINDER_DAYS))));
    }

    @Transactional
    public boolean remindExpiring(Long orderId) {
        RentalOrder order = orderRepository.findById(orderId).orElse(null);
        if (order == null || order.getStatus().isClosed() || order.getExpiresAt() == null
                || notificationService.hasOrderNotification(orderId, NotificationType.RENTAL_ORDER_EXPIRING_SOON)) {
            return false;
        }
        long daysLeft = Math.max(0, Duration.between(Instant.now(), order.getExpiresAt()).toDays());
        orderNotifier.notifyCustomer(order, NotificationType.RENTAL_ORDER_EXPIRING_SOON,
                Map.of("orderCode", OrderNotifier.orderCode(order),
                        "expiresAt", DISPLAY.format(order.getExpiresAt()),
                        "daysLeft", String.valueOf(daysLeft)));
        return true;
    }
}
