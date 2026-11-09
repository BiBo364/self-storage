package vn.lemar.selfstorage.overdue.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.lemar.selfstorage.booking.application.ContractInvoiceExpired;
import vn.lemar.selfstorage.booking.application.ContractInvoiceService;
import vn.lemar.selfstorage.booking.application.ContractInvoiceService.ContractBill;
import vn.lemar.selfstorage.booking.application.HandoverOrderService;
import vn.lemar.selfstorage.booking.application.OrderNotificationService;
import vn.lemar.selfstorage.handover.application.RentalContractService;
import vn.lemar.selfstorage.handover.application.RentalContractService.ContractSnapshot;
import vn.lemar.selfstorage.notification.application.NotificationType;
import vn.lemar.selfstorage.pricing.application.PricingQuery;
import vn.lemar.selfstorage.tenancy.application.TenancyService;

/**
 * Thu tiền thuê định kỳ. Hoá đơn tháng đầu ({@code contract.prepaid_months} tháng) thu lúc bàn giao; các tháng sau
 * của kỳ thuê được lập thành hoá đơn {@code Rental} (mã {@code INV-RNT-…}) trước ngày bắt đầu tháng
 * {@code rent.invoice_lead_days} ngày, hạn thanh toán là ngày bắt đầu tháng.
 *
 * <p>Gia hạn trả trước và nối vào cuối kỳ, nên tháng cần lập hoá đơn là {@code period − tháng gia hạn đã trả}. Số
 * tháng đã lập = tổng tháng của hoá đơn {@code Rental} không bị huỷ (kể cả quá hạn — nợ vẫn tính). Còn tháng chưa lập
 * thì lập đúng một tháng mỗi lượt. Đang có yêu cầu trả kho thì không lập tháng mới.
 */
@Service
public class RentBillingService {

    static final String POLICY_LEAD_DAYS = "rent.invoice_lead_days";
    static final String RENT_KIND = "RNT";
    /** Hạn tối thiểu khi cron lập muộn (tháng đã bắt đầu). */
    static final int MIN_DUE_DAYS = 3;

    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final RentalContractService contracts;
    private final ContractInvoiceService invoices;
    private final HandoverOrderService orders;
    private final TenancyService tenancy;
    private final OrderNotificationService notifications;
    private final PricingQuery pricingQuery;
    private final String frontendUrl;

    RentBillingService(RentalContractService contracts, ContractInvoiceService invoices, HandoverOrderService orders,
                       TenancyService tenancy, OrderNotificationService notifications, PricingQuery pricingQuery,
                       @Value("${app.frontend-url}") String frontendUrl) {
        this.contracts = contracts;
        this.invoices = invoices;
        this.orders = orders;
        this.tenancy = tenancy;
        this.notifications = notifications;
        this.pricingQuery = pricingQuery;
        this.frontendUrl = frontendUrl.replaceAll("/+$", "");
    }

    @Transactional(readOnly = true)
    public List<Long> candidateIds() {
        return contracts.activeIds();
    }

    /** Lập hoá đơn tháng kế tiếp nếu đến hạn; {@code true} khi vừa lập. */
    @Transactional
    public boolean bill(Long contractId) {
        ContractSnapshot peek = contracts.find(contractId).orElse(null);
        if (peek == null || !peek.active()) {
            return false;
        }
        orders.lock(peek.orderId());
        ContractSnapshot c = contracts.lock(contractId).orElseThrow();
        if (!c.active() || tenancy.openReturnOf(c.id()).isPresent()) {
            return false;
        }
        int covered = months(c, "Rental");
        if (covered >= c.period() - months(c, "Extension")) {
            return false;
        }
        LocalDate monthStart = c.startDate().plusMonths(covered);
        LocalDate today = LocalDate.now(ZONE);
        if (today.isBefore(monthStart.minusDays(pricingQuery.intPolicy(POLICY_LEAD_DAYS)))) {
            return false;
        }
        LocalDate monthEnd = c.startDate().plusMonths(covered + 1L).minusDays(1);
        LocalDate earliestDue = today.plusDays(MIN_DUE_DAYS);
        LocalDate dueDay = monthStart.isBefore(earliestDue) ? earliestDue : monthStart;
        Instant due = dueDay.atStartOfDay(ZONE).toInstant();
        int month = covered + 1;
        String unitCode = contracts.unitCode(c.id()).orElse("");
        ContractBill bill = invoices.issue(c.orderId(), c.id(), c.customerId(), "Rental", RENT_KIND,
                "Tiền thuê tháng " + month + "/" + c.period() + " — hợp đồng " + c.code(),
                "Kỳ " + DATE.format(monthStart) + " – " + DATE.format(monthEnd) + ", khoang " + unitCode + ".",
                c.monthlyPrice(), due, null);
        notifications.notifyCustomer(c.orderId(), NotificationType.RENT_INVOICE_ISSUED,
                Map.of("month", month + "/" + c.period(), "contractCode", c.code(), "unitCode", unitCode,
                        "periodFrom", DATE.format(monthStart), "periodTo", DATE.format(monthEnd),
                        "amount", bill.amount().toPlainString(), "dueDate", DATE.format(dueDay),
                        "invoiceLink", frontendUrl + "/invoices"));
        return true;
    }

    /** Hoá đơn tiền thuê (không phải tháng đầu) quá hạn chưa trả → báo FM thu; nợ giữ nguyên ở hoá đơn Expired. */
    @EventListener
    public void on(ContractInvoiceExpired event) {
        if (!"Rental".equals(event.invoiceType())) {
            return;
        }
        ContractSnapshot c = contracts.find(event.contractId()).orElse(null);
        ContractBill bill = invoices.bill(event.invoiceId()).orElse(null);
        if (c == null || !c.active() || bill == null || !bill.code().startsWith("INV-" + RENT_KIND + "-")) {
            return;
        }
        notifications.notifyFacilityManager(c.orderId(), NotificationType.RENT_UNPAID,
                Map.of("invoiceCode", bill.code(), "contractCode", c.code(),
                        "unitCode", contracts.unitCode(c.id()).orElse(""), "amount", bill.amount().toPlainString()));
    }

    /** Số tháng của hoá đơn loại {@code type}: Rental không bị huỷ, Extension đã trả. */
    private int months(ContractSnapshot c, String type) {
        BigDecimal monthly = c.monthlyPrice();
        if (monthly == null || monthly.signum() <= 0) {
            return "Rental".equals(type) ? c.period() : 0;
        }
        int months = 0;
        for (ContractBill b : invoices.bills(c.id())) {
            boolean counted = "Rental".equals(type)
                    ? "Rental".equals(b.type()) && !"Canceled".equals(b.status())
                    : "Extension".equals(b.type()) && "Paid".equals(b.status());
            if (counted) {
                months += Math.max(1, b.amount().divide(monthly, 0, RoundingMode.HALF_UP).intValue());
            }
        }
        return months;
    }
}
