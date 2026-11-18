package vn.lemar.selfstorage.booking.application;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.lemar.selfstorage.audit.application.AuditEntry;
import vn.lemar.selfstorage.audit.application.AuditRecorder;
import vn.lemar.selfstorage.audit.domain.AuditAction;
import vn.lemar.selfstorage.audit.domain.AuditEntityType;
import vn.lemar.selfstorage.booking.application.AppointmentBookingService.Day;
import vn.lemar.selfstorage.booking.application.AppointmentBookingService.Slot;
import vn.lemar.selfstorage.booking.application.AppointmentBookingService.SlotWindow;
import vn.lemar.selfstorage.booking.application.exception.AppointmentConflictException;
import vn.lemar.selfstorage.booking.application.exception.AppointmentForbiddenException;
import vn.lemar.selfstorage.booking.application.exception.AppointmentNotFoundException;
import vn.lemar.selfstorage.booking.application.exception.AppointmentValidationException;
import vn.lemar.selfstorage.booking.domain.Appointment;
import vn.lemar.selfstorage.booking.domain.AppointmentStatus;
import vn.lemar.selfstorage.booking.domain.AppointmentType;
import vn.lemar.selfstorage.booking.domain.RentalOrder;
import vn.lemar.selfstorage.booking.domain.RentalOrderStatus;
import vn.lemar.selfstorage.booking.repository.AppointmentReadRepository;
import vn.lemar.selfstorage.booking.repository.AppointmentReadRepository.AppointmentRow;
import vn.lemar.selfstorage.booking.repository.AppointmentRepository;
import vn.lemar.selfstorage.booking.repository.RentalOrderRepository;
import vn.lemar.selfstorage.identity.application.Access;
import vn.lemar.selfstorage.identity.application.CurrentAccountProvider;
import vn.lemar.selfstorage.notification.application.NotificationType;
import vn.lemar.selfstorage.staffing.application.StaffAvailability;

/**
 * FS dời lịch nhận kho (check-in) sang khung khác — trước khi khách đến, hoặc khi khách đã đến nhưng phiên bàn giao
 * chưa xong. Phần lịch hẹn của thao tác: kiểm quyền + đúng FS, kiểm khung mới (cùng luật "đặt lại": từ hôm nay tới ngày
 * hết hạn giữ đơn, khung chưa qua, kết thúc trước {@code expires_at}, cơ sở chưa có lịch ở khung đó), FS vẫn phụ trách
 * được khung mới (nghỉ phép / ca — cùng luật FM phân công); huỷ lịch cũ nếu khách chưa đến, tạo lịch mới gán sẵn FS,
 * phát {@code CheckinAppointmentCreated} để handover mở biên bản mới, audit + báo khách / FM.
 *
 * <p>Đóng biên bản / hợp đồng cũ thuộc module handover: handover gọi {@link #reschedule} trong transaction của mình
 * (đã khoá đơn) rồi tự đóng phiên cũ — booking không gọi sang handover.
 */
@Service
public class CheckinRescheduleService {

    static final String REASON_PREFIX = "Dời lịch: ";
    static final String MSG_SAME_SLOT = "Khung mới trùng khung hiện tại, vui lòng chọn khung khác";
    static final String MSG_NOT_IN_PROGRESS = "Đơn thuê không ở trạng thái đang bàn giao";

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter HOUR =
            DateTimeFormatter.ofPattern("HH:mm").withZone(AppointmentBookingService.ZONE);

    private final AppointmentRepository appointmentRepository;
    private final AppointmentReadRepository readRepository;
    private final RentalOrderRepository orderRepository;
    private final Access access;
    private final CurrentAccountProvider currentAccount;
    private final StaffAvailability staffAvailability;
    private final AuditRecorder auditRecorder;
    private final OrderNotifier orderNotifier;
    private final ApplicationEventPublisher events;

    CheckinRescheduleService(AppointmentRepository appointmentRepository, AppointmentReadRepository readRepository,
                             RentalOrderRepository orderRepository, Access access,
                             CurrentAccountProvider currentAccount, StaffAvailability staffAvailability,
                             AuditRecorder auditRecorder, OrderNotifier orderNotifier,
                             ApplicationEventPublisher events) {
        this.appointmentRepository = appointmentRepository;
        this.readRepository = readRepository;
        this.orderRepository = orderRepository;
        this.access = access;
        this.currentAccount = currentAccount;
        this.staffAvailability = staffAvailability;
        this.auditRecorder = auditRecorder;
        this.orderNotifier = orderNotifier;
        this.events = events;
    }

    /** Lịch cần dời: đã kiểm quyền {@code appointment.checkin} theo cơ sở và đúng FS phụ trách. */
    public record Target(Long appointmentId, Long orderId, Long facilityId, Long staffId) {
    }

    /** Kết quả dời lịch — {@code arrived}: khách đã đến buổi cũ (lịch cũ giữ {@code Done}). */
    public record Rescheduled(Long oldAppointmentId, Long newAppointmentId, Long orderId, boolean arrived,
                              LocalDate date, Instant startAt, Instant endAt, String oldTime, String newTime) {
    }

