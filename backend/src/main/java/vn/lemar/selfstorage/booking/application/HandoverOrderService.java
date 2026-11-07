package vn.lemar.selfstorage.booking.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.lemar.selfstorage.audit.application.AuditEntry;
import vn.lemar.selfstorage.audit.application.AuditRecorder;
import vn.lemar.selfstorage.audit.domain.AuditAction;
import vn.lemar.selfstorage.audit.domain.AuditEntityType;
import vn.lemar.selfstorage.booking.application.exception.BookingConflictException;
import vn.lemar.selfstorage.booking.application.exception.RentalOrderNotFoundException;
import vn.lemar.selfstorage.booking.domain.Invoice;
import vn.lemar.selfstorage.booking.domain.InvoiceStatus;
import vn.lemar.selfstorage.booking.domain.InvoiceType;
import vn.lemar.selfstorage.booking.domain.RentalOrder;
import vn.lemar.selfstorage.booking.domain.RentalOrderStatus;
import vn.lemar.selfstorage.booking.repository.InvoiceRepository;
import vn.lemar.selfstorage.booking.repository.RentalOrderRepository;
import vn.lemar.selfstorage.notification.application.NotificationType;
import vn.lemar.selfstorage.payment.application.PaymentSessionCloser;

/**
 * Public API của booking cho Flow 2 (handover): đọc/khoá đơn, lập hoá đơn tháng đầu, chốt hoặc trả đơn về sau phiên
 * bàn giao. Booking vẫn là nơi duy nhất đổi trạng thái {@code RentalOrder} và {@code Invoice}; handover chỉ gọi vào
 * đây, không chạm repository của booking.
 *
 * <p>Các hàm ghi chạy trong transaction của caller ({@code MANDATORY}). Thứ tự khoá: caller khoá đơn qua
 * {@link #lock} trước khi khoá {@code HandoverRecord}/{@code RentalContract}, cùng chiều với Cancellation Cascade.
 */
@Service
public class HandoverOrderService {

    static final String MSG_ORDER_NOT_IN_PROGRESS = "Đơn thuê không ở trạng thái đang bàn giao";

    private final RentalOrderRepository orderRepository;
    private final InvoiceRepository invoiceRepository;
    private final DepositInvoiceIssuer invoiceIssuer;
    private final RentalOrderCancellation cancellation;
    private final OrderNotifier orderNotifier;
    private final AuditRecorder auditRecorder;
    private final PaymentSessionCloser sessionCloser;

    HandoverOrderService(RentalOrderRepository orderRepository, InvoiceRepository invoiceRepository,
                         DepositInvoiceIssuer invoiceIssuer, RentalOrderCancellation cancellation,
                         OrderNotifier orderNotifier, AuditRecorder auditRecorder,
                         PaymentSessionCloser sessionCloser) {
        this.sessionCloser = sessionCloser;
        this.orderRepository = orderRepository;
        this.invoiceRepository = invoiceRepository;
        this.invoiceIssuer = invoiceIssuer;
        this.cancellation = cancellation;
        this.orderNotifier = orderNotifier;
        this.auditRecorder = auditRecorder;
    }

    /**
     * Ảnh chụp đơn cho phiên bàn giao.
     *
     * @param depositPaid tổng cọc + cọc bổ sung đã {@code Paid} — ghi vào hợp đồng
     */
    public record OrderSnapshot(Long orderId, Long customerId, Long unitId, Long facilityId, String status,
                                Instant expiresAt, String orderCode, BigDecimal depositPaid) {

        public boolean inProgress() {
            return RentalOrderStatus.IN_PROGRESS.dbValue().equals(status);
        }

        public boolean expiredAt(Instant now) {
            return expiresAt != null && expiresAt.isBefore(now);
        }
    }

    /** Hoá đơn gắn hợp đồng (tháng đầu). */
    public record ContractInvoice(Long invoiceId, String code, String status, BigDecimal amount, Instant dueDate) {

        public boolean paid() {
            return InvoiceStatus.PAID.dbValue().equals(status);
        }
    }

