package vn.lemar.selfstorage.overdue.application;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.lemar.selfstorage.booking.application.ContractInvoiceService;
import vn.lemar.selfstorage.booking.application.ContractInvoiceService.ContractBill;
import vn.lemar.selfstorage.booking.application.HandoverOrderService;
import vn.lemar.selfstorage.booking.application.OrderNotificationService;
import vn.lemar.selfstorage.handover.application.RentalContractService;
import vn.lemar.selfstorage.handover.application.RentalContractService.ContractSnapshot;
import vn.lemar.selfstorage.identity.application.Access;
import vn.lemar.selfstorage.notification.application.NotificationService;
import vn.lemar.selfstorage.notification.application.NotificationType;
import vn.lemar.selfstorage.pricing.application.PricingQuery;
import vn.lemar.selfstorage.tenancy.application.TenancyService;

/**
 * Flow 6 — quá hạn hợp đồng.
 * <ul>
 *   <li>Nhắc {@code CONTRACT_EXPIRING_SOON} khi còn {@code contract.expiry_reminder_days}, một lần mỗi kỳ (mỗi
 *       {@code end_date}).</li>
 *   <li>Qua {@code end_date}: báo khách + FM {@code CONTRACT_OVERDUE} một lần mỗi kỳ; sau
 *       {@code overdue.grace_days} ngày ân hạn, phí cộng dồn {@code số ngày × overdue.fee_per_day} trên đúng một
 *       hoá đơn {@code INV-OVD} còn mở.</li>
 *   <li>Ngừng tính ở ngày buổi trả kho khách đã đến (FS xác nhận); gia hạn phủ kỳ quá hạn → hoá đơn đang mở bị
 *       huỷ.</li>
 * </ul>
 * Mỗi hợp đồng một transaction; thứ tự khoá đơn → hợp đồng → hoá đơn.
 */
@Service
public class OverdueService {

    static final String POLICY_GRACE = "overdue.grace_days";
    static final String POLICY_FEE = "overdue.fee_per_day";
    static final String POLICY_REMINDER = "contract.expiry_reminder_days";
    static final String POLICY_ESCALATION = "overdue.escalation_days";
    static final String RESTORE_NOTE_CLEARED = "không còn quá hạn.";
    static final String RESTORE_NOTE_MOVING_OUT = "được mở lại để bạn dọn đồ trả kho; phí quá hạn vẫn tính tới ngày "
            + "bạn đến buổi trả kho.";
    static final String PERM_READ = "rental_order.read";
    static final int INVOICE_DUE_DAYS = 7;

    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final RentalContractService contracts;
    private final ContractInvoiceService invoices;
    private final HandoverOrderService orders;
    private final TenancyService tenancy;
    private final OrderNotificationService notifications;
    private final NotificationService notificationService;
    private final PricingQuery pricingQuery;
    private final Access access;

    OverdueService(RentalContractService contracts, ContractInvoiceService invoices, HandoverOrderService orders,
                   TenancyService tenancy, OrderNotificationService notifications,
                   NotificationService notificationService, PricingQuery pricingQuery, Access access) {
        this.contracts = contracts;
        this.invoices = invoices;
        this.orders = orders;
        this.tenancy = tenancy;
        this.notifications = notifications;
        this.notificationService = notificationService;
        this.pricingQuery = pricingQuery;
        this.access = access;
    }

    /** Hợp đồng quá hạn (cho FM). */
    public record OverdueContract(Long contractId, String contractCode, Long orderId, String unitCode,
                                  LocalDate endDate, long daysOverdue, long chargedDays, BigDecimal accrued,
                                  ContractBill openInvoice, boolean returnRequested) {
    }

    // ------------------------------------------------------------------ cron

    @Transactional(readOnly = true)
    public List<Long> expiringIds() {
        LocalDate today = LocalDate.now(ZONE);
        return contracts.activeEndingBetween(today, today.plusDays(pricingQuery.intPolicy(POLICY_REMINDER)));
    }

