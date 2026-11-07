package vn.lemar.selfstorage.handover.application;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.lemar.selfstorage.audit.application.AuditEntry;
import vn.lemar.selfstorage.audit.application.AuditRecorder;
import vn.lemar.selfstorage.audit.domain.AuditAction;
import vn.lemar.selfstorage.audit.domain.AuditEntityType;
import vn.lemar.selfstorage.booking.application.HandoverOrderService;
import vn.lemar.selfstorage.booking.application.RentalOrderQuery;
import vn.lemar.selfstorage.handover.domain.HandoverRecord;
import vn.lemar.selfstorage.handover.domain.HandoverResult;
import vn.lemar.selfstorage.handover.domain.RentalContract;
import vn.lemar.selfstorage.handover.domain.RentalContractStatus;
import vn.lemar.selfstorage.handover.repository.HandoverRecordRepository;
import vn.lemar.selfstorage.handover.repository.RentalContractRepository;
import vn.lemar.selfstorage.pricing.application.PricingQuery;

/**
 * Hành vi Flow 2.1/2.2 trên dữ liệu bàn giao:
 * <ul>
 *   <li>{@code onAppointmentArrived}: khách đến — set {@code due_at} một lần.</li>
 *   <li>{@code cancelByAppointmentId}/{@code cancelByOrderId}: đóng phiên bàn giao đang mở và huỷ
 *       hợp đồng chưa bàn giao của đơn. Hai entry point chỉ khác khoá tra cứu; luật huỷ giống nhau.</li>
 * </ul>
 *
 * <p>Gọi từ Cancellation Cascade ({@code RentalOrderCanceled}), lịch check-in bị huỷ/no-show
 * ({@code CheckinAppointmentClosed}), cron quá hạn bàn giao và quá hạn thanh toán tháng đầu.
 */
@Service
public class HandoverService {

    static final String DUE_DAYS_POLICY = "handover.due_days";
    /** Sàn cấu hình: 1 ngày (24 giờ), trên mức tối thiểu 2 giờ của nghiệp vụ. */
    static final int MIN_DUE_DAYS = 1;

    private static final Logger LOG = LoggerFactory.getLogger(HandoverService.class);
    private static final List<RentalContractStatus> CANCELABLE_CONTRACTS =
            List.of(RentalContractStatus.DRAFT, RentalContractStatus.SIGNED);

    private final HandoverRecordRepository handoverRecords;
    private final RentalContractRepository contracts;
    private final RentalOrderQuery orderQuery;
    private final PricingQuery pricingQuery;
    private final AuditRecorder auditRecorder;
    private final HandoverOrderService orders;

    HandoverService(HandoverRecordRepository handoverRecords, RentalContractRepository contracts,
                    RentalOrderQuery orderQuery, PricingQuery pricingQuery, AuditRecorder auditRecorder,
                    HandoverOrderService orders) {
        this.orders = orders;
        this.handoverRecords = handoverRecords;
        this.contracts = contracts;
        this.orderQuery = orderQuery;
        this.pricingQuery = pricingQuery;
        this.auditRecorder = auditRecorder;
    }

    /**
     * Flow 2.1: {@code AppointmentArrived} → set {@code due_at = min(arrived_at + handover.due_days,
     * RentalOrder.expires_at)}. Idempotent: record không tồn tại (Flow 1 chưa tạo), đã đóng hoặc đã
     * có {@code due_at} thì bỏ qua. Chạy trong transaction của caller.
     */
    @Transactional
    public void onAppointmentArrived(Long appointmentId, Instant arrivedAt) {
        Objects.requireNonNull(arrivedAt, "arrivedAt");
        HandoverRecord record = handoverRecords.findByAppointmentIdForUpdate(appointmentId).orElse(null);
        if (record == null) {
            LOG.debug("Không có HandoverRecord cho lịch hẹn {} — bỏ qua due_at", appointmentId);
            return;
        }
        if (record.getResult() != HandoverResult.IN_PROGRESS || record.getDueAt() != null) {
            return;
        }
        record.setDueAt(dueAt(record, arrivedAt));
    }

    /**
     * Huỷ theo lịch hẹn. Record {@code IN_PROGRESS} hoặc đã {@code CANCELED} đều chạy cascade để
     * dọn contract còn sót; {@code COMPLETED}/{@code REJECTED} thì no-op (đã có kết luận khác).
     */
    @Transactional
    public void cancelByAppointmentId(Long appointmentId, String reason) {
        HandoverRecord record = handoverRecords.findByAppointmentIdForUpdate(appointmentId).orElse(null);
        if (record == null || record.getResult() == HandoverResult.COMPLETED
                || record.getResult() == HandoverResult.REJECTED) {
            return;
        }
        cancelOpenForOrder(record.getOrderId(), reason);
    }

    /** Huỷ theo đơn — record đang mở (nếu có) và hợp đồng {@code Draft}/{@code Signed}. */
    @Transactional
    public void cancelByOrderId(Long orderId, String reason) {
        cancelOpenForOrder(orderId, reason);
    }

    /** Một luật huỷ cho mọi case: record đang mở → CANCELED, contract chưa bàn giao → Canceled. */
    private void cancelOpenForOrder(Long orderId, String reason) {
        handoverRecords.findOpenByOrderIdForUpdate(orderId).ifPresent(record -> {
            String previous = record.getResult().name();
            record.cancel(reason);
            Map<String, Object> newValue = new HashMap<>();
            newValue.put("result", record.getResult().name());
            newValue.put("rejectReason", reason);
            auditRecorder.record(new AuditEntry(AuditAction.HANDOVER_CANCELED,
                    AuditEntityType.HANDOVER_RECORD, String.valueOf(record.getId()),
                    Map.of("result", previous), newValue, reason, null));
        });
        for (RentalContract contract : contracts.findByOrderIdAndStatusInForUpdate(orderId, CANCELABLE_CONTRACTS)) {
            String previous = contract.getStatus().dbValue();
            boolean signed = contract.getStatus() == RentalContractStatus.SIGNED;
            contract.cancel();
            if (signed) {
                // Hoá đơn tháng đầu: Unpaid → Canceled; đã Paid → FM hoàn thủ công.
                orders.closeContractInvoices(orderId, contract.getId(), reason);
            }
            auditRecorder.record(new AuditEntry(AuditAction.CONTRACT_CANCELED,
                    AuditEntityType.RENTAL_CONTRACT, String.valueOf(contract.getId()),
                    Map.of("status", previous),
                    Map.of("status", contract.getStatus().dbValue()),
                    reason, null));
        }
    }

    private Instant dueAt(HandoverRecord record, Instant arrivedAt) {
        int dueDays = pricingQuery.intPolicy(DUE_DAYS_POLICY);
        if (dueDays < MIN_DUE_DAYS) {
            throw new IllegalStateException("policies." + DUE_DAYS_POLICY + " phải >= " + MIN_DUE_DAYS
                    + " ngày (24 giờ), nhận " + dueDays);
        }
        Instant dueAt = arrivedAt.plus(dueDays, ChronoUnit.DAYS);
        Instant expiresAt = orderQuery.expiresAt(record.getOrderId()).orElse(null);
        return expiresAt != null && expiresAt.isBefore(dueAt) ? expiresAt : dueAt;
    }
}