    @Transactional(readOnly = true)
    public Target target(Long appointmentId) {
        Long facilityId = appointmentRepository.findFacilityId(appointmentId)
                .orElseThrow(AppointmentNotFoundException::new);
        access.can(AppointmentQueryService.PERMISSION_CHECKIN, facilityId);
        AppointmentRow row = readRepository.findById(appointmentId).orElseThrow(AppointmentNotFoundException::new);
        if (!currentAccount.getCurrentAccountId().equals(row.staffId())) {
            throw new AppointmentForbiddenException("Bạn không phải FS phụ trách lịch này");
        }
        if (!AppointmentType.CHECKIN.name().equals(row.type())) {
            throw new AppointmentConflictException("Chỉ dời được lịch nhận kho (check-in)");
        }
        if (row.orderId() == null) {
            throw new AppointmentConflictException("Lịch hẹn không gắn với đơn thuê");
        }
        return new Target(appointmentId, row.orderId(), facilityId, row.staffId());
    }

    /**
     * Khung để dời: từ hôm nay tới ngày hết hạn giữ đơn; khung đã qua, kết thúc sau hạn giữ hoặc cơ sở đã có lịch thì
     * {@code available = false} (khung hiện tại của lịch cũng tính là đã có lịch).
     */
    @Transactional(readOnly = true)
    public SlotWindow slots(Long appointmentId) {
        Target target = target(appointmentId);
        RentalOrder order = orderRepository.findById(target.orderId())
                .orElseThrow(AppointmentNotFoundException::new);
        LocalDate min = LocalDate.now(AppointmentBookingService.ZONE);
        LocalDate max = maxDate(order, min);
        Set<Instant> taken = new HashSet<>(appointmentRepository.findTakenSlotStarts(target.facilityId(), min, max));
        Instant now = Instant.now();
        List<Day> days = new ArrayList<>();
        for (LocalDate d = min; !d.isAfter(max); d = d.plusDays(1)) {
            List<Slot> slots = new ArrayList<>();
            for (LocalTime[] w : AppointmentBookingService.WINDOWS) {
                Instant start = d.atTime(w[0]).atZone(AppointmentBookingService.ZONE).toInstant();
                Instant end = d.atTime(w[1]).atZone(AppointmentBookingService.ZONE).toInstant();
                boolean available = start.isAfter(now) && fits(order, end) && !taken.contains(start);
                slots.add(new Slot(start, end, available));
            }
            days.add(new Day(d, slots));
        }
        return new SlotWindow(order.getId(), target.facilityId(), min, max, days);
    }

