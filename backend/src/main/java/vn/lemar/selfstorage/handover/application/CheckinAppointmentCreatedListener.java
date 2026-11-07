package vn.lemar.selfstorage.handover.application;

import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import vn.lemar.selfstorage.audit.application.AuditEntry;
import vn.lemar.selfstorage.audit.application.AuditRecorder;
import vn.lemar.selfstorage.audit.domain.AuditAction;
import vn.lemar.selfstorage.audit.domain.AuditEntityType;
import vn.lemar.selfstorage.booking.application.CheckinAppointmentCreated;
import vn.lemar.selfstorage.handover.domain.HandoverRecord;
import vn.lemar.selfstorage.handover.repository.HandoverRecordRepository;

/**
 * Spec 1.5: lịch check-in vừa tạo → mở {@code HandoverRecord} cho đơn. Chạy {@code BEFORE_COMMIT} trong transaction
 * đặt lịch: insert lỗi (vd đơn đã có record đang mở — partial unique index) rollback cả lịch hẹn.
 *
 * <p>Idempotent theo {@code appointment_id}: record của lịch đã có thì bỏ qua.
 */
@Component
class CheckinAppointmentCreatedListener {

    private final HandoverRecordRepository repository;
    private final AuditRecorder auditRecorder;

    CheckinAppointmentCreatedListener(HandoverRecordRepository repository, AuditRecorder auditRecorder) {
        this.repository = repository;
        this.auditRecorder = auditRecorder;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void on(CheckinAppointmentCreated event) {
        if (repository.findByAppointmentId(event.appointmentId()).isPresent()) {
            return;
        }
        HandoverRecord record = repository.saveAndFlush(
                HandoverRecord.open(event.orderId(), event.appointmentId(), event.unitId()));
        auditRecorder.record(new AuditEntry(AuditAction.HANDOVER_RECORD_CREATED, AuditEntityType.HANDOVER_RECORD,
                String.valueOf(record.getId()), null,
                Map.of("result", record.getResult().name(), "orderId", event.orderId(),
                        "appointmentId", event.appointmentId()), null, null));
    }
}
