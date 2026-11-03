package vn.lemar.selfstorage;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Chạy Flyway trên Postgres thật (Testcontainers) — schema/seed hỏng là CI đỏ ngay.
 * Máy không có Docker thì test tự skip (disabledWithoutDocker).
 */
@Testcontainers(disabledWithoutDocker = true)
class MigrationTest {

    /** Bảng lõi phải tồn tại sau V1. Không đếm tổng số bảng — thêm bảng mới không nên làm đỏ test. */
    private static final List<String> CORE_TABLES = List.of(
            "roles", "accounts", "facilities", "account_facility_assignments",
            "permissions", "role_permissions",
            "unit_types", "policies", "storage_units",
            "rental_requests", "rental_orders", "rental_contracts", "invoices",
            "proposal_feedbacks", "appointments", "rental_appointments",
            "handover_records", "unit_access_keys", "payment_transactions", "audit_logs",
            "notifications", "order_notifications");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static Flyway flyway() {
        return Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load();
    }

    private static int count(Connection connection, String table) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT count(*) FROM " + table)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static int countWhere(Connection connection, String table, String where) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT count(*) FROM " + table + " WHERE " + where)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static boolean tableExists(Connection connection, String table) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("SELECT to_regclass(?) IS NOT NULL")) {
            statement.setString(1, "public." + table);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        }
    }

    @Test
    void v8MigratesExistingExpiredProposalRows() throws Exception {
        String schema = "upgrade_v8";
        MigrateResult toV7 = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema)
                .defaultSchema(schema)
                .target(MigrationVersion.fromVersion("7"))
                .load()
                .migrate();
        assertThat(toV7.migrationsExecuted).isPositive();

        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            connection.setSchema(schema);
            long requestId;
            try (PreparedStatement statement = connection.prepareStatement("""
                    insert into rental_requests (facility_id, customer_name, normalized_customer_email,
                        customer_phone, unit_type_id, start_date, period, unit_id, status)
                    values (1, 'Upgrade', 'upgrade@example.com', '0900000000', 1, current_date + 1, 1, 1,
                        'Converted') returning id""")) {
                try (ResultSet rs = statement.executeQuery()) {
                    rs.next();
                    requestId = rs.getLong(1);
                }
            }
            long orderId;
            try (PreparedStatement statement = connection.prepareStatement("""
                    insert into rental_orders (request_id, customer_id, unit_id, status)
                    values (?, (select id from accounts where email = 'customer1@lemar.vn'), 1, 'Deposited')
                    returning id""")) {
                statement.setLong(1, requestId);
                try (ResultSet rs = statement.executeQuery()) {
                    rs.next();
                    orderId = rs.getLong(1);
                }
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    insert into proposal_feedbacks (order_id, customer_id, unit_id, status)
                    values (?, (select id from accounts where email = 'customer1@lemar.vn'), 1, 'Expired')""")) {
                statement.setLong(1, orderId);
                statement.executeUpdate();
            }
        }

        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema)
                .defaultSchema(schema)
                .load()
                .migrate();

        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            connection.setSchema(schema);
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery("select status from proposal_feedbacks")) {
                rs.next();
                assertThat(rs.getString(1)).isEqualTo("Canceled");
            }
        }
    }

    @Test
    void migrateCreatesAllTablesAndDevSeed() throws Exception {
        MigrateResult result = flyway().migrate();
        assertThat(result.migrationsExecuted).isPositive();

        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {

            for (String table : CORE_TABLES) {
                assertThat(tableExists(connection, table)).as(table).isTrue();
            }

            assertThat(count(connection, "notifications")).isZero();
            assertThat(count(connection, "roles")).isEqualTo(5);
            assertThat(count(connection, "accounts")).isEqualTo(8);
            assertThat(count(connection, "facilities")).isEqualTo(2);
            assertThat(count(connection, "unit_types")).isEqualTo(3);
            assertThat(count(connection, "storage_units")).isEqualTo(8);
            // 9 = 6 key baseline + order.expiry_days (V5) + invoice.deposit_months (V8) + fee.unit_change (V9).
            assertThat(count(connection, "policies")).isEqualTo(9);
            // V5 (bug #130): policy key B2 đọc khi tạo RentalOrder.
            assertThat(countWhere(connection, "policies", "key = 'order.expiry_days'")).isEqualTo(1);
            // V8: hệ số tiền cọc của Flow 1.3.
            assertThat(countWhere(connection, "policies", "key = 'invoice.deposit_months'")).isEqualTo(1);
            // V9: phí đổi khoang sau khi cọc.
            assertThat(countWhere(connection, "policies", "key = 'fee.unit_change'")).isEqualTo(1);
            // V11: một hoá đơn cọc đầy đủ active + một hoá đơn bổ sung chờ thanh toán trên mỗi đơn.
            assertThat(countWhere(connection, "pg_indexes",
                    "indexname = 'uq_invoices_active_full_deposit'")).isEqualTo(1);
            assertThat(countWhere(connection, "pg_indexes",
                    "indexname = 'uq_invoices_unpaid_deposit_supplement'")).isEqualTo(1);
            assertThat(count(connection, "account_facility_assignments")).isEqualTo(1);
            // V3: permission catalog + mapping mặc định (A3b, #57).
            assertThat(count(connection, "permissions")).isEqualTo(24);
            assertThat(count(connection, "role_permissions")).isEqualTo(32);
            assertThat(countWhere(connection, "role_permissions", "scope = 'FACILITY'")).isEqualTo(24);
            assertThat(countWhere(connection, "role_permissions", "scope = 'GLOBAL'")).isEqualTo(8);

            // #57: quan hệ FM–Facility 1–1 trên facilities.
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery(
                         "SELECT count(*) FROM information_schema.columns"
                                 + " WHERE table_name = 'facilities' AND column_name = 'fm_account_id'")) {
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(1);
            }
            // Seed dev: cả 2 facility đã gán FM (đủ để test cross-facility 403).
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery(
                         "SELECT count(*) FROM facilities WHERE fm_account_id IS NOT NULL")) {
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(2);
            }
        }

        // Chạy lại không thực thi migration mới (idempotent)
        assertThat(flyway().migrate().migrationsExecuted).isZero();
    }
}
