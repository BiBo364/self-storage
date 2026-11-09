package vn.lemar.selfstorage.booking.application;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
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
import vn.lemar.selfstorage.booking.domain.AppointmentType;
import vn.lemar.selfstorage.booking.repository.AppointmentRepository;
import vn.lemar.selfstorage.booking.repository.RentalOrderRepository;
import vn.lemar.selfstorage.facility.application.StorageUnitQuery;
import vn.lemar.selfstorage.facility.application.StorageUnitView;
import vn.lemar.selfstorage.notification.application.NotificationType;

/**
 * Lịch {@code RETURN} cho trả kho (tenancy gọi khi FM phân công FS). Dùng chung ba khung cố định với check-in: map
 * {@code preferred_date} vào khung trống đầu tiên của ngày đó; hết khung thì dời sang ngày gần nhất (tối đa
 * {@link #SEARCH_DAYS} ngày) và báo khách {@code RETURN_DATE_SHIFTED}. Booking vẫn là module duy nhất ghi
 * {@code appointments}.
 */
@Service
public class ReturnAppointmentService {

    static final int SEARCH_DAYS = 14;
    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    /** Cùng định dạng {@code appointmentTime} của {@code RETURN_SCHEDULED}. */
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy").withZone(ZONE);

    private final AppointmentRepository appointmentRepository;
    private final RentalOrderRepository orderRepository;
    private final StorageUnitQuery storageUnitQuery;
    private final OrderNotifier orderNotifier;
    private final AuditRecorder auditRecorder;

    ReturnAppointmentService(AppointmentRepository appointmentRepository, RentalOrderRepository orderRepository,
                             StorageUnitQuery storageUnitQuery, OrderNotifier orderNotifier,
                             AuditRecorder auditRecorder) {
        this.appointmentRepository = appointmentRepository;
        this.orderRepository = orderRepository;
        this.storageUnitQuery = storageUnitQuery;
        this.orderNotifier = orderNotifier;
        this.auditRecorder = auditRecorder;
    }

