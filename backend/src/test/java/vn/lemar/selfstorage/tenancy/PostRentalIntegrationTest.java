package vn.lemar.selfstorage.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.lemar.selfstorage.booking.application.ContractInvoiceService.ContractBill;
import vn.lemar.selfstorage.booking.application.InvoicePaymentService;
import vn.lemar.selfstorage.booking.application.StaffAppointmentActionService;
import vn.lemar.selfstorage.checkout.application.CheckoutService;
import vn.lemar.selfstorage.checkout.application.CheckoutService.CheckoutView;
import vn.lemar.selfstorage.checkout.application.CheckoutService.Inspection;
import vn.lemar.selfstorage.overdue.application.OverdueService;
import vn.lemar.selfstorage.payment.application.PaymentGateway;
import vn.lemar.selfstorage.payment.application.PaymentService;
import vn.lemar.selfstorage.tenancy.application.TenancyException;
import vn.lemar.selfstorage.tenancy.application.TenancyService;
import vn.lemar.selfstorage.tenancy.application.TenancyService.RentalView;
import vn.lemar.selfstorage.tenancy.application.TenancyService.ReturnRequestView;

/**
 * Sau bàn giao, chạy thật trên Postgres: kho đang thuê → gia hạn → yêu cầu trả kho → FM phân công → FS kiểm tra +
 * chốt (đối trừ cọc, thiếu thì hoá đơn bổ sung) → bảo trì → mở lại; phí quá hạn cộng dồn. Cổng thanh toán mock.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class PostRentalIntegrationTest {

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter PAY_DATE = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final String CUSTOMER = "customer1@lemar.vn";
    private static final String FS = "fs1@lemar.vn";
    private static final String FM = "fm1@lemar.vn";
    private static final BigDecimal DEPOSIT = new BigDecimal("500000.00");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @MockBean
    private PaymentGateway gateway;
    @MockBean
    private JavaMailSender mailSender;

    @Autowired
    private TenancyService tenancy;
    @Autowired
    private CheckoutService checkout;
    @Autowired
    private OverdueService overdue;
    @Autowired
    private StaffAppointmentActionService staffActions;
    @Autowired
    private InvoicePaymentService invoicePaymentService;
    @Autowired
    private PaymentService paymentService;
    @Autowired
    private vn.lemar.selfstorage.checkout.application.CheckoutJobs checkoutJobs;
    @Autowired
    private JdbcTemplate jdbc;

    private long customerId;
    private long facilityId;
    private long fsId;

    @BeforeEach
    void setUp() {
        customerId = id("select id from accounts where email = '" + CUSTOMER + "'");
        fsId = id("select id from accounts where email = '" + FS + "'");
        facilityId = id("select id from facilities where code = 'Q7'");
        when(gateway.buildPaymentUrl(any())).thenReturn("https://pay.test/x");
        when(gateway.newTxnRef(any())).thenCallRealMethod();
        when(gateway.verifySignature(anyMap())).thenReturn(true);
        when(mailSender.createMimeMessage()).thenReturn(
                new jakarta.mail.internet.MimeMessage(jakarta.mail.Session.getInstance(new java.util.Properties())));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------------ kho đang thuê + gia hạn

    @Test
    void rentalsShowActiveContractAndExtensionAddsMonthsAfterPayment() {
        Seed seed = seedActive(LocalDate.now(VN).plusMonths(1));
        RentalView view = tenancy.rentals(customerId).stream().filter(r -> r.contractId() == seed.contractId)
                .findFirst().orElseThrow();
        assertThat(view.canExtend()).isTrue();
        assertThat(view.canRequestReturn()).isTrue();
        assertThat(view.accessKeys()).hasSize(2);
        assertThatThrownBy(() -> tenancy.requestExtension(seed.contractId, customerId, 0))
                .isInstanceOf(TenancyException.class);
        assertThatThrownBy(() -> tenancy.rental(seed.contractId, customerId + 999))
                .isInstanceOf(TenancyException.class);

        ContractBill bill = tenancy.requestExtension(seed.contractId, customerId, 2);
        assertThat(bill.code()).startsWith("INV-EXT-Q7-");
        assertThat(bill.amount()).isEqualByComparingTo("200000");
        assertThatThrownBy(() -> tenancy.requestExtension(seed.contractId, customerId, 1))
                .isInstanceOf(TenancyException.class).hasMessageContaining("chờ thanh toán");
        assertThat(tenancy.rental(seed.contractId, customerId).canExtend()).isFalse();

        pay(bill);

        assertThat(jdbc.queryForObject("select end_date from rental_contracts where id = ?", LocalDate.class,
                seed.contractId)).isEqualTo(seed.endDate.plusMonths(2));
        assertThat(jdbc.queryForObject("select period from rental_contracts where id = ?", Integer.class,
                seed.contractId)).isEqualTo(5);
        assertThat(auditCount("CONTRACT_EXTENDED", "RentalContract", seed.contractId)).isEqualTo(1);
        assertThat(notifications(CUSTOMER, "CONTRACT_EXTENDED", seed.orderId)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ yêu cầu trả kho

    @Test
    void returnRequestAssignAndCancel() {
        Seed seed = seedActive(LocalDate.now(VN).plusMonths(1));
        LocalDate preferred = LocalDate.now(VN).plusDays(2);
        assertThatThrownBy(() -> tenancy.requestReturn(seed.contractId, customerId, LocalDate.now(VN).minusDays(1),
                null)).isInstanceOf(TenancyException.class);

        ReturnRequestView request = tenancy.requestReturn(seed.contractId, customerId, preferred, "Chuyển nhà");
        assertThat(request.status()).isEqualTo("Pending");
        assertThat(notifications(FM, "RETURN_REQUESTED", seed.orderId)).isEqualTo(1);
        assertThatThrownBy(() -> tenancy.requestReturn(seed.contractId, customerId, preferred, null))
                .isInstanceOf(TenancyException.class);
        assertThatThrownBy(() -> tenancy.requestExtension(seed.contractId, customerId, 1))
                .isInstanceOf(TenancyException.class).hasMessageContaining("yêu cầu trả kho");

        loginAs(FM);
        assertThat(tenancy.facilityReturns(facilityId)).extracting(ReturnRequestView::id).contains(request.id());
        assertThatThrownBy(() -> tenancy.assign(request.id(), customerId)).isInstanceOf(TenancyException.class);
        ReturnRequestView assigned = tenancy.assign(request.id(), fsId);
        assertThat(assigned.status()).isEqualTo("Assigned");
        assertThat(assigned.appointmentStart()).isNotNull();
        assertThat(jdbc.queryForObject("select type || ':' || status || ':' || staff_id from appointments where id = ?",
                String.class, assigned.appointmentId())).isEqualTo("RETURN:Pending:" + fsId);
        assertThat(notifications(CUSTOMER, "RETURN_SCHEDULED", seed.orderId)).isEqualTo(1);
        assertThat(notifications(FS, "RETURN_SCHEDULED", seed.orderId)).isEqualTo(1);

        // Phân công lại: lịch cũ bị huỷ, lịch mới thay thế.
        ReturnRequestView reassigned = tenancy.assign(request.id(), fsId);
        assertThat(appointmentStatus(assigned.appointmentId())).isEqualTo("Canceled");
        loginAs(FS);
        assertThat(tenancy.staffReturns()).extracting(ReturnRequestView::id).contains(request.id());

        ReturnRequestView canceled = tenancy.cancelReturn(request.id(), customerId);
        assertThat(canceled.status()).isEqualTo("Canceled");
        assertThat(appointmentStatus(reassigned.appointmentId())).isEqualTo("Canceled");
        assertThat(tenancy.myReturns(customerId)).extracting(ReturnRequestView::id).contains(request.id());
        assertThatThrownBy(() -> tenancy.cancelReturn(request.id(), customerId)).isInstanceOf(TenancyException.class);
    }

    // ------------------------------------------------------------------ trả kho

    @Test
    void cleanReturnRefundsWholeDeposit() {
        Seed seed = seedActive(LocalDate.now(VN).plusMonths(1));
        ReturnRequestView request = assignedAndArrived(seed);

        loginAs(FS);
        checkout.inspect(request.id(), new Inspection(true, false, null, null, 2, List.of(), "Sạch sẽ"));
        CheckoutView done = checkout.confirm(request.id());

        assertThat(done.result()).isEqualTo("COMPLETED");
        assertThat(done.totalCharges()).isEqualByComparingTo("0");
        assertThat(done.refundAmount()).isEqualByComparingTo(DEPOSIT);
        assertThat(contractStatus(seed.contractId)).isEqualTo("Ended");
        assertThat(unitStatus(seed.unitId)).isEqualTo("Maintenance");
        assertThat(jdbc.queryForObject("select status from return_requests where id = ?", String.class,
                request.id())).isEqualTo("Completed");
        assertThat(count("select count(*) from unit_access_keys where contract_id = ? and status = 'Revoked'",
                seed.contractId)).isEqualTo(2);
        assertThat(notifications(FM, "CHECKOUT_REFUND_REQUIRED", seed.orderId)).isEqualTo(1);
        assertThat(notifications(CUSTOMER, "CHECKOUT_COMPLETED", seed.orderId)).isEqualTo(1);
        assertThat(auditCount("CHECKOUT_COMPLETED", "CheckoutRecord", id("select id from checkout_records where "
                + "return_request_id = " + request.id()))).isEqualTo(1);
        assertThat(checkout.forCustomer(request.id(), customerId).result()).isEqualTo("COMPLETED");
        assertThatThrownBy(() -> checkout.confirm(request.id())).isInstanceOf(TenancyException.class);
    }

    @Test
    void chargesAndUnpaidInvoicesAreDeductedFromDeposit() {
        Seed seed = seedActive(LocalDate.now(VN).plusMonths(1));
        ContractBill extension = tenancy.requestExtension(seed.contractId, customerId, 1);
        ReturnRequestView request = assignedAndArrived(seed);

        loginAs(FS);
        checkout.inspect(request.id(), new Inspection(true, true, new BigDecimal("100000"), "Trầy cửa", 1,
                List.of("https://img.test/r.jpg"), null));
        CheckoutView view = checkout.forStaff(request.id());
        assertThat(view.unpaidContractInvoices()).isEqualByComparingTo("100000");
        assertThat(view.keysIssued()).isEqualTo(2);
        CheckoutView done = checkout.confirm(request.id());

        // 100.000 vệ sinh + 100.000 hư hỏng + 200.000 mất 1 chìa + 100.000 gia hạn chưa trả = 500.000 = cọc.
        assertThat(done.totalCharges()).isEqualByComparingTo("500000");
        assertThat(done.refundAmount()).isEqualByComparingTo("0");
        assertThat(done.result()).isEqualTo("COMPLETED");
        assertThat(invoiceStatus(extension.invoiceId())).isEqualTo("Canceled");
        assertThat(count("select count(*) from invoices where contract_id = ? and type = 'Penalty' and status = 'Paid' "
                + "and amount = 500000", seed.contractId)).isEqualTo(1);
        assertThat(count("select count(*) from unit_access_keys where contract_id = ? and status = 'Lost'",
                seed.contractId)).isEqualTo(1);
        assertThat(notifications(FM, "CHECKOUT_REFUND_REQUIRED", seed.orderId)).isZero();
    }

    @Test
    void shortfallInvoiceMustBePaidBeforeReturnCompletes() {
        Seed seed = seedActive(LocalDate.now(VN).plusMonths(1));
        ReturnRequestView request = assignedAndArrived(seed);

        loginAs(FS);
        checkout.inspect(request.id(), new Inspection(true, false, new BigDecimal("900000"), "Vỡ vách", 2,
                List.of(), null));
        CheckoutView awaiting = checkout.confirm(request.id());

        assertThat(awaiting.result()).isEqualTo("AWAITING_PAYMENT");
        assertThat(awaiting.shortfallInvoice().amount()).isEqualByComparingTo("400000");
        assertThat(contractStatus(seed.contractId)).isEqualTo("Active");
        assertThat(notifications(CUSTOMER, "CHECKOUT_PAYMENT_REQUIRED", seed.orderId)).isEqualTo(1);

        pay(awaiting.shortfallInvoice());

        assertThat(contractStatus(seed.contractId)).isEqualTo("Ended");
        assertThat(unitStatus(seed.unitId)).isEqualTo("Maintenance");
        loginAs(FS);
        assertThat(checkout.forStaff(request.id()).result()).isEqualTo("COMPLETED");
    }

    @Test
    void pendingItemsBlockConfirmUntilUnitIsEmpty() {
        Seed seed = seedActive(LocalDate.now(VN).plusMonths(1));
        ReturnRequestView request = assignedAndArrived(seed);
        loginAs(FS);
        assertThatThrownBy(() -> checkout.confirm(request.id())).isInstanceOf(TenancyException.class);
        assertThatThrownBy(() -> checkout.inspect(request.id(), new Inspection(true, false, new BigDecimal("-1"),
                null, 2, List.of(), null))).isInstanceOf(TenancyException.class);

        CheckoutView pending = checkout.inspect(request.id(), new Inspection(false, false, null, null, null,
                List.of(), "Còn 3 thùng đồ"));
        assertThat(pending.result()).isEqualTo("PENDING_ITEMS");
        assertThatThrownBy(() -> checkout.confirm(request.id())).isInstanceOf(TenancyException.class)
                .hasMessageContaining("còn tài sản");
        assertThat(count("select count(*) from unit_access_keys where contract_id = ? and status = 'Active'",
                seed.contractId)).isEqualTo(2);

        checkout.inspect(request.id(), new Inspection(true, false, null, null, 2, List.of(), null));
        assertThat(checkout.confirm(request.id()).result()).isEqualTo("COMPLETED");
    }

    @Test
    void checkoutNeedsArrivalAndAssignedStaff() {
        Seed seed = seedActive(LocalDate.now(VN).plusMonths(1));
        ReturnRequestView request = tenancy.requestReturn(seed.contractId, customerId, LocalDate.now(VN).plusDays(1),
                null);
        loginAs(FM);
        tenancy.assign(request.id(), fsId);
        loginAs(FS);
        assertThatThrownBy(() -> checkout.inspect(request.id(), new Inspection(true, false, null, null, 2, List.of(),
                null))).isInstanceOf(TenancyException.class).hasMessageContaining("khách đến");
        loginAs(FM);
        assertThatThrownBy(() -> checkout.confirm(request.id())).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> checkout.forCustomer(request.id(), customerId + 999))
                .isInstanceOf(TenancyException.class);
    }

    @Test
    void maintenanceCronReopensUnitAfterConfiguredDays() {
        Seed seed = seedActive(LocalDate.now(VN).plusMonths(1));
        jdbc.update("update storage_units set status = 'Maintenance', maintenance_started_at = now() - interval "
                + "'3 days' where id = ?", seed.unitId);
        long manual = id("insert into storage_units (code, facility_id, unit_type_id, status) values ('MAN-"
                + SEQ.incrementAndGet() + "-" + System.nanoTime() % 100000 + "', " + facilityId
                + ", (select id from unit_types where name = 'Small'), 'Maintenance') returning id");

        checkoutJobs.reopenUnits();

        assertThat(unitStatus(seed.unitId)).isEqualTo("Available");
        assertThat(unitStatus(manual)).isEqualTo("Maintenance");
        assertThat(auditCount("STORAGE_UNIT_AVAILABLE", "StorageUnit", seed.unitId)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ quá hạn

    @Test
    void overdueFeeAccruesDailyAndExtensionCancelsIt() {
        Seed seed = seedActive(LocalDate.now(VN).minusDays(10));
        assertThat(overdue.overdueCandidateIds()).contains(seed.contractId);

        assertThat(overdue.accrue(seed.contractId)).isTrue();
        // 10 ngày quá hạn − 3 ngày ân hạn = 7 ngày × 20.000.
        assertThat(openOverdueAmount(seed.contractId)).isEqualByComparingTo("140000");
        assertThat(notifications(CUSTOMER, "CONTRACT_OVERDUE", seed.orderId)).isEqualTo(1);
        assertThat(notifications(FM, "CONTRACT_OVERDUE", seed.orderId)).isEqualTo(1);

        jdbc.update("update rental_contracts set end_date = end_date - 1 where id = ?", seed.contractId);
        overdue.accrue(seed.contractId);
        assertThat(openOverdueAmount(seed.contractId)).isEqualByComparingTo("160000");
        assertThat(count("select count(*) from invoices where contract_id = ? and code like 'INV-OVD-%'",
                seed.contractId)).isEqualTo(1);
        assertThat(notifications(CUSTOMER, "CONTRACT_OVERDUE", seed.orderId)).isEqualTo(1);

        loginAs(FM);
        assertThat(overdue.facilityOverdue(facilityId)).anySatisfy(o -> {
            assertThat(o.contractId()).isEqualTo(seed.contractId);
            assertThat(o.chargedDays()).isEqualTo(8);
        });

        pay(tenancy.requestExtension(seed.contractId, customerId, 1));
        assertThat(overdue.overdueCandidateIds()).contains(seed.contractId);
        assertThat(overdue.accrue(seed.contractId)).isFalse();
        assertThat(count("select count(*) from invoices where contract_id = ? and code like 'INV-OVD-%' "
                + "and status = 'Unpaid'", seed.contractId)).isZero();
    }

    @Test
    void returnRequestStopsOverdueAccrualAndReminderIsSentOnce() {
        Seed late = seedActive(LocalDate.now(VN).minusDays(10));
        ReturnRequestView request = tenancy.requestReturn(late.contractId, customerId, LocalDate.now(VN), null);
        jdbc.update("update return_requests set created_at = now() - interval '5 days' where id = ?", request.id());
        overdue.accrue(late.contractId);
        // Dừng tính ở ngày gửi yêu cầu: 5 ngày quá hạn − 3 ân hạn = 2 ngày.
        assertThat(openOverdueAmount(late.contractId)).isEqualByComparingTo("40000");

        Seed soon = seedActive(LocalDate.now(VN).plusDays(3));
        assertThat(overdue.expiringIds()).contains(soon.contractId);
        assertThat(overdue.remindExpiring(soon.contractId)).isTrue();
        assertThat(overdue.remindExpiring(soon.contractId)).isFalse();
        assertThat(notifications(CUSTOMER, "CONTRACT_EXPIRING_SOON", soon.orderId)).isEqualTo(1);
    }

    @Test
    void tenancyAndCheckoutGuards() {
        Seed seed = seedActive(LocalDate.now(VN).plusMonths(1));
        assertThatThrownBy(() -> tenancy.requestExtension(seed.contractId, customerId, 13))
                .isInstanceOf(TenancyException.class);
        assertThatThrownBy(() -> tenancy.requestReturn(seed.contractId, customerId, LocalDate.now(VN).plusDays(31),
                null)).isInstanceOf(TenancyException.class);
        assertThatThrownBy(() -> tenancy.requestReturn(seed.contractId, customerId, null, null))
                .isInstanceOf(TenancyException.class);
        assertThatThrownBy(() -> tenancy.requestReturn(seed.contractId, customerId, LocalDate.now(VN),
                "x".repeat(1001))).isInstanceOf(TenancyException.class);
        assertThatThrownBy(() -> tenancy.cancelReturn(-1L, customerId)).isInstanceOf(TenancyException.class);
        loginAs(FM);
        assertThatThrownBy(() -> tenancy.assign(-1L, fsId)).isInstanceOf(TenancyException.class);
        assertThatThrownBy(() -> tenancy.assign(-1L, null)).isInstanceOf(TenancyException.class);

        ReturnRequestView request = assignedAndArrived(seed);
        loginAs(FS);
        assertThatThrownBy(() -> checkout.inspect(request.id(), new Inspection(true, false, null, null, -1,
                List.of(), null))).isInstanceOf(TenancyException.class);
        assertThatThrownBy(() -> checkout.inspect(request.id(), new Inspection(true, false, null, null, 1,
                java.util.Collections.nCopies(21, "u"), null))).isInstanceOf(TenancyException.class);
        assertThatThrownBy(() -> tenancy.cancelReturn(request.id(), customerId))
                .isInstanceOf(TenancyException.class).hasMessageContaining("đã bắt đầu");
        loginAs(FM);
        assertThatThrownBy(() -> tenancy.assign(request.id(), fsId))
                .isInstanceOf(TenancyException.class).hasMessageContaining("đã bắt đầu");
        assertThatThrownBy(() -> checkout.forStaff(-1L)).isInstanceOf(TenancyException.class);
        checkout.onShortfallPaid(-1L);

        loginAs(FS);
        checkout.inspect(request.id(), new Inspection(true, false, null, null, 2, List.of(), null));
        checkout.confirm(request.id());
        assertThatThrownBy(() -> checkout.inspect(request.id(), new Inspection(true, false, null, null, 2,
                List.of(), null))).isInstanceOf(TenancyException.class);
        assertThatThrownBy(() -> tenancy.requestExtension(seed.contractId, customerId, 1))
                .isInstanceOf(TenancyException.class);
        assertThat(tenancy.rentals(customerId)).extracting(RentalView::contractId).doesNotContain(seed.contractId);
        overdue.accrue(-1L);
        assertThat(overdue.remindExpiring(seed.contractId)).isFalse();
    }

    // ------------------------------------------------------------------ helpers

    private record Seed(long unitId, long orderId, long contractId, LocalDate endDate) {
    }

    /** Hợp đồng {@code Active}: khoang Rented, đơn Done, cọc 500.000, giá 100.000/tháng, 2 chìa + mã số. */
    private Seed seedActive(LocalDate endDate) {
        int n = SEQ.incrementAndGet();
        String suffix = n + "-" + System.nanoTime() % 100000;
        long unitId = id("insert into storage_units (code, facility_id, unit_type_id, status) values ('R-" + suffix
                + "', " + facilityId + ", (select id from unit_types where name = 'Small'), 'Rented') returning id");
        long requestId = id("insert into rental_requests (facility_id, customer_name, normalized_customer_email, "
                + "customer_phone, unit_type_id, start_date, period, status, unit_id) values (" + facilityId
                + ", 'Khách Demo', '" + CUSTOMER + "', '0900000000', (select id from unit_types where name = "
                + "'Small'), current_date, 3, 'Converted', " + unitId + ") returning id");
        long orderId = id("insert into rental_orders (request_id, customer_id, unit_id, status, expires_at) values ("
                + requestId + ", " + customerId + ", " + unitId + ", 'Done', now() + interval '20 days') returning id");
        long contractId = id("insert into rental_contracts (order_id, customer_id, unit_id, code, terms_version, "
                + "monthly_price, deposit_amount, period, start_date, end_date, signed_at, status) values (" + orderId
                + ", " + customerId + ", " + unitId + ", 'HD-T-" + suffix + "', 'builtin-v1', 100000, 500000, 3, '"
                + endDate.minusMonths(3) + "', '" + endDate + "', now(), 'Active') returning id");
        jdbc.update("insert into unit_access_keys (unit_id, contract_id, access_type, quantity) values (?, ?, "
                + "'PhysicalKey', 2)", unitId, contractId);
        jdbc.update("insert into unit_access_keys (unit_id, contract_id, access_type, code_hash) values (?, ?, "
                + "'AccessCode', 'hash')", unitId, contractId);
        return new Seed(unitId, orderId, contractId, endDate);
    }

    /** Yêu cầu trả kho → FM phân công fs1 → FS xác nhận khách đến. */
    private ReturnRequestView assignedAndArrived(Seed seed) {
        ReturnRequestView request = tenancy.requestReturn(seed.contractId, customerId, LocalDate.now(VN).plusDays(1),
                null);
        loginAs(FM);
        ReturnRequestView assigned = tenancy.assign(request.id(), fsId);
        loginAs(FS);
        staffActions.arrive(assigned.appointmentId());
        return assigned;
    }

    private void pay(ContractBill bill) {
        assertThat(invoicePaymentService.start(bill.invoiceId(), customerId, "127.0.0.1").rejected()).isFalse();
        String ref = jdbc.queryForObject("select vnp_txn_ref from payment_transactions where invoice_id = ? "
                + "order by id desc limit 1", String.class, bill.invoiceId());
        Map<String, String> ipn = new HashMap<>();
        ipn.put("vnp_TxnRef", ref);
        ipn.put("vnp_TmnCode", "TESTTMN1");
        ipn.put("vnp_Amount", bill.amount().multiply(BigDecimal.valueOf(100)).toBigInteger().toString());
        ipn.put("vnp_ResponseCode", "00");
        ipn.put("vnp_TransactionStatus", "00");
        ipn.put("vnp_TransactionNo", "PR" + SEQ.incrementAndGet() + System.nanoTime() % 100000);
        ipn.put("vnp_PayDate", LocalDateTime.now(VN).format(PAY_DATE));
        ipn.put("vnp_SecureHash", "mocked");
        assertThat(paymentService.handleIpn(ipn).rspCode()).isEqualTo("00");
        assertThat(invoiceStatus(bill.invoiceId())).isEqualTo("Paid");
    }

    private BigDecimal openOverdueAmount(long contractId) {
        return jdbc.queryForObject("select amount from invoices where contract_id = ? and code like 'INV-OVD-%' "
                + "and status = 'Unpaid'", BigDecimal.class, contractId);
    }

    private long id(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private int auditCount(String action, String entityType, long entityId) {
        return count("select count(*) from audit_logs where action = ? and entity_type = ? and entity_id = ?",
                action, entityType, String.valueOf(entityId));
    }

    private int notifications(String email, String type, long orderId) {
        return count("select count(*) from notifications n join accounts a on a.id = n.account_id "
                + "join order_notifications o on o.notification_id = n.id "
                + "where a.email = ? and n.type = ? and o.order_id = ?", email, type, orderId);
    }

    private String contractStatus(long id) {
        return jdbc.queryForObject("select status from rental_contracts where id = ?", String.class, id);
    }

    private String unitStatus(long id) {
        return jdbc.queryForObject("select status from storage_units where id = ?", String.class, id);
    }

    private String invoiceStatus(long id) {
        return jdbc.queryForObject("select status from invoices where id = ?", String.class, id);
    }

    private String appointmentStatus(long id) {
        return jdbc.queryForObject("select status from appointments where id = ?", String.class, id);
    }

    private static void loginAs(String email) {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(email, null, List.of()));
    }
}
