package vn.lemar.selfstorage.booking.application;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.lemar.selfstorage.audit.application.AuditEntry;
import vn.lemar.selfstorage.audit.application.AuditRecorder;
import vn.lemar.selfstorage.audit.domain.AuditAction;
import vn.lemar.selfstorage.audit.domain.AuditEntityType;
import vn.lemar.selfstorage.booking.domain.Appointment;
import vn.lemar.selfstorage.booking.domain.AppointmentStatus;
import vn.lemar.selfstorage.booking.repository.AppointmentReadRepository;
import vn.lemar.selfstorage.booking.repository.AppointmentReadRepository.AppointmentRow;
import vn.lemar.selfstorage.booking.repository.AppointmentRepository;
import vn.lemar.selfstorage.notification.application.NotificationType;

/**
 * Cron no-show: lịch check-in Pending quá {@code end_at} → Canceled (Quá giờ và khách không đến), audit, báo khách.
 * Chỉ xử lý {@code Appointment}; HandoverRecord/RentalOrder để issue riêng. Idempotent: chỉ Pending;
 * chạy lại không ghi thêm.
 */
@Service
public class AppointmentNoShowService {

    static final String REASON = "Quá giờ và khách không đến";

    private static final ZoneId FACILITY_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm").withZone(FACILITY_ZONE);

    private final AppointmentRepository appointmentRepository;
    private final AppointmentReadRepository readRepository;
    private final OrderNotifier orderNotifier;
    private final AuditRecorder auditRecorder;
    private final String frontendUrl;

    AppointmentNoShowService(AppointmentRepository appointmentRepository,
                             AppointmentReadRepository readRepository, OrderNotifier orderNotifier,
                             AuditRecorder auditRecorder,
                             @Value("${app.frontend-url}") String frontendUrl) {
        this.appointmentRepository = appointmentRepository;
        this.readRepository = readRepository;
        this.orderNotifier = orderNotifier;
        this.auditRecorder = auditRecorder;
        this.frontendUrl = frontendUrl;
    }

    @Transactional(readOnly = true)
    public List<Long> findDueIds() {
        return appointmentRepository.findNoShowDueIds(Instant.now());
    }

    /** Huỷ một lịch nếu đang due; trả {@code true} khi thực sự huỷ. */
    @Transactional
    public boolean cancelIfDue(Long appointmentId) {
        Appointment appointment = appointmentRepository.findByIdForUpdate(appointmentId).orElse(null);
        Instant now = Instant.now();
        if (appointment == null || !appointment.isNoShowDue(now)) {
            return false;
        }
        appointment.cancel(REASON);
        Map<String, Object> newValue = new HashMap<>();
        newValue.put("status", AppointmentStatus.Canceled.name());
        newValue.put("cancelReason", REASON);
        auditRecorder.record(new AuditEntry(AuditAction.APPOINTMENT_CANCELED_NO_SHOW,
                AuditEntityType.APPOINTMENT, String.valueOf(appointmentId),
                Map.of("status", AppointmentStatus.Pending.name()), newValue, REASON, null));

        appointmentRepository.flush();
        AppointmentRow row = readRepository.findById(appointmentId).orElse(null);
        if (row != null) {
            orderNotifier.notifyCustomer(appointment.getCustomerId(), row.orderId(),
                    NotificationType.APPOINTMENT_CANCELED_NO_SHOW,
                    Map.of("appointmentTime", appointmentTime(row),
                            "rescheduleLink", frontendUrl + "/appointments/new"));
        }
        return true;
    }

    private static String appointmentTime(AppointmentRow row) {
        return TIME.format(row.startedAt()) + "–" + TIME.format(row.endAt()) + " " + DATE.format(row.date());
    }
}
