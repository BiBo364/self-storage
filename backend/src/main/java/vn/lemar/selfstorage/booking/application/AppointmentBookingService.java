package vn.lemar.selfstorage.booking.application;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.lemar.selfstorage.audit.application.AuditEntry;
import vn.lemar.selfstorage.audit.application.AuditRecorder;
import vn.lemar.selfstorage.audit.domain.AuditAction;
import vn.lemar.selfstorage.audit.domain.AuditEntityType;
import vn.lemar.selfstorage.booking.application.exception.BookingConflictException;
import vn.lemar.selfstorage.booking.application.exception.BookingNotFoundException;
import vn.lemar.selfstorage.booking.application.exception.BookingValidationException;
import vn.lemar.selfstorage.booking.domain.Appointment;
import vn.lemar.selfstorage.booking.domain.InvoiceStatus;
import vn.lemar.selfstorage.booking.domain.InvoiceType;
import vn.lemar.selfstorage.booking.domain.ProposalStatus;
import vn.lemar.selfstorage.booking.domain.RentalOrder;
import vn.lemar.selfstorage.booking.domain.RentalOrderStatus;
import vn.lemar.selfstorage.booking.repository.AppointmentReadRepository;
import vn.lemar.selfstorage.booking.repository.AppointmentReadRepository.AppointmentRow;
import vn.lemar.selfstorage.booking.repository.AppointmentRepository;
import vn.lemar.selfstorage.booking.repository.InvoiceRepository;
import vn.lemar.selfstorage.booking.repository.ProposalFeedbackRepository;
import vn.lemar.selfstorage.booking.repository.RentalOrderRepository;
import vn.lemar.selfstorage.identity.application.FacilityStaffQuery;
import vn.lemar.selfstorage.notification.application.NotificationType;
import vn.lemar.selfstorage.payment.application.PaymentService;
import vn.lemar.selfstorage.pricing.application.PricingQuery;

/**
 * Spec 1.5 — khách chọn lịch check-in sau khi đặt cọc. Hành vi chốt ở behavior-gap đặt lịch check-in:
 *
 * <ul>
 *   <li>3 khung cố định {@code 08:00–10:00}, {@code 13:00–15:00}, {@code 17:00–19:00} (Asia/Ho_Chi_Minh);</li>
 *   <li>capacity 1 lịch {@code CHECKIN} / cơ sở / khung — chống đặt trùng bằng khoá đơn rồi khoá row cơ sở;</li>
 *   <li>cửa sổ ngày: sớm nhất hôm nay, muộn nhất {@code min(lúc cọc + max_days_after_deposit, expires_at)};
 *       quá {@code lúc cọc + booking_window_days} thì không đặt được nữa;</li>
 *   <li>"lúc cọc" = muộn nhất trong: {@code paid_at} giao dịch {@code Success} của hoá đơn {@code Deposit}, của hoá
 *       đơn {@code DepositSupplement}, {@code agreed_at} của đề xuất khoang được đồng ý;</li>
 *   <li>đơn phải {@code Deposited}, thuộc khách; đơn người khác → 404.</li>
 * </ul>
 *
 * <p>Tạo lịch: {@code Appointment(CHECKIN, Pending)} + {@code rental_appointments}, đơn {@code Deposited ->
 * Scheduled}, audit {@code APPOINTMENT_CREATED} (lịch + đơn), phát {@link CheckinAppointmentCreated} để handover
 * mở {@code HandoverRecord} trong cùng transaction, notification {@code APPOINTMENT_CREATED} cho khách + FM và
 * {@code FS_ASSIGNMENT_REQUIRED} khi cơ sở chưa có FS đang hoạt động.
 */
@Service
public class AppointmentBookingService {

    static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    /** Ba khung cố định mỗi ngày — domain constant, không lưu trong {@code policies}. */
    static final List<LocalTime[]> WINDOWS = List.of(
            new LocalTime[] {LocalTime.of(8, 0), LocalTime.of(10, 0)},
            new LocalTime[] {LocalTime.of(13, 0), LocalTime.of(15, 0)},
            new LocalTime[] {LocalTime.of(17, 0), LocalTime.of(19, 0)});

    static final String POLICY_WINDOW_DAYS = "appointment.booking_window_days";
    static final String POLICY_MAX_DAYS = "appointment.max_days_after_deposit";

