package vn.lemar.selfstorage.booking.application;

import java.util.List;
import java.util.Map;
import org.springframework.context.ApplicationEventPublisher;
import vn.lemar.selfstorage.payment.application.PaymentSessionCloser;
import org.springframework.stereotype.Component;
import vn.lemar.selfstorage.audit.application.AuditEntry;
import vn.lemar.selfstorage.audit.application.AuditRecorder;
import vn.lemar.selfstorage.audit.domain.AuditAction;
import vn.lemar.selfstorage.audit.domain.AuditEntityType;
import vn.lemar.selfstorage.booking.domain.Appointment;
import vn.lemar.selfstorage.booking.domain.AppointmentStatus;
import vn.lemar.selfstorage.booking.domain.Invoice;
import vn.lemar.selfstorage.booking.domain.InvoiceStatus;
import vn.lemar.selfstorage.booking.domain.ProposalFeedback;
import vn.lemar.selfstorage.booking.domain.ProposalStatus;
import vn.lemar.selfstorage.booking.domain.RentalOrder;
import vn.lemar.selfstorage.booking.repository.AppointmentRepository;
import vn.lemar.selfstorage.booking.repository.InvoiceRepository;
import vn.lemar.selfstorage.booking.repository.ProposalFeedbackRepository;
import vn.lemar.selfstorage.facility.application.StorageUnitService;
import vn.lemar.selfstorage.notification.application.NotificationType;

/**
 * RentalOrder Cancellation Cascade ({@code specs/db-table-draft.md} › Cancellation Cascade), phần thuộc
 * booking. Chạy trong transaction của caller, đơn đã được caller khoá.
 *
 * <p>{@code RentalContract Draft/Signed -> Canceled} và {@code HandoverRecord IN_PROGRESS -> CANCELED} do module
 * handover làm khi nghe {@link RentalOrderCanceled} (cùng transaction). Tiền cọc: hoàn thủ công theo Flow 4.
 *
 * <p>Khoá: caller đã khoá đơn; cascade khoá lịch {@code Pending} sau đó, giữ thứ tự đơn → lịch.
 */
@Component
class RentalOrderCancellation {

    static final String OUTCOME_CANCELED = "đã bị hủy";
    static final String OUTCOME_EXPIRED = "đã hết hạn";
    static final String CANCEL_SESSION_REASON = "Don thue da bi huy";

    private final ProposalFeedbackRepository proposalRepository;
    private final InvoiceRepository invoiceRepository;
    private final AppointmentRepository appointmentRepository;
    private final StorageUnitService storageUnitService;
    private final AuditRecorder auditRecorder;
    private final OrderNotifier orderNotifier;
    private final ApplicationEventPublisher events;
    private final PaymentSessionCloser sessionCloser;

    RentalOrderCancellation(ProposalFeedbackRepository proposalRepository, InvoiceRepository invoiceRepository,
                            AppointmentRepository appointmentRepository, StorageUnitService storageUnitService,
                            AuditRecorder auditRecorder, OrderNotifier orderNotifier,
                            ApplicationEventPublisher events, PaymentSessionCloser sessionCloser) {
        this.events = events;
        this.sessionCloser = sessionCloser;
        this.proposalRepository = proposalRepository;
        this.invoiceRepository = invoiceRepository;
        this.appointmentRepository = appointmentRepository;
        this.storageUnitService = storageUnitService;
        this.auditRecorder = auditRecorder;
        this.orderNotifier = orderNotifier;
    }