    /** Kết quả trả đơn về sau phiên bàn giao bị đóng. */
    public enum Release {
        /** Đơn còn hạn giữ kho → {@code Deposited}, khoang vẫn {@code Reserved}, khách đặt lịch mới. */
        RETURNED_TO_DEPOSITED,
        /** Đơn đã quá {@code expires_at} → Cancellation Cascade. */
        CANCELED,
        /** Đơn đã đóng hoặc không ở trạng thái có phiên bàn giao — không đổi gì. */
        NONE
    }

    @Transactional(readOnly = true)
    public Optional<OrderSnapshot> find(Long orderId) {
        return orderRepository.findById(orderId).map(this::snapshot);
    }

    /** Khoá row đơn (bước đầu của mọi thao tác ghi trên phiên bàn giao). */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<OrderSnapshot> lock(Long orderId) {
        return orderRepository.findByIdForUpdate(orderId).map(this::snapshot);
    }

    /**
     * Spec 2.3 giai đoạn 2: hoá đơn {@code Rental} tiền tố {@code RNT} gắn {@code contract_id} (CHECK V1: hoá đơn có
     * đúng một nguồn). Chốt thanh toán phát {@link ContractInvoicePaid}; huỷ hợp đồng chưa bàn giao thì handover gọi
     * {@link #closeContractInvoices}. Cọc Flow 1.4 không trừ vào hoá đơn này.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public ContractInvoice issueFirstMonthInvoice(Long orderId, Long contractId, String unitCode, BigDecimal amount,
                                                  Instant dueDate, Long actorAccountId) {
        RentalOrder order = requireInProgress(orderId);
        Invoice invoice = invoiceIssuer.issueFirstMonth(order, contractId, unitCode, amount, dueDate,
                actorAccountId);
        return contractInvoice(invoice);
    }

    /** Hoá đơn tháng đầu mới nhất của hợp đồng. */
    @Transactional(readOnly = true)
    public Optional<ContractInvoice> firstMonthInvoice(Long contractId) {
        return invoiceRepository.findByContractIdOrderByIdDesc(contractId).stream()
                .filter(invoice -> invoice.getType() == InvoiceType.RENTAL)
                .findFirst()
                .map(HandoverOrderService::contractInvoice);
    }