    static final String MSG_ORDER_NOT_FOUND = "Không tìm thấy đơn thuê";
    static final String MSG_NOT_DEPOSITED = "Đơn thuê chưa ở trạng thái chờ đặt lịch check-in";
    static final String MSG_AWAITING_UNIT = "Cơ sở đang sắp xếp khoang khác cho đơn này. "
            + "Vui lòng đợi đề xuất mới và hoàn tất đặt cọc bổ sung (nếu có) trước khi đặt lịch.";
    static final String MSG_ALREADY_SCHEDULED = "Đơn thuê đã có lịch hẹn check-in";
    static final String MSG_NO_DEPOSIT_TIME = "Chưa xác định được mốc đặt cọc của đơn, vui lòng liên hệ cơ sở";
    static final String MSG_WINDOW_PASSED = "Đã quá hạn đặt lịch check-in";
    static final String MSG_NO_SLOT_LEFT = "Không còn khung giờ trước hạn giữ kho, vui lòng liên hệ cơ sở";
    static final String MSG_DATE_OUT_OF_WINDOW = "Ngày hẹn nằm ngoài khoảng cho phép";
    static final String MSG_INVALID_SLOT = "Khung giờ không hợp lệ";
    static final String MSG_SLOT_PASSED = "Khung giờ đã qua, vui lòng chọn khung khác";
    static final String MSG_SLOT_TAKEN = "Khung giờ đã có người đặt, vui lòng chọn khung khác";

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("HH:mm").withZone(ZONE);

    private final RentalOrderRepository orderRepository;
    private final InvoiceRepository invoiceRepository;
    private final ProposalFeedbackRepository proposalRepository;
    private final AppointmentRepository appointmentRepository;
    private final AppointmentReadRepository readRepository;
    private final PaymentService paymentService;
    private final PricingQuery pricingQuery;
    private final FacilityStaffQuery facilityStaffQuery;
    private final AuditRecorder auditRecorder;
    private final OrderNotifier orderNotifier;
    private final ApplicationEventPublisher events;

    AppointmentBookingService(RentalOrderRepository orderRepository, InvoiceRepository invoiceRepository,
                              ProposalFeedbackRepository proposalRepository,
                              AppointmentRepository appointmentRepository, AppointmentReadRepository readRepository,
                              PaymentService paymentService, PricingQuery pricingQuery,
                              FacilityStaffQuery facilityStaffQuery, AuditRecorder auditRecorder,
                              OrderNotifier orderNotifier, ApplicationEventPublisher events) {
        this.orderRepository = orderRepository;
        this.invoiceRepository = invoiceRepository;
        this.proposalRepository = proposalRepository;
        this.appointmentRepository = appointmentRepository;
        this.readRepository = readRepository;
        this.paymentService = paymentService;
        this.pricingQuery = pricingQuery;
        this.facilityStaffQuery = facilityStaffQuery;
        this.auditRecorder = auditRecorder;
        this.orderNotifier = orderNotifier;
        this.events = events;
    }

    /** Một khung giờ trong ngày. */
    public record Slot(Instant startAt, Instant endAt, boolean available) {
    }

    /** Các khung của một ngày. */
    public record Day(LocalDate date, List<Slot> slots) {
    }

    /** Toàn bộ cửa sổ đặt lịch của đơn. */
    public record SlotWindow(Long orderId, Long facilityId, LocalDate minDate, LocalDate maxDate, List<Day> days) {
    }

    /** Lịch vừa tạo. */
    public record BookedAppointment(Long appointmentId, Long orderId, String orderStatus, LocalDate date,
                                    Instant startAt, Instant endAt, String facilityName, String facilityAddress) {
    }

    @Transactional(readOnly = true)
    public SlotWindow slots(Long orderId, Long customerId) {
        RentalOrder order = orderRepository.findById(orderId)
                .filter(o -> o.getCustomerId().equals(customerId))
                .orElseThrow(() -> new BookingNotFoundException(MSG_ORDER_NOT_FOUND));
        Long facilityId = facilityOf(order);
        Window window = window(order);
        Set<Instant> taken =
                new HashSet<>(appointmentRepository.findTakenSlotStarts(facilityId, window.min, window.max));
        Instant now = Instant.now();
        List<Day> days = new ArrayList<>();
        for (LocalDate d = window.min; !d.isAfter(window.max); d = d.plusDays(1)) {
            List<Slot> slots = new ArrayList<>();
            for (LocalTime[] w : WINDOWS) {
                Instant start = d.atTime(w[0]).atZone(ZONE).toInstant();
                Instant end = d.atTime(w[1]).atZone(ZONE).toInstant();
                boolean available = start.isAfter(now) && fitsOrder(order, end) && !taken.contains(start);
                slots.add(new Slot(start, end, available));
            }
            days.add(new Day(d, slots));
        }
        return new SlotWindow(order.getId(), facilityId, window.min, window.max, days);
    }