    /** Nhắc sắp hết hạn — mỗi đơn một lần. */
    @Transactional
    public boolean remindExpiring(Long contractId) {
        ContractSnapshot c = contracts.find(contractId).orElse(null);
        // Một lần mỗi kỳ: chỉ tính nhắc gửi từ đầu cửa sổ nhắc của end_date hiện tại — gia hạn xong thì nhắc lại.
        if (c == null || !c.active() || notificationService.hasOrderNotificationSince(c.orderId(),
                NotificationType.CONTRACT_EXPIRING_SOON,
                startOf(c.endDate().minusDays(pricingQuery.intPolicy(POLICY_REMINDER))))) {
            return false;
        }
        long daysLeft = ChronoUnit.DAYS.between(LocalDate.now(ZONE), c.endDate());
        notifications.notifyCustomer(c.orderId(), NotificationType.CONTRACT_EXPIRING_SOON,
                Map.of("contractCode", c.code(), "unitCode", unitCode(c), "endDate", DATE.format(c.endDate()),
                        "daysLeft", String.valueOf(daysLeft)));
        return true;
    }

    /** Hợp đồng cần đồng bộ phí quá hạn: đang quá hạn + đang có hoá đơn phí quá hạn mở. */
    @Transactional(readOnly = true)
    public List<Long> overdueCandidateIds() {
        Set<Long> ids = new LinkedHashSet<>(contracts.activeEndedBefore(LocalDate.now(ZONE)));
        ids.addAll(invoices.contractsWithOpenOverdueFee());
        return List.copyOf(ids);
    }

    /** Đồng bộ phí quá hạn một hợp đồng; {@code true} khi hợp đồng đang quá hạn. */
    @Transactional
    public boolean accrue(Long contractId) {
        ContractSnapshot peek = contracts.find(contractId).orElse(null);
        if (peek == null) {
            return false;
        }
        orders.lock(peek.orderId());
        ContractSnapshot c = contracts.lock(contractId).orElseThrow();
        Calc calc = calc(c);
        if (c.active() && calc.daysOverdue() > 0
                && !notificationService.hasOrderNotificationSince(c.orderId(), NotificationType.CONTRACT_OVERDUE,
                        startOf(c.endDate().plusDays(1)))) {
            Map<String, String> values = Map.of("contractCode", c.code(), "unitCode", unitCode(c),
                    "endDate", DATE.format(c.endDate()),
                    "feePerDay", pricingQuery.decimalPolicy(POLICY_FEE).toPlainString(),
                    "graceDays", String.valueOf(pricingQuery.intPolicy(POLICY_GRACE)));
            notifications.notifyCustomer(c.orderId(), NotificationType.CONTRACT_OVERDUE, values);
            notifications.notifyFacilityManager(c.orderId(), NotificationType.CONTRACT_OVERDUE, values);
        }
        escalate(c, calc);
        BigDecimal accrued = c.active() ? calc.accrued() : BigDecimal.ZERO;
        invoices.syncOverdueFee(c.orderId(), c.id(), c.customerId(), accrued, calc.chargedDays(),
                Instant.now().plus(Duration.ofDays(INVOICE_DUE_DAYS)), startOf(c.endDate().plusDays(1)));
        return calc.daysOverdue() > 0;
    }

    /** Hợp đồng đang thuê bị tạm khoá truy cập — cron kiểm tra để mở lại khi hết quá hạn. */
    @Transactional(readOnly = true)
    public List<Long> suspendedIds() {
        return contracts.activeIds().stream().filter(contracts::accessSuspended).toList();
    }

    /** Hết quá hạn (gia hạn phủ kỳ quá hạn) và không còn phí quá hạn chưa trả → mở lại truy cập. */
    @Transactional
    public boolean restoreIfCleared(Long contractId) {
        ContractSnapshot peek = contracts.find(contractId).orElse(null);
        if (peek == null) {
            return false;
        }
        orders.lock(peek.orderId());
        ContractSnapshot c = contracts.lock(contractId).orElseThrow();
        boolean overdue = c.endDate().isBefore(LocalDate.now(ZONE));
        boolean feeOpen = invoices.bills(c.id()).stream()
                .anyMatch(b -> b.unpaid() && b.code().startsWith("INV-" + ContractInvoiceService.OVERDUE_KIND));
        // Còn quá hạn nhưng đã gửi yêu cầu trả kho và trả hết phí tới nay → mở để khách dọn đồ. Phí vẫn tính tới buổi
        // trả kho khách đến; escalate bỏ qua hợp đồng có yêu cầu trả kho mở nên không khoá lại.
        boolean movingOut = overdue && tenancy.openReturnOf(c.id()).isPresent();
        // Đã chốt quyết toán trả kho (FS đã thu hồi chìa / mã) — không "mở lại để dọn đồ" nữa.
        boolean settled = invoices.bills(c.id()).stream()
                .anyMatch(b -> ContractInvoiceService.isSettlementCode(b.code()) && !"Canceled".equals(b.status()));
        if ((overdue && !movingOut) || feeOpen || settled || !contracts.restoreAccess(c.id(), null)) {
            return false;
        }
        notifications.notifyCustomer(c.orderId(), NotificationType.CONTRACT_ACCESS_RESTORED,
                Map.of("contractCode", c.code(), "unitCode", unitCode(c), "restoreNote",
                        movingOut ? RESTORE_NOTE_MOVING_OUT : RESTORE_NOTE_CLEARED));
        return true;
    }

