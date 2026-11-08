package vn.lemar.selfstorage.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
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
    private vn.lemar.selfstorage.booking.application.ContractInvoiceService contractInvoices;
    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;
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
        // Hoá đơn gia hạn đang chờ → chưa được gửi yêu cầu trả kho (hợp đồng không được cộng kỳ khi đang trả kho).
        assertThat(tenancy.rental(seed.contractId, customerId).canRequestReturn()).isFalse();
        assertThatThrownBy(() -> tenancy.requestReturn(seed.contractId, customerId, LocalDate.now(VN).plusDays(1),
                null)).isInstanceOf(TenancyException.class).hasMessageContaining("hoá đơn gia hạn");

        pay(bill);
        assertThat(tenancy.rental(seed.contractId, customerId).canRequestReturn()).isTrue();

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

    @Test
    void missedReturnAppointmentGoesBackToPendingThenCancelsAtThreshold() {
        Seed seed = seedActive(LocalDate.now(VN).plusMonths(1));
        ReturnRequestView request = tenancy.requestReturn(seed.contractId, customerId, LocalDate.now(VN).plusDays(1),
                null);
        loginAs(FM);
        ReturnRequestView first = tenancy.assign(request.id(), fsId);
        assertThat(tenancy.missedReturnAppointmentIds()).doesNotContain(first.appointmentId());

        moveAppointment(first.appointmentId(), LocalDate.now(VN).minusDays(1));
        assertThat(tenancy.missedReturnAppointmentIds()).contains(first.appointmentId());
        assertThat(tenancy.handleReturnNoShow(first.appointmentId())).isTrue();
        assertThat(tenancy.handleReturnNoShow(first.appointmentId())).isFalse();

        assertThat(appointmentStatus(first.appointmentId())).isEqualTo("Canceled");
        assertThat(jdbc.queryForObject("select status || ':' || no_show_count || ':' || (appointment_id is null) "
                + "from return_requests where id = ?", String.class, request.id())).isEqualTo("Pending:1:true");
        assertThat(auditCount("RETURN_NO_SHOW", "ReturnRequest", request.id())).isEqualTo(1);
        assertThat(auditCount("APPOINTMENT_CANCELED_NO_SHOW", "Appointment", first.appointmentId())).isEqualTo(1);
        assertThat(notifications(CUSTOMER, "RETURN_NO_SHOW", seed.orderId)).isEqualTo(1);
        assertThat(notifications(FM, "RETURN_NO_SHOW", seed.orderId)).isEqualTo(1);

        // Lần thứ hai (return.max_no_shows = 2) → huỷ yêu cầu trả kho.
        ReturnRequestView second = tenancy.assign(request.id(), fsId);
        moveAppointment(second.appointmentId(), LocalDate.now(VN).minusDays(1));
        assertThat(tenancy.handleReturnNoShow(second.appointmentId())).isTrue();
        assertThat(jdbc.queryForObject("select status || ':' || no_show_count || ':' || cancel_reason "
                + "from return_requests where id = ?", String.class, request.id()))
                .isEqualTo("Canceled:2:Khách không đến buổi trả kho 2 lần");
        assertThat(tenancy.openReturnOf(seed.contractId)).isEmpty();
        assertThat(tenancy.rental(seed.contractId, customerId).canRequestReturn()).isTrue();
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
        ContractBill rent = unpaidRent(seed);
        ReturnRequestView request = assignedAndArrived(seed);

        loginAs(FS);
        checkout.inspect(request.id(), new Inspection(true, true, new BigDecimal("100000"), "Trầy cửa", 1,
                List.of("https://img.test/r.jpg"), null));
        CheckoutView view = checkout.forStaff(request.id());
        assertThat(view.unpaidContractInvoices()).isEqualByComparingTo("100000");
        assertThat(view.keysIssued()).isEqualTo(2);
        CheckoutView done = checkout.confirm(request.id());

        // 100.000 vệ sinh + 100.000 hư hỏng + 200.000 mất 1 chìa + 100.000 tiền thuê chưa trả = 500.000 = cọc.
        assertThat(done.totalCharges()).isEqualByComparingTo("500000");
        assertThat(done.refundAmount()).isEqualByComparingTo("0");
        assertThat(done.result()).isEqualTo("COMPLETED");
        assertThat(invoiceStatus(rent.invoiceId())).isEqualTo("Canceled");
        // Quyết toán tách mỗi loại phí một hoá đơn Penalty (trước đây gộp một INV-PEN 500.000), cọc phủ hết → Paid.
        assertThat(paidPenalty(seed.contractId, "CLN")).isEqualByComparingTo("100000");
        assertThat(paidPenalty(seed.contractId, "DMG")).isEqualByComparingTo("100000");
        assertThat(paidPenalty(seed.contractId, "KEY")).isEqualByComparingTo("200000");
        assertThat(paidPenalty(seed.contractId, "PEN")).isEqualByComparingTo("100000");
        assertThat(count("select count(*) from unit_access_keys where contract_id = ? and status = 'Lost'",
                seed.contractId)).isEqualTo(1);
        assertThat(notifications(FM, "CHECKOUT_REFUND_REQUIRED", seed.orderId)).isZero();
        // Mất chìa, hoàn tất ngay lúc chốt: bảo trì thủ công (không có mốc tự mở lại).
        assertThat(unitStatus(seed.unitId)).isEqualTo("Maintenance");
        assertThat(maintenanceStartedAtIsNull(seed.unitId)).isTrue();
    }

    @Test
    void penaltiesAreSplitPerCategoryAndUncoveredPartGoesIntoOneShortfall() {
        Seed seed = seedActive(LocalDate.now(VN).plusMonths(1));
        unpaidRent(seed);
        ReturnRequestView request = assignedAndArrived(seed);

        loginAs(FS);
        checkout.inspect(request.id(), new Inspection(true, true, new BigDecimal("300000"), "Móp vách", 1,
                List.of(), null));
        CheckoutView awaiting = checkout.confirm(request.id());

        // 100.000 vệ sinh + 300.000 hư hỏng + 200.000 mất 1 chìa + 100.000 tiền thuê chưa trả = 700.000; cọc 500.000
        // phủ lần lượt CLN (100.000) → DMG (300.000) → KEY (100.000/200.000); còn thiếu 200.000 vào một INV-PEN.
        assertThat(awaiting.result()).isEqualTo("AWAITING_PAYMENT");
        assertThat(awaiting.totalCharges()).isEqualByComparingTo("700000");
        assertThat(paidPenalty(seed.contractId, "CLN")).isEqualByComparingTo("100000");
        assertThat(paidPenalty(seed.contractId, "DMG")).isEqualByComparingTo("300000");
        assertThat(paidPenalty(seed.contractId, "KEY")).isEqualByComparingTo("100000");
        assertThat(paidPenalty(seed.contractId, "PEN")).isEqualByComparingTo("0");
        assertThat(awaiting.shortfallInvoice().code()).startsWith("INV-PEN-Q7-");
        assertThat(awaiting.shortfallInvoice().status()).isEqualTo("Unpaid");
        assertThat(awaiting.shortfallInvoice().amount()).isEqualByComparingTo("200000");
        assertThat(count("select count(*) from invoices where contract_id = ? and type = 'Penalty'", seed.contractId))
                .isEqualTo(4);
        assertThat(count("select count(*) from audit_logs a join invoices i on a.entity_id = i.id::text "
                + "where a.entity_type = 'Invoice' and a.action = 'INVOICE_PAID' and i.contract_id = ?",
                seed.contractId)).isEqualTo(3);

        pay(awaiting.shortfallInvoice());

        // Hoàn tất khi trả phần thiếu: mất chìa → bảo trì thủ công tới khi thay khoá, cron không tự mở lại.
        assertThat(contractStatus(seed.contractId)).isEqualTo("Ended");
        assertThat(unitStatus(seed.unitId)).isEqualTo("Maintenance");
        assertThat(maintenanceStartedAtIsNull(seed.unitId)).isTrue();
        assertThat(count("select count(*) from audit_logs where action = 'STORAGE_UNIT_MAINTENANCE' and entity_type = "
                + "'StorageUnit' and entity_id = ? and new_value ->> 'reason' = ?", String.valueOf(seed.unitId),
                "Bảo trì sau trả kho — thay khoá do mất chìa")).isEqualTo(1);
        checkoutJobs.reopenUnits();
        assertThat(unitStatus(seed.unitId)).isEqualTo("Maintenance");
    }

    @Test
    void returnDateShiftedWhenPreferredDayIsFullNotifiesCustomer() {
        Seed seed = seedActive(LocalDate.now(VN).plusMonths(2));
        LocalDate preferred = LocalDate.now(VN).plusDays(25);
        for (LocalTime start : List.of(LocalTime.of(8, 0), LocalTime.of(13, 0), LocalTime.of(17, 0))) {
            jdbc.update("insert into appointments (customer_id, facility_id, staff_id, type, date, started_at, end_at, "
                    + "status) values (?, ?, ?, 'RETURN', ?, ?, ?, 'Pending')", customerId, facilityId, fsId,
                    preferred, timestamp(preferred, start), timestamp(preferred, start.plusHours(2)));
        }
        ReturnRequestView request = tenancy.requestReturn(seed.contractId, customerId, preferred, null);
        loginAs(FM);
        ReturnRequestView assigned = tenancy.assign(request.id(), fsId);

        assertThat(assigned.appointmentStart().atZone(VN).toLocalDate()).isAfter(preferred);
        assertThat(notifications(CUSTOMER, "RETURN_DATE_SHIFTED", seed.orderId)).isEqualTo(1);
        assertThat(notifications(CUSTOMER, "RETURN_SCHEDULED", seed.orderId)).isEqualTo(1);
        String unitCode = jdbc.queryForObject("select code from storage_units where id = ?", String.class,
                seed.unitId);
        String body = jdbc.queryForObject("select n.body from notifications n join order_notifications o on "
                + "o.notification_id = n.id where n.type = 'RETURN_DATE_SHIFTED' and o.order_id = ?", String.class,
                seed.orderId);
        assertThat(body).contains(unitCode)
                .contains(DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy").withZone(VN)
                        .format(assigned.appointmentStart()));
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
        // Quy tắc mới: gửi yêu cầu không còn dừng tính phí (trước đây 40.000) — chưa đến buổi trả kho nên tính tới
        // hôm nay: 10 ngày quá hạn − 3 ân hạn = 7 ngày.
        assertThat(openOverdueAmount(late.contractId)).isEqualByComparingTo("140000");

        Seed soon = seedActive(LocalDate.now(VN).plusDays(3));
        assertThat(overdue.expiringIds()).contains(soon.contractId);
        assertThat(overdue.remindExpiring(soon.contractId)).isTrue();
        assertThat(overdue.remindExpiring(soon.contractId)).isFalse();
        assertThat(notifications(CUSTOMER, "CONTRACT_EXPIRING_SOON", soon.orderId)).isEqualTo(1);
    }

    @Test
    void remindersRepeatOncePerContractTermAfterExtension() {
        Seed seed = seedActive(LocalDate.now(VN).plusDays(3));
        assertThat(overdue.remindExpiring(seed.contractId)).isTrue();
        // Nhắc đã gửi trước cửa sổ nhắc của kỳ mới (gia hạn 1 tháng) → kỳ mới nhắc lại một lần.
        jdbc.update("update notifications set created_at = now() - interval '10 days' where id in (select "
                + "notification_id from order_notifications where order_id = ?)", seed.orderId);
        pay(tenancy.requestExtension(seed.contractId, customerId, 1));
        jdbc.update("update rental_contracts set end_date = current_date + 2 where id = ?", seed.contractId);
        assertThat(overdue.remindExpiring(seed.contractId)).isTrue();
        assertThat(overdue.remindExpiring(seed.contractId)).isFalse();
        assertThat(notifications(CUSTOMER, "CONTRACT_EXPIRING_SOON", seed.orderId)).isEqualTo(2);

        jdbc.update("update rental_contracts set end_date = current_date - 5 where id = ?", seed.contractId);
        overdue.accrue(seed.contractId);
        overdue.accrue(seed.contractId);
        assertThat(notifications(CUSTOMER, "CONTRACT_OVERDUE", seed.orderId)).isEqualTo(1);
        // Kỳ quá hạn mới (end_date mới đã qua sau lần báo trước) → báo lại.
        jdbc.update("update notifications set created_at = now() - interval '10 days' where type = 'CONTRACT_OVERDUE' "
                + "and id in (select notification_id from order_notifications where order_id = ?)", seed.orderId);
        jdbc.update("update rental_contracts set end_date = current_date - 2 where id = ?", seed.contractId);
        overdue.accrue(seed.contractId);
        assertThat(notifications(CUSTOMER, "CONTRACT_OVERDUE", seed.orderId)).isEqualTo(2);
    }

    @Test
    void overdueStopsAtAttendedReturnAppointmentDateOtherwiseCountsToToday() {
        LocalDate today = LocalDate.now(VN);
        Seed attended = seedActive(today.minusDays(10));
        ReturnRequestView request = assignedAndArrived(attended);
        overdue.accrue(attended.contractId);
        // Khách đã đến nhưng buổi hẹn là hôm nay/tương lai: tính tới hôm nay, 10 − 3 ân hạn = 7 ngày.
        assertThat(openOverdueAmount(attended.contractId)).isEqualByComparingTo("140000");

        moveAppointment(request.appointmentId(), today.minusDays(4));
        overdue.accrue(attended.contractId);
        // Dừng ở ngày buổi trả kho khách đến: 6 ngày quá hạn − 3 ân hạn = 3 ngày.
        assertThat(openOverdueAmount(attended.contractId)).isEqualByComparingTo("60000");

        Seed missed = seedActive(today.minusDays(10));
        ReturnRequestView open = tenancy.requestReturn(missed.contractId, customerId, today.plusDays(1), null);
        loginAs(FM);
        ReturnRequestView scheduled = tenancy.assign(open.id(), fsId);
        moveAppointment(scheduled.appointmentId(), today.minusDays(4));
        overdue.accrue(missed.contractId);
        // Lịch đã qua mà khách không đến: vẫn tính tới hôm nay.
        assertThat(openOverdueAmount(missed.contractId)).isEqualByComparingTo("140000");
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

    /** Hoá đơn tiền thuê 100.000 {@code Unpaid} của hợp đồng — hoá đơn còn nợ được gộp khi quyết toán trả kho. */
    private ContractBill unpaidRent(Seed seed) {
        // issue() là Propagation.MANDATORY — caller production luôn đang trong transaction.
        return new org.springframework.transaction.support.TransactionTemplate(transactionManager).execute(status ->
                contractInvoices.issue(seed.orderId, seed.contractId, customerId, "Rental", "RNT", "Tiền thuê",
                "Tiền thuê tháng kế tiếp.", new BigDecimal("100000"), java.time.Instant.now().plusSeconds(86400),
                null));
    }

    /** Tổng hoá đơn Penalty {@code Paid} mã {@code INV-<kind>-…} của hợp đồng. */
    private BigDecimal paidPenalty(long contractId, String kind) {
        return jdbc.queryForObject("select coalesce(sum(amount), 0) from invoices where contract_id = ? "
                + "and type = 'Penalty' and status = 'Paid' and code like ?", BigDecimal.class, contractId,
                "INV-" + kind + "-%");
    }

    private Boolean maintenanceStartedAtIsNull(long unitId) {
        return jdbc.queryForObject("select maintenance_started_at is null from storage_units where id = ?",
                Boolean.class, unitId);
    }

    /** Dời lịch trả kho sang khung 08:00–10:00 của {@code date} (giờ VN). */
    private void moveAppointment(long appointmentId, LocalDate date) {
        jdbc.update("update appointments set date = ?, started_at = ?, end_at = ? where id = ?", date,
                timestamp(date, LocalTime.of(8, 0)), timestamp(date, LocalTime.of(10, 0)), appointmentId);
    }

    private static java.sql.Timestamp timestamp(LocalDate date, LocalTime time) {
        return java.sql.Timestamp.from(date.atTime(time).atZone(VN).toInstant());
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
