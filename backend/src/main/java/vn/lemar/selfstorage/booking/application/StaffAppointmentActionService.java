package vn.lemar.selfstorage.booking.application;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.lemar.selfstorage.audit.application.AuditEntry;
import vn.lemar.selfstorage.audit.application.AuditRecorder;
import vn.lemar.selfstorage.audit.domain.AuditAction;
import vn.lemar.selfstorage.audit.domain.AuditEntityType;
import vn.lemar.selfstorage.booking.application.dto.StaffAppointmentResponse;
import vn.lemar.selfstorage.booking.application.exception.AppointmentConflictException;
import vn.lemar.selfstorage.booking.application.exception.AppointmentForbiddenException;
import vn.lemar.selfstorage.booking.application.exception.AppointmentNotFoundException;
import vn.lemar.selfstorage.booking.domain.Appointment;
import vn.lemar.selfstorage.booking.domain.AppointmentStatus;
import vn.lemar.selfstorage.booking.repository.AppointmentReadRepository;
import vn.lemar.selfstorage.booking.repository.AppointmentRepository;
import vn.lemar.selfstorage.identity.application.Access;
import vn.lemar.selfstorage.identity.application.CurrentAccountProvider;

/**
 * Action của FS trên lịch được phân công — {@code Appointment} dùng chung cho check-in/handover/return,
 * không thuộc riêng handover:
 * <ul>
 *   <li>{@code arrive}: {@code Pending -> Done}, ghi {@code arrived_at}, audit, phát {@code AppointmentArrived}
 *       để handover tự set {@code HandoverRecord.due_at}.</li>
 *   <li>{@code cancel} chủ động: {@code Pending -> Canceled} + lý do, chưa quá {@code end_at} (quá giờ để cron
 *       no-show); không đụng {@code RentalOrder}.</li>
 * </ul>
 */
@Service
public class StaffAppointmentActionService {

    private final AppointmentRepository appointmentRepository;
    private final AppointmentReadRepository readRepository;
    private final Access access;
    private final CurrentAccountProvider currentAccount;
    private final AuditRecorder auditRecorder;
    private final ApplicationEventPublisher events;

    StaffAppointmentActionService(AppointmentRepository appointmentRepository,
                                  AppointmentReadRepository readRepository, Access access,
                                  CurrentAccountProvider currentAccount, AuditRecorder auditRecorder,
                                  ApplicationEventPublisher events) {
        this.appointmentRepository = appointmentRepository;
        this.readRepository = readRepository;
        this.access = access;
        this.currentAccount = currentAccount;
        this.auditRecorder = auditRecorder;
        this.events = events;
    }

    /** FS xác nhận khách đã đến; lịch chỉ đổi từ {@code Pending}. */
    @Transactional
    public StaffAppointmentResponse arrive(Long appointmentId) {
        Appointment appointment = lockAssigned(appointmentId);
        requirePending(appointment);
        if (appointment.isNoShowDue(Instant.now())) {
            throw new AppointmentConflictException("Lịch đã quá khung giờ, không thể xác nhận khách đến");
        }
        appointment.arrive();
        Long actorId = currentAccount.getCurrentAccountId();
        Map<String, Object> newValue = new HashMap<>();
        newValue.put("status", AppointmentStatus.Done.name());
        newValue.put("arrivedAt", appointment.getArrivedAt());
        auditRecorder.record(new AuditEntry(AuditAction.APPOINTMENT_ARRIVED, AuditEntityType.APPOINTMENT,
                String.valueOf(appointmentId), Map.of("status", AppointmentStatus.Pending.name()), newValue,
                null, actorId));
        events.publishEvent(new AppointmentArrived(appointmentId));
        return response(appointmentId);
    }

    /** FS chủ động huỷ lịch đang chờ với lý do; quá {@code end_at} để cron no-show. */
    @Transactional
    public StaffAppointmentResponse cancel(Long appointmentId, String reason) {
        Appointment appointment = lockAssigned(appointmentId);
        requirePending(appointment);
        if (appointment.getEndAt() != null && appointment.getEndAt().isBefore(Instant.now())) {
            throw new AppointmentConflictException("Lịch đã quá giờ");
        }
        appointment.cancel(reason);
        Long actorId = currentAccount.getCurrentAccountId();
        Map<String, Object> newValue = new HashMap<>();
        newValue.put("status", AppointmentStatus.Canceled.name());
        newValue.put("cancelReason", reason);
        auditRecorder.record(new AuditEntry(AuditAction.APPOINTMENT_CANCELED, AuditEntityType.APPOINTMENT,
                String.valueOf(appointmentId), Map.of("status", AppointmentStatus.Pending.name()), newValue,
                reason, actorId));
        return response(appointmentId);
    }

    private Appointment lockAssigned(Long appointmentId) {
        Long facilityId = appointmentRepository.findFacilityId(appointmentId)
                .orElseThrow(AppointmentNotFoundException::new);
        access.can(AppointmentQueryService.PERMISSION_CHECKIN, facilityId);
        Appointment appointment = appointmentRepository.findByIdForUpdate(appointmentId)
                .orElseThrow(AppointmentNotFoundException::new);
        if (!currentAccount.getCurrentAccountId().equals(appointment.getStaffId())) {
            throw new AppointmentForbiddenException("Bạn không phải FS phụ trách lịch này");
        }
        return appointment;
    }

    private static void requirePending(Appointment appointment) {
        if (appointment.getStatus() != AppointmentStatus.Pending) {
            throw new AppointmentConflictException("Lịch hẹn không còn ở trạng thái chờ");
        }
    }

    private StaffAppointmentResponse response(Long appointmentId) {
        appointmentRepository.flush();
        return readRepository.findById(appointmentId)
                .map(row -> StaffAppointmentResponse.from(row, Instant.now()))
                .orElseThrow(AppointmentNotFoundException::new);
    }
}
