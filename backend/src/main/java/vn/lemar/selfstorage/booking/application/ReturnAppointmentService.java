package vn.lemar.selfstorage.booking.application;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.lemar.selfstorage.audit.application.AuditEntry;
import vn.lemar.selfstorage.audit.application.AuditRecorder;
import vn.lemar.selfstorage.audit.domain.AuditAction;
import vn.lemar.selfstorage.audit.domain.AuditEntityType;
import vn.lemar.selfstorage.booking.application.exception.BookingConflictException;
import vn.lemar.selfstorage.booking.domain.Appointment;
import vn.lemar.selfstorage.booking.domain.AppointmentStatus;
import vn.lemar.selfstorage.booking.repository.AppointmentRepository;

/**
 * Lịch {@code RETURN} cho trả kho (tenancy gọi khi FM phân công FS). Dùng chung ba khung cố định với check-in: map
 * {@code preferred_date} vào khung trống đầu tiên của ngày đó; hết khung thì dời sang ngày gần nhất (tối đa
 * {@link #SEARCH_DAYS} ngày). Booking vẫn là module duy nhất ghi {@code appointments}.
 */
@Service
public class ReturnAppointmentService {

    static final int SEARCH_DAYS = 14;
    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final AppointmentRepository appointmentRepository;
    private final AuditRecorder auditRecorder;

    ReturnAppointmentService(AppointmentRepository appointmentRepository, AuditRecorder auditRecorder) {
        this.appointmentRepository = appointmentRepository;
        this.auditRecorder = auditRecorder;
    }

    /** Lịch trả kho vừa tạo; {@code shifted} = không còn khung ngày khách chọn, đã dời ngày. */
    public record ReturnAppointment(Long appointmentId, LocalDate date, Instant startAt, Instant endAt,
                                    boolean shifted) {
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public ReturnAppointment book(Long orderId, Long customerId, Long facilityId, Long staffId,
                                  LocalDate preferredDate, Long actorAccountId) {
        appointmentRepository.lockFacility(facilityId);
        Instant now = Instant.now();
        LocalDate today = LocalDate.now(ZONE);
        LocalDate from = preferredDate.isBefore(today) ? today : preferredDate;
        Set<Instant> taken = new HashSet<>(appointmentRepository.findTakenSlotStarts(facilityId, from,
                from.plusDays(SEARCH_DAYS)));
        for (LocalDate d = from; !d.isAfter(from.plusDays(SEARCH_DAYS)); d = d.plusDays(1)) {
            for (LocalTime[] w : AppointmentBookingService.WINDOWS) {
                Instant start = d.atTime(w[0]).atZone(ZONE).toInstant();
                if (!start.isAfter(now) || taken.contains(start)) {
                    continue;
                }
                Instant end = d.atTime(w[1]).atZone(ZONE).toInstant();
                Appointment appointment = appointmentRepository.save(
                        Appointment.returnVisit(customerId, facilityId, staffId, d, start, end));
                appointmentRepository.linkToOrder(appointment.getId(), orderId);
                Map<String, Object> value = new HashMap<>();
                value.put("type", "RETURN");
                value.put("status", "Pending");
                value.put("orderId", orderId);
                value.put("staffId", staffId);
                value.put("startedAt", start.toString());
                auditRecorder.record(new AuditEntry(AuditAction.APPOINTMENT_CREATED, AuditEntityType.APPOINTMENT,
                        String.valueOf(appointment.getId()), null, value, null, actorAccountId));
                return new ReturnAppointment(appointment.getId(), d, start, end, !d.equals(preferredDate));
            }
        }
        throw new BookingConflictException("Không còn khung giờ trống trong " + SEARCH_DAYS
                + " ngày tới để hẹn trả kho");
    }

    /** Huỷ lịch trả kho còn {@code Pending} (khách huỷ yêu cầu / FM phân công lại). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void cancel(Long appointmentId, String reason, Long actorAccountId) {
        appointmentRepository.findByIdForUpdate(appointmentId)
                .filter(a -> a.getStatus() == AppointmentStatus.Pending)
                .ifPresent(a -> {
                    a.cancel(reason);
                    auditRecorder.record(new AuditEntry(AuditAction.APPOINTMENT_CANCELED,
                            AuditEntityType.APPOINTMENT, String.valueOf(appointmentId),
                            Map.of("status", AppointmentStatus.Pending.name()),
                            Map.of("status", AppointmentStatus.Canceled.name()), reason, actorAccountId));
                });
    }

    /** Trạng thái lịch (Pending/Done/Canceled) và mốc khách đến. */
    public record AppointmentState(String status, Instant arrivedAt, Long staffId, Instant startAt) {
    }

    @Transactional(readOnly = true)
    public java.util.Optional<AppointmentState> state(Long appointmentId) {
        return appointmentRepository.findById(appointmentId)
                .map(a -> new AppointmentState(a.getStatus().name(), a.getArrivedAt(), a.getStaffId(),
                        a.getStartedAt()));
    }
}