    @Transactional
    public BookedAppointment book(Long orderId, Long customerId, LocalDate date, Instant startAt) {
        if (date == null || startAt == null) {
            throw new BookingValidationException("Thiếu ngày hoặc khung giờ hẹn");
        }
        RentalOrder order = orderRepository.findByIdForUpdate(orderId)
                .filter(o -> o.getCustomerId().equals(customerId))
                .orElseThrow(() -> new BookingNotFoundException(MSG_ORDER_NOT_FOUND));
        if (appointmentRepository.existsPendingByOrderId(orderId)) {
            throw new BookingConflictException(MSG_ALREADY_SCHEDULED);
        }
        Window window = window(order);
        if (date.isBefore(window.min) || date.isAfter(window.max)) {
            throw new BookingValidationException(MSG_DATE_OUT_OF_WINDOW);
        }
        LocalTime[] slot = WINDOWS.stream()
                .filter(w -> date.atTime(w[0]).atZone(ZONE).toInstant().equals(startAt))
                .findFirst()
                .orElseThrow(() -> new BookingValidationException(MSG_INVALID_SLOT));
        Instant endAt = date.atTime(slot[1]).atZone(ZONE).toInstant();
        if (!startAt.isAfter(Instant.now())) {
            throw new BookingValidationException(MSG_SLOT_PASSED);
        }
        if (!fitsOrder(order, endAt)) {
            throw new BookingConflictException(MSG_NO_SLOT_LEFT);
        }

        Long facilityId = facilityOf(order);
        appointmentRepository.lockFacility(facilityId);
        if (appointmentRepository.findTakenSlotStarts(facilityId, date, date).contains(startAt)) {
            throw new BookingConflictException(MSG_SLOT_TAKEN);
        }

        Appointment appointment = appointmentRepository.save(
                Appointment.checkin(customerId, facilityId, date, startAt, endAt));
        appointmentRepository.linkToOrder(appointment.getId(), orderId);
        String orderBefore = order.getStatus().dbValue();
        order.schedule();

        Map<String, Object> appointmentValue = new HashMap<>();
        appointmentValue.put("type", "CHECKIN");
        appointmentValue.put("status", "Pending");
        appointmentValue.put("orderId", orderId);
        appointmentValue.put("startedAt", startAt.toString());
        auditRecorder.record(new AuditEntry(AuditAction.APPOINTMENT_CREATED, AuditEntityType.APPOINTMENT,
                String.valueOf(appointment.getId()), null, appointmentValue, null, customerId));
        auditRecorder.record(new AuditEntry(AuditAction.APPOINTMENT_CREATED, AuditEntityType.RENTAL_ORDER,
                String.valueOf(orderId), Map.of("status", orderBefore),
                Map.of("status", order.getStatus().dbValue(), "appointmentId", appointment.getId()), null,
                customerId));

        // HandoverRecord do module handover mở trong cùng transaction — lỗi ở đó rollback cả lịch.
        events.publishEvent(new CheckinAppointmentCreated(appointment.getId(), orderId, order.getUnitId()));

        appointmentRepository.flush();
        AppointmentRow row = readRepository.findById(appointment.getId())
                .orElseThrow(() -> new IllegalStateException("Không đọc lại được lịch " + appointment.getId()));
        notifyCreated(order, row);
        return new BookedAppointment(appointment.getId(), orderId, order.getStatus().dbValue(), date, startAt, endAt,
                row.facilityName(), row.facilityAddress());
    }

    private void notifyCreated(RentalOrder order, AppointmentRow row) {
        Map<String, String> values = new HashMap<>();
        values.put("orderCode", OrderNotifier.orderCode(order));
        values.put("appointmentTime", HOUR.format(row.startedAt()) + "–" + HOUR.format(row.endAt()) + " "
                + DAY.format(row.date()));
        values.put("facilityAddress", row.facilityAddress() == null ? row.facilityName() : row.facilityAddress());
        orderNotifier.notifyCustomer(order, NotificationType.APPOINTMENT_CREATED, values);
        orderNotifier.notifyFacilityManager(order.getId(), NotificationType.APPOINTMENT_CREATED, values);
        if (facilityStaffQuery.listStaff(row.facilityId()).isEmpty()) {
            orderNotifier.notifyFacilityManager(order.getId(), NotificationType.FS_ASSIGNMENT_REQUIRED, values);
        }
    }

    private record Window(LocalDate min, LocalDate max) {
    }

    /**
     * Đơn đặt được lịch check-in ngay bây giờ không — cùng luật với lúc đặt; dùng để quyết định hiện nút "Đặt lịch"
     * cho khách (không hiện nút cho thao tác chắc chắn bị 409).
     */
    @Transactional(readOnly = true)
    public boolean bookable(Long orderId) {
        RentalOrder order = orderRepository.findById(orderId).orElse(null);
        if (order == null) {
            return false;
        }
        try {
            window(order);
            return true;
        } catch (BookingConflictException e) {
            return false;
        }
    }

