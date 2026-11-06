package vn.lemar.selfstorage.handover.application;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.lemar.selfstorage.audit.application.AuditEntry;
import vn.lemar.selfstorage.audit.application.AuditRecorder;
import vn.lemar.selfstorage.audit.domain.AuditAction;
import vn.lemar.selfstorage.audit.domain.AuditEntityType;
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
 * <p>Chưa có HTTP/cron gọi hai hàm huỷ — cron quá hạn và no-show thuộc issue riêng.
 */
@Service
public class HandoverService {

    static final String DUE_DAYS_POLICY = "handover.due_days";

    private static final Logger LOG = LoggerFactory.getLogger(HandoverService.class);
    private static final List<RentalContractStatus> CANCELABLE_CONTRACTS =
            List.of(RentalContractStatus.DRAFT, RentalContractStatus.SIGNED);

    private final HandoverRecordRepository handoverRecords;
    private final RentalContractRepository contracts;
    private final RentalOrderQuery orderQuery;
    private final PricingQuery pricingQuery;
    private final AuditRecorder auditRecorder;

    HandoverService(HandoverRecordRepository handoverRecords, RentalContractRepository contracts,
                    RentalOrderQuery orderQuery, PricingQuery pricingQuery, AuditRecorder auditRecorder) {
        this.handoverRecords = handoverRecords;
        this.contracts = contracts;
        this.orderQuery = orderQuery;
        this.pricingQuery = pricingQuery;
        this.auditRecorder = auditRecorder;
    }

    /**
     * Flow 2.1: {@code AppointmentArrived} → set {@code due_at = min(now + handover.due_days,
     * RentalOrder.expires_at)}. Idempotent: record không tồn tại (Flow 1 chưa tạo), đã đóng hoặc đã
     * có {@code due_at} thì bỏ qua. Chạy trong transaction của caller.
     */
    @Transactional
    public void onAppointmentArrived(Long appointmentId) {
        HandoverRecord record = handoverRecords.findByAppointmentIdForUpdate(appointmentId).orElse(null);
        if (record == null) {
            LOG.debug("Không có HandoverRecord cho lịch hẹn {} — bỏ qua due_at", appointmentId);
            return;
        }
        if (record.getResult() != HandoverResult.IN_PROGRESS || record.getDueAt() != null) {
            return;
        }
        record.setDueAt(dueAt(record));
    }

    /**
     * Huỷ theo lịch hẹn — chỉ khi record của lịch đang {@code IN_PROGRESS}; record đã đóng thì no-op.
     */
    @Transactional
    public void cancelByAppointmentId(Long appointmentId, String reason) {
        HandoverRecord record = handoverRecords.findByAppointmentIdForUpdate(appointmentId).orElse(null);
        if (record == null || record.getResult() != HandoverResult.IN_PROGRESS) {
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
            contract.cancel();
            auditRecorder.record(new AuditEntry(AuditAction.CONTRACT_CANCELED,
                    AuditEntityType.RENTAL_CONTRACT, String.valueOf(contract.getId()),
                    Map.of("status", previous),
                    Map.of("status", contract.getStatus().dbValue()),
                    reason, null));
        }
    }

    private Instant dueAt(HandoverRecord record) {
        Instant dueAt = Instant.now().plus(pricingQuery.intPolicy(DUE_DAYS_POLICY), ChronoUnit.DAYS);
        Instant expiresAt = orderQuery.expiresAt(record.getOrderId()).orElse(null);
        return expiresAt != null && expiresAt.isBefore(dueAt) ? expiresAt : dueAt;
    }
}
