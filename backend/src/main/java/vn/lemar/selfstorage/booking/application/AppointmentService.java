package vn.lemar.selfstorage.booking.application;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.lemar.selfstorage.audit.application.AuditEntry;
import vn.lemar.selfstorage.audit.application.AuditRecorder;
import vn.lemar.selfstorage.audit.domain.AuditAction;
import vn.lemar.selfstorage.audit.domain.AuditEntityType;
import vn.lemar.selfstorage.booking.application.dto.FmAppointmentResponse;
import vn.lemar.selfstorage.booking.application.exception.AppointmentConflictException;
import vn.lemar.selfstorage.booking.application.exception.AppointmentNotFoundException;
import vn.lemar.selfstorage.booking.application.exception.AppointmentValidationException;
import vn.lemar.selfstorage.booking.domain.Appointment;
import vn.lemar.selfstorage.booking.domain.AppointmentStatus;
import vn.lemar.selfstorage.booking.domain.AppointmentType;
import vn.lemar.selfstorage.booking.domain.RentalOrder;
import vn.lemar.selfstorage.booking.repository.AppointmentReadRepository;
import vn.lemar.selfstorage.booking.repository.AppointmentReadRepository.AppointmentRow;
import vn.lemar.selfstorage.booking.repository.AppointmentRepository;
import vn.lemar.selfstorage.booking.repository.RentalOrderRepository;
import vn.lemar.selfstorage.identity.application.Access;
import vn.lemar.selfstorage.identity.application.AccountQueryService;
import vn.lemar.selfstorage.identity.application.FacilityStaffQuery;
import vn.lemar.selfstorage.notification.application.NotificationRecipient;
import vn.lemar.selfstorage.notification.application.NotificationService;
import vn.lemar.selfstorage.notification.application.NotificationType;

/**
 * Spec 1.5 phía FM: phân công FS cho lịch check-in. Gán {@code Appointment.staff_id}, đơn {@code Scheduled ->
 * InProgress}, AuditLog {@code FS_ASSIGNED}, FS nhận {@code FS_ASSIGNED}.
 *
 * <p>Đổi FS đã gán không cho ở MVP (chốt trong behavior-gap): lịch đã có FS thì 409. Chưa làm: phát
 * {@code FS_ASSIGNMENT_REQUIRED} lúc khách tạo lịch (thuộc luồng khách đặt lịch).
 */
@Service
public class AppointmentService {

    private static final ZoneId FACILITY_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm").withZone(FACILITY_ZONE);

    private final AppointmentRepository appointmentRepository;
    private final AppointmentReadRepository readRepository;
    private final RentalOrderRepository orderRepository;
    private final FacilityStaffQuery facilityStaffQuery;
    private final AccountQueryService accountQueryService;
    private final Access access;
    private final AuditRecorder auditRecorder;
    private final NotificationService notificationService;

    AppointmentService(AppointmentRepository appointmentRepository, AppointmentReadRepository readRepository,
                       RentalOrderRepository orderRepository, FacilityStaffQuery facilityStaffQuery,
                       AccountQueryService accountQueryService, Access access, AuditRecorder auditRecorder,
                       NotificationService notificationService) {
        this.appointmentRepository = appointmentRepository;
        this.readRepository = readRepository;
        this.orderRepository = orderRepository;
        this.facilityStaffQuery = facilityStaffQuery;
        this.accountQueryService = accountQueryService;
        this.access = access;
        this.auditRecorder = auditRecorder;
        this.notificationService = notificationService;
    }

    /**
     * FM phân công FS cho lịch check-in. Quyền {@code appointment.assign} theo cơ sở của lịch; FS phải là tài khoản
     * FS đang hoạt động được gán vào cùng cơ sở.
     */
    @Transactional
    public FmAppointmentResponse assignStaff(Long appointmentId, Long staffAccountId, Long actorAccountId) {
        Long facilityId = appointmentRepository.findFacilityId(appointmentId)
                .orElseThrow(AppointmentNotFoundException::new);
        access.can(AppointmentQueryService.PERMISSION_ASSIGN, facilityId);

        Appointment appointment = appointmentRepository.findByIdForUpdate(appointmentId)
                .orElseThrow(AppointmentNotFoundException::new);
        if (appointment.getType() != AppointmentType.CHECKIN) {
            throw new AppointmentConflictException("Chỉ phân công FS cho lịch check-in");
        }
        if (appointment.getStatus() != AppointmentStatus.Pending) {
            throw new AppointmentConflictException("Lịch hẹn không còn ở trạng thái chờ");
        }
        if (appointment.hasStaff()) {
            throw new AppointmentConflictException("Lịch hẹn đã có FS phụ trách");
        }
        if (!facilityStaffQuery.isActiveStaffOf(staffAccountId, facilityId)) {
            throw new AppointmentValidationException("Nhân viên không phải FS của cơ sở này");
        }
        Long orderId = appointmentRepository.findOrderId(appointmentId)
                .orElseThrow(() -> new AppointmentConflictException("Lịch hẹn không gắn với đơn thuê"));
        RentalOrder order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new AppointmentConflictException("Lịch hẹn không gắn với đơn thuê"));
        if (!order.isScheduled()) {
            throw new AppointmentConflictException("Đơn thuê không ở trạng thái chờ phân công");
        }

        String previousOrderStatus = order.getStatus().dbValue();
        appointment.assignStaff(staffAccountId);
        order.startProgress();
        Map<String, Object> newValue = new HashMap<>();
        newValue.put("staffId", staffAccountId);
        newValue.put("orderId", orderId);
        newValue.put("orderStatus", order.getStatus().dbValue());
        auditRecorder.record(new AuditEntry(AuditAction.FS_ASSIGNED, AuditEntityType.APPOINTMENT,
                String.valueOf(appointmentId), Map.of("orderStatus", previousOrderStatus), newValue, null,
                actorAccountId));

        appointmentRepository.flush();
        AppointmentRow row = readRepository.findById(appointmentId).orElseThrow(AppointmentNotFoundException::new);
        notificationService.notify(staffAccountId, accountQueryService.requireEmailById(staffAccountId),
                NotificationRecipient.FS, NotificationType.FS_ASSIGNED, notificationValues(row), orderId);
        return FmAppointmentResponse.from(row);
    }

    private static Map<String, String> notificationValues(AppointmentRow row) {
        Map<String, String> values = new HashMap<>();
        values.put("orderCode", String.valueOf(row.orderId()));
        values.put("appointmentTime", appointmentTime(row));
        values.put("facilityAddress", row.facilityAddress() == null ? row.facilityName() : row.facilityAddress());
        values.put("unitCode", row.unitCode() == null ? "" : row.unitCode());
        return values;
    }

    private static String appointmentTime(AppointmentRow row) {
        String day = DATE.format(row.date());
        if (row.startedAt() == null) {
            return day;
        }
        String start = TIME.format(row.startedAt());
        return row.endAt() == null ? start + " " + day : start + "–" + TIME.format(row.endAt()) + " " + day;
    }
}
