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
 *   <li>Nhắc {@code CONTRACT_EXPIRING_SOON} khi còn {@code contract.expiry_reminder_days}, mỗi đơn một lần.</li>
 *   <li>Qua {@code end_date}: báo khách + FM {@code CONTRACT_OVERDUE} một lần; sau {@code overdue.grace_days} ngày
 *       ân hạn, phí cộng dồn {@code số ngày × overdue.fee_per_day} trên đúng một hoá đơn {@code INV-OVD} còn mở.</li>
 *   <li>Ngừng tính từ ngày khách gửi yêu cầu trả kho; gia hạn phủ kỳ quá hạn → hoá đơn đang mở bị huỷ.</li>
 * </ul>
 * Mỗi hợp đồng một transaction; thứ tự khoá đơn → hợp đồng → hoá đơn.
 */
@Service
public class OverdueService {

    static final String POLICY_GRACE = "overdue.grace_days";
    static final String POLICY_FEE = "overdue.fee_per_day";
    static final String POLICY_REMINDER = "contract.expiry_reminder_days";
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
        if (c == null || !c.active()
                || notificationService.hasOrderNotification(c.orderId(), NotificationType.CONTRACT_EXPIRING_SOON)) {
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
                && !notificationService.hasOrderNotification(c.orderId(), NotificationType.CONTRACT_OVERDUE)) {
            Map<String, String> values = Map.of("contractCode", c.code(), "unitCode", unitCode(c),
                    "endDate", DATE.format(c.endDate()),
                    "feePerDay", pricingQuery.decimalPolicy(POLICY_FEE).toPlainString(),
                    "graceDays", String.valueOf(pricingQuery.intPolicy(POLICY_GRACE)));
            notifications.notifyCustomer(c.orderId(), NotificationType.CONTRACT_OVERDUE, values);
            notifications.notifyFacilityManager(c.orderId(), NotificationType.CONTRACT_OVERDUE, values);
        }
        BigDecimal accrued = c.active() ? calc.accrued() : BigDecimal.ZERO;
        invoices.syncOverdueFee(c.orderId(), c.id(), c.customerId(), accrued, calc.chargedDays(),
                Instant.now().plus(Duration.ofDays(INVOICE_DUE_DAYS)));
        return calc.daysOverdue() > 0;
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

    /** Ngày tính phí dừng ở ngày khách gửi yêu cầu trả kho (nếu có). */
    private Calc calc(ContractSnapshot c) {
        LocalDate today = LocalDate.now(ZONE);
        var open = tenancy.openReturnOf(c.id());
        LocalDate stop = open.map(r -> r.createdAt().atZone(ZONE).toLocalDate()).orElse(today);
        long daysOverdue = Math.max(0, ChronoUnit.DAYS.between(c.endDate(), today));
        long charged = Math.max(0, ChronoUnit.DAYS.between(c.endDate(), stop) - pricingQuery.intPolicy(POLICY_GRACE));
        BigDecimal accrued = pricingQuery.decimalPolicy(POLICY_FEE).multiply(BigDecimal.valueOf(charged));
        return new Calc(daysOverdue, charged, accrued, open.isPresent());
    }

    private String unitCode(ContractSnapshot c) {
        return contracts.unitCode(c.id()).orElse("");
    }
}
