package vn.lemar.selfstorage.payment;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.lemar.selfstorage.identity.application.JwtService;
import vn.lemar.selfstorage.identity.domain.Account;
import vn.lemar.selfstorage.identity.repository.AccountRepository;
import vn.lemar.selfstorage.payment.application.PaymentReconciliationCaseService;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@Transactional
@TestPropertySource(properties = {
        "security.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
})
class AdminPendingPaymentIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private AccountRepository accounts;
    @Autowired
    private PaymentReconciliationCaseService cases;

    @BeforeEach
    void seedPendingPayment() {
        Long requestId = jdbc.queryForObject("""
                insert into rental_requests (facility_id, customer_name, normalized_customer_email,
                    customer_phone, unit_type_id, start_date, period, status)
                values (1, 'Khách pending', 'customer1@lemar.vn', '0901234567', 1, current_date, 6, 'Converted')
                returning id
                """, Long.class);
        Long orderId = jdbc.queryForObject("""
                insert into rental_orders (request_id, customer_id, unit_id, status)
                values (?, 6, 1, 'Deposited') returning id
                """, Long.class, requestId);
        Long invoiceId = jdbc.queryForObject("""
                insert into invoices (order_id, customer_id, code, title, type, status, amount,
                    created_at, due_date)
                values (?, 6, 'INV-ADMIN-PENDING', 'Đặt cọc', 'Deposit', 'Unpaid', 500000,
                    now() - interval '30 minutes', now() + interval '2 days')
                returning id
                """, Long.class, orderId);
        jdbc.update("""
                insert into payment_transactions (invoice_id, vnp_txn_ref, amount, direction, status,
                    created_at)
                values (?, 'TXN-ADMIN-PENDING', 500000, 'PAY', 'Pending', now() - interval '30 minutes')
                """, invoiceId);
        cases.createMissingCases(Instant.now(), 50);
    }

    @Test
    void adminSeesOpenPendingCase() throws Exception {
        Long caseId = jdbc.queryForObject("select id from payment_reconciliation_cases limit 1", Long.class);
        cases.markNotified(caseId);

        mockMvc.perform(get("/api/admin/payments/pending")
                        .param("olderThanMinutes", "15")
                        .header(HttpHeaders.AUTHORIZATION, bearerOf("admin@lemar.vn")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Lấy danh sách giao dịch Pending thành công"))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].vnpTxnRef").value("TXN-ADMIN-PENDING"))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items[0].pendingAgeMinutes")
                        .value(org.hamcrest.Matchers.greaterThanOrEqualTo(29)))
                .andExpect(jsonPath("$.data.items[0].notifiedAt").isNotEmpty());
    }

    @Test
    void invalidPagingIsRejected() throws Exception {
        mockMvc.perform(get("/api/admin/payments/pending")
                        .param("page", "0")
                        .param("size", "0")
                        .header(HttpHeaders.AUTHORIZATION, bearerOf("admin@lemar.vn")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void nonAdminCannotSeePendingCases() throws Exception {
        mockMvc.perform(get("/api/admin/payments/pending")
                        .header(HttpHeaders.AUTHORIZATION, bearerOf("fm1@lemar.vn")))
                .andExpect(status().isForbidden());
    }

    @Test
    void resolvedCaseLeavesTheOpenList() {
        Long transactionId = jdbc.queryForObject(
                "select id from payment_transactions where vnp_txn_ref = 'TXN-ADMIN-PENDING'", Long.class);
        cases.resolve(transactionId, "Failed", null, null, "portal says failed", null, "admin@lemar.vn");

        org.assertj.core.api.Assertions.assertThat(cases.listOpen(15, 0, 20).items()).isEmpty();
    }

    @Test
    void historyShowsResolvedCase() throws Exception {
        Long transactionId = jdbc.queryForObject(
                "select id from payment_transactions where vnp_txn_ref = 'TXN-ADMIN-PENDING'", Long.class);
        cases.resolve(transactionId, "Failed", null, null, "portal says failed", null, "admin@lemar.vn");

        mockMvc.perform(get("/api/admin/payments/reconciliation")
                        .param("view", "history")
                        .header(HttpHeaders.AUTHORIZATION, bearerOf("admin@lemar.vn")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].status").value("Resolved"))
                .andExpect(jsonPath("$.data.items[0].resolution").value("Failed"))
                .andExpect(jsonPath("$.data.items[0].note").value("portal says failed"))
                .andExpect(jsonPath("$.data.items[0].resolvedByEmail").value("admin@lemar.vn"))
                .andExpect(jsonPath("$.data.items[0].resolvedAt").isNotEmpty())
                .andExpect(jsonPath("$.data.totalElements").value(1));

        mockMvc.perform(get("/api/admin/payments/reconciliation")
                        .header(HttpHeaders.AUTHORIZATION, bearerOf("admin@lemar.vn")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(0));
    }

    @Test
    void invalidReconciliationViewIsRejected() throws Exception {
        mockMvc.perform(get("/api/admin/payments/reconciliation")
                        .param("view", "unknown")
                        .header(HttpHeaders.AUTHORIZATION, bearerOf("admin@lemar.vn")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void historyRouteWorksThroughLegacyPendingAlias() throws Exception {
        Long transactionId = jdbc.queryForObject(
                "select id from payment_transactions where vnp_txn_ref = 'TXN-ADMIN-PENDING'", Long.class);
        cases.resolve(transactionId, "Success", "GW-HISTORY", Instant.now(),
                "portal success", null, "admin@lemar.vn");

        mockMvc.perform(get("/api/admin/payments/pending")
                        .param("view", "history")
                        .header(HttpHeaders.AUTHORIZATION, bearerOf("admin@lemar.vn")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].status").value("Resolved"))
                .andExpect(jsonPath("$.data.items[0].resolution").value("Success"))
                .andExpect(jsonPath("$.data.items[0].gatewayTransactionNo").value("GW-HISTORY"));
    }

    private String bearerOf(String email) {
        Account account = accounts.findByEmail(email).orElseThrow();
        return "Bearer " + jwtService.issueAccessToken(account);
    }
}