    /** Cửa sổ ngày đặt lịch; đơn không đặt được (sai trạng thái, quá hạn) → 409. */
    private Window window(RentalOrder order) {
        if (order.getStatus() != RentalOrderStatus.DEPOSITED) {
            throw new BookingConflictException(order.getStatus() == RentalOrderStatus.SCHEDULED
                    || order.getStatus() == RentalOrderStatus.IN_PROGRESS ? MSG_ALREADY_SCHEDULED : MSG_NOT_DEPOSITED);
        }
        // Khách từ chối khoang tại bàn giao: đợi đề xuất mới được đồng ý + cọc bổ sung trả xong.
        if (order.isNeedsReproposal()
                || "Pending".equals(orderRepository.findLatestProposalStatus(order.getId()).orElse(null))
                || invoiceRepository.existsByOrderIdAndStatusInAndTypeIn(order.getId(),
                        java.util.List.of(InvoiceStatus.UNPAID), java.util.List.of(InvoiceType.DEPOSIT_SUPPLEMENT))) {
            throw new BookingConflictException(MSG_AWAITING_UNIT);
        }
        Instant depositPaidAt = bookableSince(order);
        Instant now = Instant.now();
        // Đặt lại sau khi lịch check-in trước bị huỷ (no-show, FS huỷ, quá hạn bàn giao): không áp cửa sổ đặt lịch
        // tính từ lúc cọc — khách được đặt lại trong thời hạn giữ đơn (expires_at).
        boolean rebooking = appointmentRepository.existsCanceledCheckinByOrderId(order.getId());
        if (!rebooking
                && now.isAfter(depositPaidAt.plus(Duration.ofDays(pricingQuery.intPolicy(POLICY_WINDOW_DAYS))))) {
            throw new BookingConflictException(MSG_WINDOW_PASSED);
        }
        LocalDate min = LocalDate.now(ZONE);
        LocalDate max = rebooking && order.getExpiresAt() != null
                ? order.getExpiresAt().atZone(ZONE).toLocalDate()
                : depositPaidAt.plus(Duration.ofDays(pricingQuery.intPolicy(POLICY_MAX_DAYS)))
                        .atZone(ZONE).toLocalDate();
        if (order.getExpiresAt() != null) {
            LocalDate expiry = order.getExpiresAt().atZone(ZONE).toLocalDate();
            if (expiry.isBefore(max)) {
                max = expiry;
            }
        }
        if (max.isBefore(min)) {
            throw new BookingConflictException(MSG_NO_SLOT_LEFT);
        }
        return new Window(min, max);
    }

    /**
     * Mốc tính cửa sổ đặt lịch = muộn nhất trong: lúc trả cọc, lúc trả cọc bổ sung, lúc khách đồng ý đề xuất khoang.
     * Đơn đã cọc nhưng phải đổi khoang (khoang bị giữ trước / từ chối tại bàn giao) được tính lại từ lúc đồng ý khoang
     * mới, không bị quá hạn vì thời gian chờ cơ sở đề xuất.
     */
    private Instant bookableSince(RentalOrder order) {
        Instant since = invoiceRepository
                .findFirstByOrderIdAndTypeAndStatusOrderByIdDesc(order.getId(), InvoiceType.DEPOSIT, InvoiceStatus.PAID)
                .flatMap(invoice -> paymentService.firstPaidAt(invoice.getId()))
                .orElseThrow(() -> new BookingConflictException(MSG_NO_DEPOSIT_TIME));
        Optional<Instant> supplement = invoiceRepository.findFirstByOrderIdAndTypeAndStatusOrderByIdDesc(order.getId(),
                        InvoiceType.DEPOSIT_SUPPLEMENT, InvoiceStatus.PAID)
                .flatMap(invoice -> paymentService.firstPaidAt(invoice.getId()));
        Optional<Instant> agreed = proposalRepository.findLatestAgreedAt(order.getId(), ProposalStatus.AGREED);
        for (Optional<Instant> later : List.of(supplement, agreed)) {
            if (later.isPresent() && later.get().isAfter(since)) {
                since = later.get();
            }
        }
        return since;
    }

    /** Spec Appointment › CONSTRAINTS: lịch check-in phải kết thúc trước {@code RentalOrder.expires_at}. */
    private static boolean fitsOrder(RentalOrder order, Instant endAt) {
        return order.getExpiresAt() == null || !endAt.isAfter(order.getExpiresAt());
    }

    private Long facilityOf(RentalOrder order) {
        return orderRepository.findFacilityId(order.getId())
                .orElseThrow(() -> new BookingNotFoundException(MSG_ORDER_NOT_FOUND));
    }
}
