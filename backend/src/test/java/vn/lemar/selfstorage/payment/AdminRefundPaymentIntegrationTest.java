package vn.lemar.selfstorage.payment;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@Transactional
@TestPropertySource(properties = {
        "security.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
})
class AdminRefundPaymentIntegrationTest {

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

    @BeforeEach
    void seedRefundRequiredInvoice() {
        Long requestId = jdbc.queryForObject("""
                insert into rental_requests (facility_id, customer_name, normalized_customer_email,
                    customer_phone, unit_type_id, start_date, period, status)
                values (1, 'Khách refund', 'customer1@lemar.vn', '0901234567', 1, current_date, 6, 'Converted')
                returning id
                """, Long.class);
        Long orderId = jdbc.queryForObject("""
                insert into rental_orders (request_id, customer_id, unit_id, status)
                values (?, 6, 1, 'Deposited') returning id
                """, Long.class, requestId);
        Long invoiceId = jdbc.queryForObject("""
                insert into invoices (order_id, customer_id, code, title, type, status, amount,
                    refund_status, refund_reason, refund_requested_at, created_at, due_date)
                values (?, 6, 'INV-ADMIN-REFUND', 'Đặt cọc', 'Deposit', 'Paid', 500000,
                    'Required', 'UNIT_UNAVAILABLE', now() - interval '5 minutes',
                    now() - interval '30 minutes', now() + interval '2 days')
                returning id
                """, Long.class, orderId);
        jdbc.update("""
                insert into payment_transactions (invoice_id, vnp_txn_ref, amount, direction, status,
                    gateway_transaction_no, paid_at, created_at)
                values (?, 'TXN-ADMIN-REFUND', 500000, 'PAY', 'Success', 'GW-REFUND-1',
                    now() - interval '29 minutes', now() - interval '30 minutes')
                """, invoiceId);
    }

    @Test
    void adminSeesRefundRequiredInvoice() throws Exception {
        mockMvc.perform(get("/api/admin/payments/refunds")
                        .header(HttpHeaders.AUTHORIZATION, bearerOf("admin@lemar.vn")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Lấy danh sách khoản cần hoàn tiền thành công"))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].invoiceCode").value("INV-ADMIN-REFUND"))
                .andExpect(jsonPath("$.data.items[0].refundStatus").value("Required"))
                .andExpect(jsonPath("$.data.items[0].refundReason").value("UNIT_UNAVAILABLE"))
                .andExpect(jsonPath("$.data.items[0].vnpTxnRef").value("TXN-ADMIN-REFUND"))
                .andExpect(jsonPath("$.data.items[0].gatewayTransactionNo").value("GW-REFUND-1"))
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    void refundDoesNotLeakIntoPendingQueue() throws Exception {
        mockMvc.perform(get("/api/admin/payments/pending")
                        .header(HttpHeaders.AUTHORIZATION, bearerOf("admin@lemar.vn")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(0))
                .andExpect(jsonPath("$.data.totalElements").value(0));
    }

    @Test
    void invalidRefundPagingIsRejected() throws Exception {
        mockMvc.perform(get("/api/admin/payments/refunds")
                        .param("page", "0")
                        .param("size", "0")
                        .header(HttpHeaders.AUTHORIZATION, bearerOf("admin@lemar.vn")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void nonAdminCannotSeeRefundQueue() throws Exception {
        mockMvc.perform(get("/api/admin/payments/refunds")
                        .header(HttpHeaders.AUTHORIZATION, bearerOf("fm1@lemar.vn")))
                .andExpect(status().isForbidden());
    }

    @Test
    void fmSeesRefundRequiredForOwnFacility() throws Exception {
        mockMvc.perform(get("/api/fm/invoices/refunds")
                        .param("facilityId", "1")
                        .header(HttpHeaders.AUTHORIZATION, bearerOf("fm1@lemar.vn")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].invoiceCode").value("INV-ADMIN-REFUND"));
    }

    @Test
    void fmOfOtherFacilityCannotSeeRefundQueue() throws Exception {
        mockMvc.perform(get("/api/fm/invoices/refunds")
                        .param("facilityId", "1")
                        .header(HttpHeaders.AUTHORIZATION, bearerOf("fm2@lemar.vn")))
                .andExpect(status().isForbidden());
    }

    @Test
    void customerCannotSeeFmRefundQueue() throws Exception {
        mockMvc.perform(get("/api/fm/invoices/refunds")
                        .param("facilityId", "1")
                        .header(HttpHeaders.AUTHORIZATION, bearerOf("customer1@lemar.vn")))
                .andExpect(status().isForbidden());
    }

    private String bearerOf(String email) {
        Account account = accounts.findByEmail(email).orElseThrow();
        return "Bearer " + jwtService.issueAccessToken(account);
    }
}