    /** Lịch trả kho vừa tạo; {@code shifted} = không còn khung ngày khách chọn, đã dời ngày. */
    public record ReturnAppointment(Long appointmentId, LocalDate date, Instant startAt, Instant endAt,
                                    boolean shifted) {
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public ReturnAppointment book(Long orderId, Long customerId, Long facilityId, Long staffId,
                                  LocalDate preferredDate, Long actorAccountId) {
        return book(orderId, customerId, facilityId, staffId, preferredDate, actorAccountId, (start, end) -> true);
    }

    /**
     * Như trên, nhưng ưu tiên khung trống đầu tiên thoả {@code slotFits} (vd FS có ca bao trọn khung). Không khung
     * trống nào thoả thì vẫn chọn khung trống đầu tiên — caller kiểm lại và báo đúng lý do (thiếu ca / nghỉ phép).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public ReturnAppointment book(Long orderId, Long customerId, Long facilityId, Long staffId,
                                  LocalDate preferredDate, Long actorAccountId,
                                  java.util.function.BiPredicate<Instant, Instant> slotFits) {
        appointmentRepository.lockFacility(facilityId);
        Instant now = Instant.now();
        LocalDate today = LocalDate.now(ZONE);
        LocalDate from = preferredDate.isBefore(today) ? today : preferredDate;
        Set<Instant> taken = new HashSet<>(appointmentRepository.findTakenSlotStarts(facilityId, from,
                from.plusDays(SEARCH_DAYS)));
        LocalDate firstFreeDate = null;
        LocalTime[] firstFree = null;
        for (LocalDate d = from; !d.isAfter(from.plusDays(SEARCH_DAYS)); d = d.plusDays(1)) {
            for (LocalTime[] w : AppointmentBookingService.WINDOWS) {
                Instant start = d.atTime(w[0]).atZone(ZONE).toInstant();
                if (!start.isAfter(now) || taken.contains(start)) {
                    continue;
                }
                if (firstFree == null) {
                    firstFreeDate = d;
                    firstFree = w;
                }
                if (slotFits.test(start, d.atTime(w[1]).atZone(ZONE).toInstant())) {
                    return save(orderId, customerId, facilityId, staffId, preferredDate, actorAccountId, d, w);
                }
            }
        }
        if (firstFree != null) {
            return save(orderId, customerId, facilityId, staffId, preferredDate, actorAccountId, firstFreeDate,
                    firstFree);
        }
        throw new BookingConflictException("Không còn khung giờ trống trong " + SEARCH_DAYS
                + " ngày tới để hẹn trả kho");
    }

    private ReturnAppointment save(Long orderId, Long customerId, Long facilityId, Long staffId,
                                   LocalDate preferredDate, Long actorAccountId, LocalDate d, LocalTime[] w) {
        Instant start = d.atTime(w[0]).atZone(ZONE).toInstant();
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
        boolean shifted = !d.equals(preferredDate);
        if (shifted) {
            notifyShifted(orderId, customerId, start);
        }
        return new ReturnAppointment(appointment.getId(), d, start, end, shifted);
    }

    /** Ngày khách chọn đã kín lịch — báo khách ngày giờ mới (in-app + email, nối đơn). */
    private void notifyShifted(Long orderId, Long customerId, Instant start) {
        String unitCode = orderRepository.findById(orderId)
                .map(order -> order.getUnitId())
                .flatMap(storageUnitQuery::find)
                .map(StorageUnitView::code)
                .orElse("");
        Map<String, Object> values = new HashMap<>();
        values.put("unitCode", unitCode);
        values.put("appointmentTime", TIME.format(start));
        orderNotifier.notifyCustomer(customerId, orderId, NotificationType.RETURN_DATE_SHIFTED, values);
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

    /** Lịch trả kho khách chưa đến ({@code Pending}) đã kết thúc trước {@code cutoff}. */
    @Transactional(readOnly = true)
    public java.util.List<Long> missedIds(Instant cutoff) {
        return appointmentRepository.findReturnMissedIds(cutoff);
    }

    /**
     * Huỷ lịch trả kho khách không đến: chỉ khi lịch còn {@code Pending} và kết thúc trước {@code cutoff}; audit
     * {@code APPOINTMENT_CANCELED_NO_SHOW}, actor = System. {@code true} khi thực sự huỷ.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean cancelMissed(Long appointmentId, Instant cutoff, String reason) {
        return appointmentRepository.findByIdForUpdate(appointmentId)
                .filter(a -> a.getType() == AppointmentType.RETURN && a.getStatus() == AppointmentStatus.Pending
                        && a.getEndAt().isBefore(cutoff))
                .map(a -> {
                    a.cancel(reason);
                    Map<String, Object> newValue = new HashMap<>();
                    newValue.put("status", AppointmentStatus.Canceled.name());
                    newValue.put("cancelReason", reason);
                    auditRecorder.record(new AuditEntry(AuditAction.APPOINTMENT_CANCELED_NO_SHOW,
                            AuditEntityType.APPOINTMENT, String.valueOf(appointmentId),
                            Map.of("status", AppointmentStatus.Pending.name()), newValue, reason, null));
                    return true;
                })
                .orElse(false);
    }

    /** Trạng thái lịch (Pending/Done/Canceled), mốc khách đến, khung giờ. */
    public record AppointmentState(String status, Instant arrivedAt, Long staffId, Instant startAt, Instant endAt) {
    }

    @Transactional(readOnly = true)
    public java.util.Optional<AppointmentState> state(Long appointmentId) {
        return appointmentRepository.findById(appointmentId)
                .map(a -> new AppointmentState(a.getStatus().name(), a.getArrivedAt(), a.getStaffId(),
                        a.getStartedAt(), a.getEndAt()));
    }
}
