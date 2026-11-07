package vn.lemar.selfstorage.overdue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
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
import vn.lemar.selfstorage.booking.application.ManualRefundService;
import vn.lemar.selfstorage.booking.application.exception.BookingConflictException;
import vn.lemar.selfstorage.booking.application.exception.BookingValidationException;
import vn.lemar.selfstorage.handover.application.HandoverWorkflowService;
import vn.lemar.selfstorage.overdue.application.OverdueService;
import vn.lemar.selfstorage.overdue.application.RentBillingService;
import vn.lemar.selfstorage.payment.application.PaymentGateway;

/**
 * Thu tiền thuê định kỳ, tạm khoá truy cập khi quá hạn kéo dài, FM ghi nhận hoàn tiền — chạy thật trên Postgres.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class RentBillingAndRefundIntegrationTest {

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final String CUSTOMER = "customer1@lemar.vn";
    private static final String FM = "fm1@lemar.vn";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @MockBean
    private PaymentGateway gateway;
    @MockBean
    private JavaMailSender mailSender;

    @Autowired
    private RentBillingService rentBilling;
    @Autowired
    private OverdueService overdue;
    @Autowired
    private ManualRefundService refunds;
    @Autowired
    private HandoverWorkflowService handoverWorkflow;
    @Autowired
    private JdbcTemplate jdbc;

    private long customerId;
    private long facilityId;

    @BeforeEach
    void setUp() {
        customerId = id("select id from accounts where email = '" + CUSTOMER + "'");
        facilityId = id("select id from facilities where code = 'Q7'");
        when(mailSender.createMimeMessage()).thenReturn(
                new jakarta.mail.internet.MimeMessage(jakarta.mail.Session.getInstance(new java.util.Properties())));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------------ tiền thuê định kỳ

    @Test
    void nextMonthRentIsBilledWithinLeadDaysOnlyOnce() {
        LocalDate start = LocalDate.now(VN).minusMonths(1).plusDays(3);
        Seed seed = seedActive(start, 3);
        invoice(seed, "Rental", "RNT0", "Paid", "100000");

        assertThat(rentBilling.bill(seed.contractId)).isTrue();
        assertThat(rentBilling.bill(seed.contractId)).isFalse();

        assertThat(count("select count(*) from invoices where contract_id = ? and code like 'INV-RNT-%' "
                + "and type = 'Rental' and status = 'Unpaid' and amount = 100000", seed.contractId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select (due_date at time zone 'Asia/Ho_Chi_Minh')::date from invoices "
                + "where contract_id = ? and code like 'INV-RNT-%'", LocalDate.class, seed.contractId))
                .isEqualTo(start.plusMonths(1));
        assertThat(count("select count(*) from notifications n join accounts a on a.id = n.account_id "
                + "join order_notifications o on o.notification_id = n.id "
                + "where a.email = ? and n.type = 'RENT_INVOICE_ISSUED' and o.order_id = ?", CUSTOMER,
                seed.orderId)).isEqualTo(1);
    }

    @Test
    void rentIsNotBilledBeforeLeadDays() {
        Seed seed = seedActive(LocalDate.now(VN).minusDays(5), 3);
        invoice(seed, "Rental", "RNT0", "Paid", "100000");

        assertThat(rentBilling.bill(seed.contractId)).isFalse();
    }

    @Test
    void paidExtensionCoversTheTailNotTheNextMonth() {
        Seed seed = seedActive(LocalDate.now(VN).minusMonths(1).plusDays(2), 5);
        invoice(seed, "Rental", "RNT0", "Paid", "100000");
        invoice(seed, "Extension", "EXT0", "Paid", "200000");

        assertThat(rentBilling.bill(seed.contractId)).isTrue();
        // Kỳ gốc 3 tháng: tháng 2 vừa lập; tháng 3 chưa tới hạn; tháng 4–5 đã trả trước qua gia hạn.
        jdbc.update("update invoices set status = 'Paid' where contract_id = ? and code like 'INV-RNT-%'",
                seed.contractId);
        jdbc.update("update rental_contracts set start_date = start_date - interval '1 month' where id = ?",
                seed.contractId);
        assertThat(rentBilling.bill(seed.contractId)).isTrue();
        jdbc.update("update invoices set status = 'Paid' where contract_id = ? and code like 'INV-RNT-%'",
                seed.contractId);
        jdbc.update("update rental_contracts set start_date = start_date - interval '1 month' where id = ?",
                seed.contractId);
        assertThat(rentBilling.bill(seed.contractId)).isFalse();
        assertThat(count("select count(*) from invoices where contract_id = ? and code like 'INV-RNT-%'",
                seed.contractId)).isEqualTo(2);
    }

    // ------------------------------------------------------------------ quá hạn kéo dài

    @Test
    void longOverdueSuspendsAccessCodeThenRestoresAfterExtension() {
        LocalDate end = LocalDate.now(VN).minusDays(31);
        Seed seed = seedActive(end.minusMonths(3), 3);

        overdue.accrue(seed.contractId);

        assertThat(jdbc.queryForObject("select access_suspended_at is not null from rental_contracts where id = ?",
                Boolean.class, seed.contractId)).isTrue();
        assertThat(jdbc.queryForObject("select status from unit_access_keys where contract_id = ? "
                + "and access_type = 'AccessCode'", String.class, seed.contractId)).isNotEqualTo("Active");
        assertThat(count("select count(*) from audit_logs where action = 'CONTRACT_ACCESS_SUSPENDED' "
                + "and entity_id = ?", String.valueOf(seed.contractId))).isEqualTo(1);
        overdue.accrue(seed.contractId);
        assertThat(count("select count(*) from audit_logs where action = 'CONTRACT_ACCESS_SUSPENDED' "
                + "and entity_id = ?", String.valueOf(seed.contractId))).isEqualTo(1);

        assertThatThrownBy(() -> handoverWorkflow.rotateAccessCode(seed.contractId, customerId))
                .hasMessageContaining("tạm khoá");
        assertThat(overdue.restoreIfCleared(seed.contractId)).isFalse();

        jdbc.update("update rental_contracts set end_date = current_date + 30 where id = ?", seed.contractId);
        jdbc.update("update invoices set status = 'Canceled' where contract_id = ? and code like 'INV-OVD-%'",
                seed.contractId);
        assertThat(overdue.suspendedIds()).contains(seed.contractId);
        assertThat(overdue.restoreIfCleared(seed.contractId)).isTrue();
        assertThat(jdbc.queryForObject("select access_suspended_at is null from rental_contracts where id = ?",
                Boolean.class, seed.contractId)).isTrue();
        assertThat(count("select count(*) from audit_logs where action = 'CONTRACT_ACCESS_RESTORED' "
                + "and entity_id = ?", String.valueOf(seed.contractId))).isEqualTo(1);
    }

    // ------------------------------------------------------------------ hoàn tiền thủ công

    @Test
    void fmRecordsRefundUpToTheAmountCollected() {
        Seed seed = seedActive(LocalDate.now(VN).minusDays(5), 3);
        long invoiceId = invoice(seed, "Rental", "RNT0", "Paid", "100000");
        jdbc.update("insert into payment_transactions (invoice_id, vnp_txn_ref, amount, direction, status, paid_at) "
                + "values (?, ?, 100000, 'PAY', 'Success', now())", invoiceId, "PAYT" + System.nanoTime());
        loginAs(FM);

        assertThatThrownBy(() -> refunds.record(invoiceId, new BigDecimal("1000"), " "))
                .isInstanceOf(BookingValidationException.class);
        ManualRefundService.RefundSummary after = refunds.record(invoiceId, new BigDecimal("40000"), "CK hoàn 001");

        assertThat(after.refundable()).isEqualByComparingTo("60000");
        assertThat(after.refunds()).hasSize(1);
        assertThatThrownBy(() -> refunds.record(invoiceId, new BigDecimal("70000"), "CK hoàn 002"))
                .isInstanceOf(BookingConflictException.class);
        assertThat(count("select count(*) from payment_transactions where invoice_id = ? and direction = 'REFUND' "
                + "and status = 'Success'", invoiceId)).isEqualTo(1);
        assertThat(count("select count(*) from audit_logs where action = 'MANUAL_REFUND_RECORDED'")).isPositive();
    }

    // ------------------------------------------------------------------ helpers

    private record Seed(long unitId, long orderId, long contractId) {
    }

    /** Hợp đồng {@code Active} giá 100.000/tháng, {@code period} tháng từ {@code start}, có mã khoá số. */
    private Seed seedActive(LocalDate start, int period) {
        String suffix = SEQ.incrementAndGet() + "-" + System.nanoTime() % 100000;
        long unitId = id("insert into storage_units (code, facility_id, unit_type_id, status) values ('RB-" + suffix
                + "', " + facilityId + ", (select id from unit_types where name = 'Small'), 'Rented') returning id");
        long requestId = id("insert into rental_requests (facility_id, customer_name, normalized_customer_email, "
                + "customer_phone, unit_type_id, start_date, period, status, unit_id) values (" + facilityId
                + ", 'Khách Demo', '" + CUSTOMER + "', '0900000000', (select id from unit_types where name = "
                + "'Small'), current_date, 3, 'Converted', " + unitId + ") returning id");
        long orderId = id("insert into rental_orders (request_id, customer_id, unit_id, status, expires_at) values ("
                + requestId + ", " + customerId + ", " + unitId + ", 'Done', now() + interval '20 days') returning id");
        long contractId = id("insert into rental_contracts (order_id, customer_id, unit_id, code, terms_version, "
                + "monthly_price, deposit_amount, period, start_date, end_date, signed_at, status) values (" + orderId
                + ", " + customerId + ", " + unitId + ", 'HD-RB-" + suffix + "', 'builtin-v1', 100000, 500000, "
                + period + ", '" + start + "', '" + start.plusMonths(period).minusDays(1) + "', now(), 'Active') "
                + "returning id");
        jdbc.update("insert into unit_access_keys (unit_id, contract_id, access_type, code_hash) values (?, ?, "
                + "'AccessCode', 'hash')", unitId, contractId);
        return new Seed(unitId, orderId, contractId);
    }

    private long invoice(Seed seed, String type, String kind, String status, String amount) {
        return id("insert into invoices (contract_id, customer_id, code, title, type, status, amount, due_date) "
                + "values (" + seed.contractId + ", " + customerId + ", 'INV-" + kind + "-T-" + System.nanoTime()
                + "', 'Test', '" + type + "', '" + status + "', " + amount + ", now()) returning id");
    }

    private long id(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private static void loginAs(String email) {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(email, null, List.of()));
    }
}