    /**
     * @param actorAccountId người gây ra việc huỷ; {@code null} = hệ thống/cron
     */
    void cancel(RentalOrder order, String reason, Long actorAccountId) {
        String oldStatus = order.getStatus().dbValue();
        // Huỷ vì quá hạn (cron theo lý do, hoặc hệ thống đóng đơn đã qua expires_at) → "đã hết hạn".
        String outcome = BookingExpiryService.EXPIRY_REASONS.contains(reason)
                || (actorAccountId == null && order.isExpiredAt(java.time.Instant.now()))
                ? OUTCOME_EXPIRED : OUTCOME_CANCELED;
        // Chụp trước khi đổi status: đơn chỉ giữ khoang khi đang ở Deposited/Scheduled/InProgress.
        boolean heldUnit = order.holdsUnit();
        order.cancel(reason);
        record(AuditAction.RENTAL_ORDER_CANCELED, AuditEntityType.RENTAL_ORDER, order.getId(),
                Map.of("status", oldStatus),
                Map.of("status", order.getStatus().dbValue(), "cancelReason", reason), reason, actorAccountId);

        for (ProposalFeedback proposal : proposalRepository.findByOrderIdAndStatusIn(order.getId(),
                List.of(ProposalStatus.PENDING, ProposalStatus.AGREED))) {
            String before = proposal.getStatus().dbValue();
            proposal.cancel();
            record(AuditAction.RENTAL_ORDER_CANCELED, AuditEntityType.PROPOSAL_FEEDBACK, proposal.getId(),
                    Map.of("status", before), Map.of("status", proposal.getStatus().dbValue()), reason,
                    actorAccountId);
        }

        List<Long> canceledInvoiceIds = new java.util.ArrayList<>();
        for (Invoice invoice : invoiceRepository.findByOrderIdAndStatus(order.getId(), InvoiceStatus.UNPAID)) {
            canceledInvoiceIds.add(invoice.getId());
            invoice.cancel();
            record(AuditAction.INVOICE_CANCELED, AuditEntityType.INVOICE, invoice.getId(),
                    Map.of("status", InvoiceStatus.UNPAID.dbValue()), Map.of("status", invoice.getStatus().dbValue()),
                    reason, actorAccountId);
        }

        // Huỷ phiên thanh toán còn mở ở cổng (payOS: huỷ link) sau commit, best-effort — không rollback việc huỷ.
        sessionCloser.closeAfterCommit(canceledInvoiceIds, CANCEL_SESSION_REASON);

        // Lịch Pending chưa diễn ra bị huỷ theo đơn; actor = System theo catalog audit.
        for (Long appointmentId : appointmentRepository.findPendingIdsByOrderId(order.getId())) {
            appointmentRepository.findByIdForUpdate(appointmentId)
                    .filter(appointment -> appointment.getStatus() == AppointmentStatus.Pending)
                    .ifPresent(appointment -> cancelAppointment(appointment, reason));
        }

        // Chỉ nhả khoang khi đơn thực sự đang giữ nó: nhiều đơn có thể cùng unit_id (đã đồng ý, chưa cọc),
        // trả khoang của đơn khác đã cọc là sai.
        if (heldUnit && order.getUnitId() != null
                && storageUnitService.releaseIfReserved(order.getUnitId(), order.getId())) {
            record(AuditAction.RENTAL_ORDER_CANCELED, AuditEntityType.STORAGE_UNIT, order.getUnitId(),
                    Map.of("status", "Reserved"), Map.of("status", "Available"), reason, actorAccountId);
        }

        // Phần của handover (HandoverRecord đang mở, RentalContract Draft/Signed) — booking không gọi chéo module.
        events.publishEvent(new RentalOrderCanceled(order.getId(), order.getUnitId(), reason));

        // Khách tự chịu rủi ro mất cọc khi huỷ do quá ngưỡng từ chối.
        String notifyReason = ProposalService.CANCEL_REASON_TOO_MANY_REJECTIONS.equals(reason)
                ? reason + ". Tiền cọc không được hoàn" : reason;
        Map<String, String> values = Map.of("orderCode", OrderNotifier.orderCode(order),
                "outcome", outcome, "reason", notifyReason);
        orderNotifier.notifyCustomer(order, NotificationType.RENTAL_ORDER_CANCELED, values);
        orderNotifier.notifyFacilityManager(order.getId(), NotificationType.RENTAL_ORDER_CANCELED, values);
    }

    private void cancelAppointment(Appointment appointment, String reason) {
        appointment.cancel(reason);
        record(AuditAction.APPOINTMENT_CANCELED, AuditEntityType.APPOINTMENT, appointment.getId(),
                Map.of("status", AppointmentStatus.Pending.name()),
                Map.of("status", AppointmentStatus.Canceled.name()), reason, null);
    }

    private void record(AuditAction action, AuditEntityType type, Long entityId, Object oldValue, Object newValue,
                        String reason, Long actorAccountId) {
        auditRecorder.record(new AuditEntry(action, type, String.valueOf(entityId), oldValue, newValue, reason,
                actorAccountId));
    }
}
