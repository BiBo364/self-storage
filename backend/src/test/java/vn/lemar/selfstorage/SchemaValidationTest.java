package vn.lemar.selfstorage;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Boot context trên Postgres đã migrate — Hibernate (`ddl-auto=validate`) đối chiếu
 * entity với schema V1/V2; lệch là CI đỏ. Máy không có Docker thì test tự skip.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@org.springframework.test.context.TestPropertySource(properties = {
    "security.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
})
class SchemaValidationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void contextLoads() {
        // Không assert gì thêm: context chỉ load được nếu Flyway chạy và schema khớp entity.
    }

    @Test
    void v12ConstraintsAcceptDuplicatePaymentData() {
        String paymentStatusCheck = jdbc.queryForObject(
                "select pg_get_constraintdef(oid) from pg_constraint"
                        + " where conrelid = 'payment_transactions'::regclass"
                        + " and conname = 'payment_transactions_status_check'",
                String.class);
        String auditActionCheck = jdbc.queryForObject(
                "select pg_get_constraintdef(oid) from pg_constraint"
                        + " where conrelid = 'audit_logs'::regclass"
                        + " and conname = 'audit_logs_action_check'",
                String.class);

        assertThat(paymentStatusCheck).contains("Duplicate");
        assertThat(auditActionCheck)
                .contains("PAYMENT_DUPLICATE")
                .contains("INVOICE_REFUND_REQUIRED")
                .contains("APPOINTMENT_ARRIVED");
    }

    @Test
    void v13EnforcesOneSuccessfulPayPerInvoice() {
        String indexDefinition = jdbc.queryForObject(
                "select indexdef from pg_indexes"
                        + " where schemaname = 'public'"
                        + " and indexname = 'uq_payment_transactions_one_success_per_invoice'",
                String.class);

        assertThat(indexDefinition)
                .contains("UNIQUE INDEX")
                .contains("(invoice_id)")
                .contains("'Success'")
                .contains("'PAY'");
    }
}
