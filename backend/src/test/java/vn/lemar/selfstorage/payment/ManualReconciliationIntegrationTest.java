package vn.lemar.selfstorage.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import vn.lemar.selfstorage.notification.application.NotificationService;
import vn.lemar.selfstorage.payment.application.PaymentService;
import vn.lemar.selfstorage.payment.application.dto.ManualReconciliationOutcome;
import vn.lemar.selfstorage.payment.application.dto.ManualReconciliationRequest;

/**
 * Lớp đối soát thủ công: ops tra portal VNPay rồi nhập kết quả cho giao dịch Pending.
 *
 * <p>Test đi qua đúng endpoint ADMIN, khoá transaction row, cập nhật invoice/order/unit qua event
 * và ghi audit. Không dùng querydr — đây là lớp 3 của chiến lược IPN + local repair + manual portal.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class ManualReconciliationIntegrationTest {

    private static final String ADMIN_EMAIL = "admin@lemar.vn";
    private static final String CUSTOMER_EMAIL = "customer1@lemar.vn";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @MockBean
    private NotificationService notifications;

    private Long invoiceId;
    private Long orderId;
    private Long unitId;

    @BeforeEach
    void resetData() {
        jdbc.update("DELETE FROM order_notifications");
        jdbc.update("DELETE FROM notifications");
        jdbc.update("DELETE FROM audit_logs");
        jdbc.update("DELETE FROM payment_transactions");
        jdbc.update("DELETE FROM invoices");
        jdbc.update("DELETE FROM proposal_feedbacks");
        jdbc.update("DELETE FROM rental_orders");
        jdbc.update("DELETE FROM rental_requests");
        jdbc.update("UPDATE storage_units SET status = 'Available' WHERE status = 'Reserved'");
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();

        Long customerId = jdbc.queryForObject(
                "SELECT id FROM accounts WHERE email = ?", Long.class, CUSTOMER_EMAIL);
        unitId = jdbc.queryForObject(
                "SELECT id FROM storage_units WHERE status = 'Available' ORDER BY id LIMIT 1",
                Long.class);
        Long facilityId = jdbc.queryForObject(
                "SELECT facility_id FROM storage_units WHERE id = ?", Long.class, unitId);
        Long unitTypeId = jdbc.queryForObject("SELECT id FROM unit_types ORDER BY id LIMIT 1", Long.class);

        Long requestId = jdbc.queryForObject(
                "INSERT INTO rental_requests (facility_id, customer_name, normalized_customer_email,"
                        + " customer_phone, unit_type_id, start_date, period, status)"
                        + " VALUES (?, 'Nguyen Van A', ?, '0900000000', ?, CURRENT_DATE, 1, 'Approved')"
                        + " RETURNING id",
                Long.class, facilityId, CUSTOMER_EMAIL, unitTypeId);
        orderId = jdbc.queryForObject(
                "INSERT INTO rental_orders (request_id, customer_id, unit_id, status, expires_at)"
                        + " VALUES (?, ?, ?, 'Pending', now() + interval '3 days') RETURNING id",
                Long.class, requestId, customerId, unitId);
        invoiceId = jdbc.queryForObject(
                "INSERT INTO invoices (order_id, customer_id, code, title, type, status, amount, due_date)"
                        + " VALUES (?, ?, 'INV-MANUAL', 'Dat coc khoang chua', 'Deposit', 'Unpaid',"
                        + " 10000.00, now() + interval '3 days') RETURNING id",
                Long.class, orderId, customerId);
    }

    @Test
    void manualSuccessFinishesTheInvoiceLikeAnIpn() throws Exception {
        String txnRef = createPendingPayment("INV-MANUAL-SUCCESS");

        mockMvc.perform(reconcileAs(ADMIN_EMAIL, txnRef, """
                        {"outcome":"SUCCESS","note":"portal: vnp_TransactionNo 14777001",
                         "paidAt":"%s","gatewayTransactionNo":"14777001"}
                        """.formatted(Instant.now())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.txnRef").value(txnRef))
                .andExpect(jsonPath("$.data.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.gatewayTransactionNo").value("14777001"));

        assertThat(paymentStatus(txnRef)).isEqualTo("Success");
        assertThat(statusOf("invoices", invoiceId)).isEqualTo("Paid");
        assertThat(statusOf("rental_orders", orderId)).isEqualTo("Deposited");
        assertThat(statusOf("storage_units", unitId)).isEqualTo("Reserved");
        assertThat(jdbc.queryForList("SELECT action FROM audit_logs ORDER BY action", String.class))
                .containsExactly("INVOICE_PAID", "PAYMENT_SUCCEEDED", "RENTAL_ORDER_DEPOSITED",
                        "STORAGE_UNIT_RESERVED");
    }

    @Test
    void manualSuccessOnCanceledInvoiceStillRecordsPayment() throws Exception {
        jdbc.update("UPDATE invoices SET status = 'Canceled' WHERE id = ?", invoiceId);
        String txnRef = createPendingPayment("INV-MANUAL-CANCELED");

        mockMvc.perform(reconcileAs(ADMIN_EMAIL, txnRef, """
                        {"outcome":"SUCCESS","note":"portal: paid after cancel",
                         "paidAt":"%s","gatewayTransactionNo":"GW-CANCELED"}
                        """.formatted(Instant.now())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCESS"));

        assertThat(paymentStatus(txnRef)).isEqualTo("Success");
        assertThat(statusOf("invoices", invoiceId)).isEqualTo("Canceled");
    }

    @Test
    void manualSuccessOnExpiredInvoiceStillRecordsPayment() throws Exception {
        jdbc.update("UPDATE invoices SET status = 'Expired' WHERE id = ?", invoiceId);
        String txnRef = createPendingPayment("INV-MANUAL-EXPIRED");

        mockMvc.perform(reconcileAs(ADMIN_EMAIL, txnRef, """
                        {"outcome":"SUCCESS","note":"portal: paid after expiry",
                         "paidAt":"%s","gatewayTransactionNo":"GW-EXPIRED"}
                        """.formatted(Instant.now())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCESS"));

        assertThat(paymentStatus(txnRef)).isEqualTo("Success");
        assertThat(statusOf("invoices", invoiceId)).isEqualTo("Expired");
    }

    @Test
    void manualSuccessWithoutPaidAtIsRejected() throws Exception {
        String txnRef = createPendingPayment("INV-MANUAL-NO-PAIDAT");

        mockMvc.perform(reconcileAs(ADMIN_EMAIL, txnRef, """
                        {"outcome":"SUCCESS","note":"portal: paid",
                         "gatewayTransactionNo":"GW-NO-PAIDAT"}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("paidAt")));

        assertThat(paymentStatus(txnRef)).isEqualTo("Pending");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs", Integer.class)).isZero();
    }

    @Test
    void futurePaidAtIsRejected() throws Exception {
        String txnRef = createPendingPayment("INV-MANUAL-FUTURE");

        mockMvc.perform(reconcileAs(ADMIN_EMAIL, txnRef, """
                        {"outcome":"SUCCESS","note":"future","paidAt":"2999-01-01T00:00:00Z",
                         "gatewayTransactionNo":"GW-FUTURE"}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("tương lai")));

        assertThat(paymentStatus(txnRef)).isEqualTo("Pending");
    }

    @Test
    void paidAtBeforeTransactionCreationIsRejected() throws Exception {
        String txnRef = createPendingPayment("INV-MANUAL-PAST");

        mockMvc.perform(reconcileAs(ADMIN_EMAIL, txnRef, """
                        {"outcome":"SUCCESS","note":"past","paidAt":"2000-01-01T00:00:00Z",
                         "gatewayTransactionNo":"GW-PAST"}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("trước thời điểm tạo giao dịch")));

        assertThat(paymentStatus(txnRef)).isEqualTo("Pending");
    }

    @Test
    void manualSuccessWithoutGatewayTransactionNoIsRejected() throws Exception {
        String txnRef = createPendingPayment("INV-MANUAL-NO-GATEWAY");

        mockMvc.perform(reconcileAs(ADMIN_EMAIL, txnRef, """
                        {"outcome":"SUCCESS","note":"portal: paid",
                         "paidAt":"%s"}
                        """.formatted(Instant.now())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("gatewayTransactionNo")));

        assertThat(paymentStatus(txnRef)).isEqualTo("Pending");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs", Integer.class)).isZero();
    }

    @Test
    void invoiceWithExistingSuccessfulPaymentMarksDuplicate() throws Exception {
        String txnRef = createPendingPayment("INV-MANUAL-DOUBLE");
        jdbc.update("INSERT INTO payment_transactions"
                + " (invoice_id, vnp_txn_ref, amount, direction, status, paid_at)"
                + " VALUES (?, 'INV-MANUAL-OLD', 10000.00, 'PAY', 'Success', now())", invoiceId);

        mockMvc.perform(reconcileAs(ADMIN_EMAIL, txnRef, """
                        {"outcome":"SUCCESS","note":"portal: paid",
                         "paidAt":"%s","gatewayTransactionNo":"GW-NEW"}
                        """.formatted(Instant.now())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DUPLICATE"));

        assertThat(paymentStatus(txnRef)).isEqualTo("Duplicate");
        assertThat(statusOf("invoices", invoiceId)).isEqualTo("Unpaid");
        assertThat(jdbc.queryForList("SELECT action FROM audit_logs ORDER BY action", String.class))
                .containsExactly("PAYMENT_DUPLICATE");
    }

    @Test
    void duplicateGatewayTransactionNoIsAConflict() throws Exception {
        String txnRef = createPendingPayment("INV-MANUAL-DUP");
        jdbc.update("INSERT INTO payment_transactions"
                + " (invoice_id, vnp_txn_ref, amount, direction, status, gateway_transaction_no)"
                + " VALUES (?, 'INV-MANUAL-DUP-OTHER', 10000.00, 'PAY', 'Pending', 'GW-DUP')",
                invoiceId);

        mockMvc.perform(reconcileAs(ADMIN_EMAIL, txnRef, """
                        {"outcome":"SUCCESS","note":"portal: paid",
                         "paidAt":"%s","gatewayTransactionNo":"GW-DUP"}
                        """.formatted(Instant.now())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("đã được dùng cho giao dịch khác")));

        assertThat(paymentStatus(txnRef)).isEqualTo("Pending");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs", Integer.class)).isZero();
    }

    @Test
    void concurrentManualSuccessesOnSameInvoiceOnlyOneWins() throws Exception {
        String firstRef = createPendingPayment("INV-MANUAL-RACE-A");
        String secondRef = createPendingPayment("INV-MANUAL-RACE-B");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<String> first = pool.submit(() -> reconcile(firstRef, ready, start));
            Future<String> second = pool.submit(() -> reconcile(secondRef, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("SUCCESS", "DUPLICATE");
        } finally {
            pool.shutdownNow();
        }

        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_transactions"
                + " WHERE invoice_id = ? AND direction = 'PAY' AND status = 'Success'",
                Integer.class, invoiceId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_transactions"
                + " WHERE invoice_id = ? AND direction = 'PAY' AND status = 'Duplicate'",
                Integer.class, invoiceId)).isEqualTo(1);
        assertThat(statusOf("invoices", invoiceId)).isEqualTo("Paid");
    }

    @Test
    void manualFailureClosesThePaymentWithoutTouchingInvoiceState() throws Exception {
        String txnRef = createPendingPayment("INV-MANUAL-FAILED");

        mockMvc.perform(reconcileAs(ADMIN_EMAIL, txnRef, """
                        {"outcome":"FAILED","note":"portal: giao dịch không thành công"}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FAILED"));

        assertThat(paymentStatus(txnRef)).isEqualTo("Failed");
        assertThat(jdbc.queryForObject(
                "SELECT failure_reason FROM payment_transactions WHERE vnp_txn_ref = ?",
                String.class, txnRef)).contains("manual:");
        assertThat(statusOf("invoices", invoiceId)).isEqualTo("Unpaid");
        assertThat(statusOf("rental_orders", orderId)).isEqualTo("Pending");
        assertThat(statusOf("storage_units", unitId)).isEqualTo("Available");
        assertThat(jdbc.queryForList("SELECT action FROM audit_logs ORDER BY action", String.class))
                .containsExactly("PAYMENT_FAILED");
    }

    @Test
    void alreadySettledTransactionIsAConflict() throws Exception {
        String txnRef = "INV-MANUAL-ALREADY";
        jdbc.update("INSERT INTO payment_transactions"
                + " (invoice_id, vnp_txn_ref, amount, direction, status, paid_at, gateway_transaction_no)"
                + " VALUES (?, ?, 10000.00, 'PAY', 'Success', now(), 'GW-EXISTING')", invoiceId, txnRef);

        mockMvc.perform(reconcileAs(ADMIN_EMAIL, txnRef, """
                        {"outcome":"SUCCESS","note":"portal: already paid"}
                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("đã ở trạng thái")));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs", Integer.class)).isZero();
    }

    @Test
    void unknownTransactionIsNotFound() throws Exception {
        mockMvc.perform(reconcileAs(ADMIN_EMAIL, "INV-MANUAL-MISSING", """
                        {"outcome":"FAILED","note":"portal: not found"}
                        """))
                .andExpect(status().isNotFound());
    }

    @Test
    void customerCannotReconcile() throws Exception {
        String txnRef = createPendingPayment("INV-MANUAL-CUSTOMER");

        mockMvc.perform(reconcileAs(CUSTOMER_EMAIL, txnRef, """
                        {"outcome":"SUCCESS","note":"self service"}
                        """))
                .andExpect(status().isForbidden());

        assertThat(paymentStatus(txnRef)).isEqualTo("Pending");
    }

    @Test
    void missingNoteIsRejected() throws Exception {
        String txnRef = createPendingPayment("INV-MANUAL-INVALID");

        mockMvc.perform(reconcileAs(ADMIN_EMAIL, txnRef, """
                        {"outcome":"SUCCESS"}
                        """))
                .andExpect(status().isBadRequest());

        assertThat(paymentStatus(txnRef)).isEqualTo("Pending");
    }

    private String reconcile(String txnRef, CountDownLatch ready, CountDownLatch start) {
        ready.countDown();
        try {
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("concurrent reconcile start timed out");
            }
            return paymentService.reconcileManually(txnRef,
                    new ManualReconciliationRequest(ManualReconciliationOutcome.SUCCESS,
                            "concurrent manual", Instant.now(), "GW-" + txnRef),
                    ADMIN_EMAIL).status().name();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private String createPendingPayment(String txnRef) {
        jdbc.update("INSERT INTO payment_transactions"
                + " (invoice_id, vnp_txn_ref, amount, direction, status)"
                + " VALUES (?, ?, 10000.00, 'PAY', 'Pending')", invoiceId, txnRef);
        return txnRef;
    }

    private String paymentStatus(String txnRef) {
        return jdbc.queryForObject(
                "SELECT status FROM payment_transactions WHERE vnp_txn_ref = ?", String.class, txnRef);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder reconcileAs(
            String email, String txnRef, String body) {
        return post("/api/admin/payments/{txnRef}/reconcile", txnRef)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(email))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private String tokenFor(String email) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("self-storage").issuedAt(now).expiresAt(now.plusSeconds(300))
                .claim("email", email).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    private String statusOf(String table, Long id) {
        return jdbc.queryForObject("SELECT status FROM " + table + " WHERE id = ?", String.class, id);
    }
}