    /** Spec 2.4: bàn giao hoàn tất → {@code InProgress -> Done}. Flow 2 là nơi duy nhất gọi. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void complete(Long orderId, Long actorAccountId) {
        RentalOrder order = requireInProgress(orderId);
        order.complete();
        auditRecorder.record(new AuditEntry(AuditAction.RENTAL_ORDER_DONE, AuditEntityType.RENTAL_ORDER,
                String.valueOf(orderId), Map.of("status", RentalOrderStatus.IN_PROGRESS.dbValue()),
                Map.of("status", order.getStatus().dbValue()), null, actorAccountId));
    }

    /**
     * Phiên bàn giao bị đóng không phải do khách huỷ (quá hạn bàn giao, quá hạn thanh toán tháng đầu, không đến):
     * đơn còn hạn → {@code Deposited}; quá {@code expires_at} → Cancellation Cascade với {@code reason}.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Release release(Long orderId, String reason, Long actorAccountId) {
        RentalOrder order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(RentalOrderNotFoundException::new);
        return release(order, reason, actorAccountId);
    }

    Release release(RentalOrder order, String reason, Long actorAccountId) {
        if (order.getStatus().isClosed()) {
            return Release.NONE;
        }
        if (order.isExpiredAt(Instant.now())) {
            cancellation.cancel(order, reason, actorAccountId);
            return Release.CANCELED;
        }
        if (order.getStatus() != RentalOrderStatus.SCHEDULED && order.getStatus() != RentalOrderStatus.IN_PROGRESS) {
            return Release.NONE;
        }
        order.returnToDeposited();
        return Release.RETURNED_TO_DEPOSITED;
    }

    /**
     * Spec 2.5 — khách từ chối khoang, chưa chạm ngưỡng: đơn về {@code Deposited} + bật {@code needs_reproposal},
     * FM nhận {@code HANDOVER_REJECTED}. Khoang vẫn {@code Reserved} cho tới khi khách đồng ý khoang mới (bước đồng ý
     * đề xuất nhả khoang cũ) hoặc đơn bị huỷ.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void rejectUnit(Long orderId, String unitCode, String reason) {
        RentalOrder order = requireInProgress(orderId);
        order.returnToDeposited();
        order.requireReproposal();
        Map<String, Object> values = new HashMap<>();
        values.put("orderCode", OrderNotifier.orderCode(order));
        values.put("unitCode", unitCode);
        values.put("reason", reason);
        orderNotifier.notifyFacilityManager(orderId, NotificationType.HANDOVER_REJECTED, values);
    }

    /** Cancellation Cascade từ Flow 2 (khách không ký, từ chối quá ngưỡng). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void cancel(Long orderId, String reason, Long actorAccountId) {
        RentalOrder order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(RentalOrderNotFoundException::new);
        if (order.getStatus().isClosed()) {
            throw new BookingConflictException("Đơn thuê đã đóng");
        }
        cancellation.cancel(order, reason, actorAccountId);
    }

    /**
     * Hợp đồng chưa bàn giao vừa bị huỷ: hoá đơn tháng đầu còn {@code Unpaid} → {@code Canceled} (đóng phiên cổng sau
     * commit); đã {@code Paid} → FM nhận {@code FIRST_MONTH_REFUND_REQUIRED} để hoàn thủ công (spec 2.5 "Sau trả tiền,
     * trước nhận khoang").
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void closeContractInvoices(Long orderId, Long contractId, String reason) {
        List<Long> canceled = new java.util.ArrayList<>();
        for (Invoice found : invoiceRepository.findByContractIdOrderByIdDesc(contractId)) {
            Invoice invoice = invoiceRepository.findByIdForUpdate(found.getId()).orElse(found);
            if (invoice.isUnpaid()) {
                invoice.cancel();
                canceled.add(invoice.getId());
                auditRecorder.record(new AuditEntry(AuditAction.INVOICE_CANCELED, AuditEntityType.INVOICE,
                        String.valueOf(invoice.getId()), Map.of("status", InvoiceStatus.UNPAID.dbValue()),
                        Map.of("status", invoice.getStatus().dbValue()), reason, null));
            } else if (invoice.getStatus() == InvoiceStatus.PAID && invoice.getType() == InvoiceType.RENTAL) {
                Map<String, Object> values = new HashMap<>();
                values.put("orderCode", String.valueOf(orderId));
                values.put("refundAmount", invoice.getAmount().toPlainString());
                values.put("reason", reason);
                orderNotifier.notifyFacilityManager(orderId, NotificationType.FIRST_MONTH_REFUND_REQUIRED, values);
            }
        }
        sessionCloser.closeAfterCommit(canceled, "Hop dong da bi huy");
    }

    /** Báo khách của đơn (in-app + email) — dùng cho notification của Flow 2. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void notifyCustomer(Long orderId, NotificationType type, Map<String, ?> values) {
        RentalOrder order = orderRepository.findById(orderId).orElseThrow(RentalOrderNotFoundException::new);
        orderNotifier.notifyCustomer(order, type, values);
    }

    private RentalOrder requireInProgress(Long orderId) {
        RentalOrder order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(RentalOrderNotFoundException::new);
        if (order.getStatus() != RentalOrderStatus.IN_PROGRESS) {
            throw new BookingConflictException(MSG_ORDER_NOT_IN_PROGRESS);
        }
        return order;
    }

    private OrderSnapshot snapshot(RentalOrder order) {
        Long facilityId = orderRepository.findFacilityId(order.getId()).orElse(null);
        BigDecimal depositPaid = invoiceRepository.sumAmount(order.getId(),
                List.of(InvoiceType.DEPOSIT, InvoiceType.DEPOSIT_SUPPLEMENT), InvoiceStatus.PAID);
        return new OrderSnapshot(order.getId(), order.getCustomerId(), order.getUnitId(), facilityId,
                order.getStatus().dbValue(), order.getExpiresAt(), OrderNotifier.orderCode(order), depositPaid);
    }

    private static ContractInvoice contractInvoice(Invoice invoice) {
        return new ContractInvoice(invoice.getId(), invoice.getCode(), invoice.getStatus().dbValue(),
                invoice.getAmount(), invoice.getDueDate());
    }
}