    /**
     * Dời lịch. Chạy trong transaction của caller (handover đã khoá đơn và kiểm hoá đơn tháng đầu). Thứ tự khoá: đơn →
     * lịch → cơ sở (capacity) → FS (ca / nghỉ phép).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Rescheduled reschedule(Long appointmentId, LocalDate date, Instant startAt, String reason, Long actorId) {
        if (date == null || startAt == null) {
            throw new AppointmentValidationException("Thiếu ngày hoặc khung giờ hẹn mới");
        }
        Long orderId = appointmentRepository.findOrderId(appointmentId)
                .orElseThrow(() -> new AppointmentConflictException("Lịch hẹn không gắn với đơn thuê"));
        RentalOrder order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(AppointmentNotFoundException::new);
        if (order.getStatus() != RentalOrderStatus.IN_PROGRESS) {
            throw new AppointmentConflictException(MSG_NOT_IN_PROGRESS);
        }
        Appointment old = appointmentRepository.findByIdForUpdate(appointmentId)
                .orElseThrow(AppointmentNotFoundException::new);
        if (!actorId.equals(old.getStaffId())) {
            throw new AppointmentForbiddenException("Bạn không phải FS phụ trách lịch này");
        }
        Instant now = Instant.now();
        boolean arrived = old.getStatus() == AppointmentStatus.Done;
        if (old.getStatus() == AppointmentStatus.Pending) {
            if (old.isNoShowDue(now)) {
                throw new AppointmentConflictException("Lịch đã quá giờ — hệ thống sẽ huỷ do khách không đến");
            }
        } else if (!arrived) {
            throw new AppointmentConflictException("Lịch hẹn không còn hiệu lực");
        } else if (appointmentRepository.existsPendingByOrderId(orderId)) {
            throw new AppointmentConflictException("Đơn thuê đã có lịch hẹn check-in khác");
        }

        LocalDate min = LocalDate.now(AppointmentBookingService.ZONE);
        if (date.isBefore(min) || date.isAfter(maxDate(order, min))) {
            throw new AppointmentValidationException(AppointmentBookingService.MSG_DATE_OUT_OF_WINDOW);
        }
        LocalTime[] window = AppointmentBookingService.WINDOWS.stream()
                .filter(w -> date.atTime(w[0]).atZone(AppointmentBookingService.ZONE).toInstant().equals(startAt))
                .findFirst()
                .orElseThrow(() -> new AppointmentValidationException(AppointmentBookingService.MSG_INVALID_SLOT));
        Instant endAt = date.atTime(window[1]).atZone(AppointmentBookingService.ZONE).toInstant();
        if (!startAt.isAfter(now)) {
            throw new AppointmentValidationException(AppointmentBookingService.MSG_SLOT_PASSED);
        }
        if (startAt.equals(old.getStartedAt())) {
            throw new AppointmentValidationException(MSG_SAME_SLOT);
        }
        if (!fits(order, endAt)) {
            throw new AppointmentConflictException(AppointmentBookingService.MSG_NO_SLOT_LEFT);
        }
        Long facilityId = old.getFacilityId();
        appointmentRepository.lockFacility(facilityId);
        if (appointmentRepository.findTakenSlotStarts(facilityId, date, date).contains(startAt)) {
            throw new AppointmentConflictException(AppointmentBookingService.MSG_SLOT_TAKEN);
        }
        staffAvailability.appointmentViolation(actorId, facilityId, startAt, endAt).ifPresent(message -> {
            throw new AppointmentConflictException(message + " — chọn khung khác hoặc nhờ FM phân công FS khác");
        });

        String why = REASON_PREFIX + reason;
        String oldTime = time(old.getDate(), old.getStartedAt(), old.getEndAt());
        if (!arrived) {
            old.cancel(why);
        }
        Appointment fresh = appointmentRepository.save(
                Appointment.checkin(order.getCustomerId(), facilityId, date, startAt, endAt));
        fresh.assignStaff(actorId);
        appointmentRepository.linkToOrder(fresh.getId(), orderId);

        Map<String, Object> oldValue = new HashMap<>();
        oldValue.put("status", arrived ? AppointmentStatus.Done.name() : AppointmentStatus.Pending.name());
        oldValue.put("startedAt", old.getStartedAt().toString());
        Map<String, Object> newValue = new HashMap<>();
        newValue.put("status", old.getStatus().name());
        newValue.put("newAppointmentId", fresh.getId());
        newValue.put("newStartedAt", startAt.toString());
        auditRecorder.record(new AuditEntry(AuditAction.APPOINTMENT_RESCHEDULED, AuditEntityType.APPOINTMENT,
                String.valueOf(appointmentId), oldValue, newValue, why, actorId));
        Map<String, Object> created = new HashMap<>();
        created.put("type", AppointmentType.CHECKIN.name());
        created.put("status", AppointmentStatus.Pending.name());
        created.put("orderId", orderId);
        created.put("staffId", actorId);
        created.put("startedAt", startAt.toString());
        created.put("rescheduledFrom", appointmentId);
        auditRecorder.record(new AuditEntry(AuditAction.APPOINTMENT_CREATED, AuditEntityType.APPOINTMENT,
                String.valueOf(fresh.getId()), null, created, why, actorId));

        // Handover mở biên bản mới cho lịch này (BEFORE_COMMIT) — caller phải đóng biên bản cũ trước khi commit.
        events.publishEvent(new CheckinAppointmentCreated(fresh.getId(), orderId, order.getUnitId()));

        appointmentRepository.flush();
        AppointmentRow row = readRepository.findById(fresh.getId()).orElseThrow(AppointmentNotFoundException::new);
        String newTime = time(date, startAt, endAt);
        Map<String, String> values = new HashMap<>();
        values.put("orderCode", OrderNotifier.orderCode(order));
        values.put("oldAppointmentTime", oldTime);
        values.put("appointmentTime", newTime);
        values.put("facilityAddress", row.facilityAddress() == null ? row.facilityName() : row.facilityAddress());
        values.put("reason", reason);
        orderNotifier.notifyCustomer(order, NotificationType.APPOINTMENT_RESCHEDULED, values);
        orderNotifier.notifyFacilityManager(orderId, NotificationType.APPOINTMENT_RESCHEDULED, values);
        return new Rescheduled(appointmentId, fresh.getId(), orderId, arrived, date, startAt, endAt, oldTime, newTime);
    }

    /** Ngày cuối được dời tới = ngày hết hạn giữ đơn (đơn chưa có hạn giữ → chỉ trong ngày). */
    private static LocalDate maxDate(RentalOrder order, LocalDate min) {
        if (order.getExpiresAt() == null) {
            return min;
        }
        LocalDate expiry = order.getExpiresAt().atZone(AppointmentBookingService.ZONE).toLocalDate();
        return expiry.isBefore(min) ? min : expiry;
    }

    private static boolean fits(RentalOrder order, Instant endAt) {
        return order.getExpiresAt() == null || !endAt.isAfter(order.getExpiresAt());
    }

    private static String time(LocalDate date, Instant start, Instant end) {
        return HOUR.format(start) + "–" + HOUR.format(end) + " " + DAY.format(date);
    }
}