    /**
     * Quá hạn kéo dài ({@code overdue.escalation_days} ngày sau {@code end_date}) mà khách chưa gửi yêu cầu trả kho →
     * tạm khoá mã khoá số, báo khách + FM một lần (khi vừa khoá).
     */
    private void escalate(ContractSnapshot c, Calc calc) {
        if (!c.active() || calc.returnRequested()
                || calc.daysOverdue() < pricingQuery.intPolicy(POLICY_ESCALATION)) {
            return;
        }
        if (contracts.suspendAccess(c.id(), calc.daysOverdue(), null)) {
            Map<String, String> values = Map.of("contractCode", c.code(), "unitCode", unitCode(c),
                    "days", String.valueOf(calc.daysOverdue()));
            notifications.notifyCustomer(c.orderId(), NotificationType.CONTRACT_ACCESS_SUSPENDED, values);
            notifications.notifyFacilityManager(c.orderId(), NotificationType.CONTRACT_ACCESS_SUSPENDED, values);
        }
    }

    // ------------------------------------------------------------------ FM

    @Transactional(readOnly = true)
    public List<OverdueContract> facilityOverdue(Long facilityId) {
        access.can(PERM_READ, facilityId);
        return contracts.activeEndedBefore(LocalDate.now(ZONE)).stream()
                .map(contracts::find).flatMap(Optional::stream)
                .filter(c -> facilityId.equals(c.facilityId()))
                .map(c -> {
                    Calc calc = calc(c);
                    ContractBill open = invoices.bills(c.id()).stream()
                            .filter(b -> b.unpaid()
                                    && b.code().startsWith("INV-" + ContractInvoiceService.OVERDUE_KIND))
                            .findFirst().orElse(null);
                    return new OverdueContract(c.id(), c.code(), c.orderId(), unitCode(c), c.endDate(),
                            calc.daysOverdue(), calc.chargedDays(), calc.accrued(), open, calc.returnRequested());
                })
                .toList();
    }

    // ------------------------------------------------------------------ internals

    private record Calc(long daysOverdue, long chargedDays, BigDecimal accrued, boolean returnRequested) {
    }

    /**
     * Phí tính tới hôm nay chừng nào trả kho chưa xong; khách đã đến buổi trả kho (FS xác nhận đến) thì dừng ở ngày
     * của buổi đó ({@code start_at}) — kể cả khi biên bản còn chờ (khoang còn đồ / chờ trả phần thiếu). Lịch hẹn
     * chưa tới hoặc khách không đến thì vẫn tính tới hôm nay, không bao giờ vượt hôm nay.
     */
    private Calc calc(ContractSnapshot c) {
        LocalDate today = LocalDate.now(ZONE);
        var open = tenancy.openReturnOf(c.id());
        LocalDate stop = open.filter(r -> r.appointmentArrivedAt() != null && r.appointmentStart() != null)
                .map(r -> r.appointmentStart().atZone(ZONE).toLocalDate())
                .filter(attended -> attended.isBefore(today))
                .orElse(today);
        long daysOverdue = Math.max(0, ChronoUnit.DAYS.between(c.endDate(), today));
        long charged = Math.max(0, ChronoUnit.DAYS.between(c.endDate(), stop) - pricingQuery.intPolicy(POLICY_GRACE));
        BigDecimal accrued = pricingQuery.decimalPolicy(POLICY_FEE).multiply(BigDecimal.valueOf(charged));
        return new Calc(daysOverdue, charged, accrued, open.isPresent());
    }

    private static java.time.Instant startOf(LocalDate date) {
        return date.atStartOfDay(ZONE).toInstant();
    }

    private String unitCode(ContractSnapshot c) {
        return contracts.unitCode(c.id()).orElse("");
    }
}
