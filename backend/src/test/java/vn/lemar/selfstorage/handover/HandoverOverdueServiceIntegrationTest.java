package vn.lemar.selfstorage.handover;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.lemar.selfstorage.handover.application.HandoverOverdueJob;
import vn.lemar.selfstorage.handover.application.HandoverOverdueService;
import vn.lemar.selfstorage.handover.repository.HandoverRecordRepository;

/**
 * Cron quá {@code due_at} chạy thật trên Postgres: nhánh còn hạn giữ kho, nhánh hết hạn chạy
 * Cancellation Cascade, boundary due_at, idempotent, record/order bất thường, audit + notification.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@Transactional
class HandoverOverdueServiceIntegrationTest {

    private static final String REASON = "Chưa hoàn tất bàn giao trong hạn";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private HandoverOverdueService service;
    @Autowired
    private HandoverOverdueJob job;
    @Autowired
    private HandoverRecordRepository handoverRecords;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private EntityManager entityManager;

    @Test
    void overdueWithinOrderExpiryReturnsOrderToDeposited() {
        Fixture fixture = fixture("InProgress", Instant.now().plus(30, ChronoUnit.DAYS),
                "Draft", "IN_PROGRESS", Instant.now().minus(1, ChronoUnit.HOURS));

        assertThat(service.cancelIfOverdue(fixture.recordId())).isTrue();
        entityManager.flush();

        assertThat(recordResult(fixture.recordId())).isEqualTo("CANCELED");
        assertThat(rejectReason(fixture.recordId())).isEqualTo(REASON);
        assertThat(contractStatus(fixture.contractId())).isEqualTo("Canceled");
        assertThat(orderStatus(fixture.orderId())).isEqualTo("Deposited");
        assertThat(unitStatus(fixture.unitId())).isEqualTo("Reserved");
        assertThat(notificationCount("HANDOVER_OVERDUE", fixture.customerId())).isEqualTo(1);
        assertThat(auditCount("HANDOVER_CANCELED")).isEqualTo(1);
        assertThat(auditCount("CONTRACT_CANCELED")).isEqualTo(1);
        assertThat(auditCount("RENTAL_ORDER_DEPOSITED")).isEqualTo(1);
        Map<String, Object> orderAudit = jdbc.queryForMap(
                "select actor_account_id, reason, old_value ->> 'status' as old_status, "
                        + "new_value ->> 'status' as new_status from audit_logs "
                        + "where action = 'RENTAL_ORDER_DEPOSITED'");
        assertThat(orderAudit.get("actor_account_id")).isNull();
        assertThat(orderAudit.get("reason")).isEqualTo(REASON);
        assertThat(orderAudit.get("old_status")).isEqualTo("InProgress");
        assertThat(orderAudit.get("new_status")).isEqualTo("Deposited");

        assertThat(service.cancelIfOverdue(fixture.recordId())).isFalse();
        entityManager.flush();
        assertThat(auditCount("HANDOVER_CANCELED")).isEqualTo(1);
        assertThat(auditCount("CONTRACT_CANCELED")).isEqualTo(1);
        assertThat(auditCount("RENTAL_ORDER_DEPOSITED")).isEqualTo(1);
    }

    @Test
    void overduePastOrderExpiryRunsCancellationCascade() {
        Fixture fixture = fixture("InProgress", Instant.now().minus(1, ChronoUnit.HOURS),
                "Signed", "IN_PROGRESS", Instant.now().minus(2, ChronoUnit.HOURS));

        assertThat(service.cancelIfOverdue(fixture.recordId())).isTrue();
        entityManager.flush();

        assertThat(recordResult(fixture.recordId())).isEqualTo("CANCELED");
        assertThat(contractStatus(fixture.contractId())).isEqualTo("Canceled");
        assertThat(orderStatus(fixture.orderId())).isEqualTo("Canceled");
        assertThat(jdbc.queryForObject("select cancel_reason from rental_orders where id = ?",
                String.class, fixture.orderId())).isEqualTo(REASON);
        assertThat(unitStatus(fixture.unitId())).isEqualTo("Available");
        assertThat(auditCount("HANDOVER_CANCELED")).isEqualTo(1);
        assertThat(auditCount("CONTRACT_CANCELED")).isEqualTo(1);
        // Order + StorageUnit, cùng action RENTAL_ORDER_CANCELED.
        assertThat(auditCount("RENTAL_ORDER_CANCELED")).isEqualTo(2);
        assertThat(notificationCount("RENTAL_ORDER_CANCELED", fixture.customerId())).isEqualTo(1);
        assertThat(notificationCount("HANDOVER_OVERDUE", fixture.customerId())).isZero();
    }

    @Test
    void dueAtBoundaryUsesStrictlyBefore() {
        Instant now = Instant.now();
        Fixture exactlyNow = fixture("InProgress", now.plus(30, ChronoUnit.DAYS),
                "Draft", "IN_PROGRESS", now);
        Fixture future = fixture("InProgress", now.plus(30, ChronoUnit.DAYS),
                "Draft", "IN_PROGRESS", now.plus(1, ChronoUnit.HOURS));
        Fixture alreadyDue = fixture("InProgress", now.plus(30, ChronoUnit.DAYS),
                "Draft", "IN_PROGRESS", now.minusSeconds(1));

        assertThat(handoverRecords.findOverdueIds(now))
                .contains(alreadyDue.recordId())
                .doesNotContain(exactlyNow.recordId(), future.recordId());
        assertThat(service.cancelIfOverdue(future.recordId())).isFalse();
        assertThat(service.cancelIfOverdue(alreadyDue.recordId())).isTrue();
    }

    @Test
    void closedRecordIsSkipped() {
        Fixture fixture = fixture("InProgress", Instant.now().plus(30, ChronoUnit.DAYS),
                "Draft", "CANCELED", Instant.now().minus(1, ChronoUnit.HOURS));

        assertThat(service.cancelIfOverdue(fixture.recordId())).isFalse();
        entityManager.flush();

        assertThat(auditCount("HANDOVER_CANCELED")).isZero();
        assertThat(auditCount("CONTRACT_CANCELED")).isZero();
    }

    @Test
    void missingRecordIsSkipped() {
        assertThat(service.cancelIfOverdue(999_999L)).isFalse();
    }

    @Test
    void orderDoneIsSkipped() {
        Fixture fixture = fixture("Done", Instant.now().plus(30, ChronoUnit.DAYS),
                "Active", "IN_PROGRESS", Instant.now().minus(1, ChronoUnit.HOURS));

        assertThat(service.cancelIfOverdue(fixture.recordId())).isFalse();
        entityManager.flush();

        assertThat(recordResult(fixture.recordId())).isEqualTo("IN_PROGRESS");
        assertThat(auditCount("HANDOVER_CANCELED")).isZero();
        assertThat(notificationCount("HANDOVER_OVERDUE", fixture.customerId())).isZero();
    }

    @Test
    void canceledOrderStillCleansRecordAndContract() {
        Fixture fixture = fixture("Canceled", Instant.now().plus(30, ChronoUnit.DAYS),
                "Draft", "IN_PROGRESS", Instant.now().minus(1, ChronoUnit.HOURS));

        assertThat(service.cancelIfOverdue(fixture.recordId())).isTrue();
        entityManager.flush();

        assertThat(recordResult(fixture.recordId())).isEqualTo("CANCELED");
        assertThat(contractStatus(fixture.contractId())).isEqualTo("Canceled");
        assertThat(orderStatus(fixture.orderId())).isEqualTo("Canceled");
        assertThat(auditCount("HANDOVER_CANCELED")).isEqualTo(1);
        assertThat(auditCount("CONTRACT_CANCELED")).isEqualTo(1);
        assertThat(notificationCount("HANDOVER_OVERDUE", fixture.customerId())).isZero();
    }

    @Test
    void nullOrderExpiryReturnsToDeposited() {
        Fixture fixture = fixture("InProgress", null,
                "Draft", "IN_PROGRESS", Instant.now().minus(1, ChronoUnit.HOURS));

        assertThat(service.cancelIfOverdue(fixture.recordId())).isTrue();
        entityManager.flush();

        assertThat(orderStatus(fixture.orderId())).isEqualTo("Deposited");
        assertThat(notificationCount("HANDOVER_OVERDUE", fixture.customerId())).isEqualTo(1);
    }

    @Test
    void jobProcessesEveryDueRecord() {
        Fixture first = fixture("InProgress", Instant.now().plus(30, ChronoUnit.DAYS),
                "Draft", "IN_PROGRESS", Instant.now().minus(1, ChronoUnit.HOURS));
        Fixture second = fixture("InProgress", Instant.now().minus(1, ChronoUnit.HOURS),
                "Signed", "IN_PROGRESS", Instant.now().minus(2, ChronoUnit.HOURS));

        job.cancelOverdue();
        entityManager.flush();

        assertThat(recordResult(first.recordId())).isEqualTo("CANCELED");
        assertThat(recordResult(second.recordId())).isEqualTo("CANCELED");
        assertThat(orderStatus(first.orderId())).isEqualTo("Deposited");
        assertThat(orderStatus(second.orderId())).isEqualTo("Canceled");
        assertThat(auditCount("HANDOVER_CANCELED")).isEqualTo(2);
    }

    private Fixture fixture(String orderStatus, Instant expiresAt, String contractStatus,
                            String recordResult, Instant dueAt) {
        long customerId = jdbc.queryForObject(
                "select id from accounts where email = 'customer1@lemar.vn'", Long.class);
        long facilityId = jdbc.queryForObject("select id from facilities where code = 'Q7'", Long.class);
        long unitId = jdbc.queryForObject("select id from storage_units where code = 'A-01'", Long.class);
        jdbc.update("update storage_units set status = 'Reserved' where id = ?", unitId);

        Long requestId = jdbc.queryForObject("""
                insert into rental_requests (facility_id, customer_name, normalized_customer_email,
                    customer_phone, unit_type_id, start_date, period, unit_id, status)
                values (?, 'Overdue', 'customer1@lemar.vn', '0900000000',
                    (select id from unit_types where name = 'Small'), current_date + 7, 3, ?, 'Converted')
                returning id""", Long.class, facilityId, unitId);
        long orderId = jdbc.queryForObject("""
                insert into rental_orders (request_id, customer_id, unit_id, status, expires_at)
                values (?, ?, ?, ?, ?) returning id""",
                Long.class, requestId, customerId, unitId, orderStatus, timestamp(expiresAt));

        LocalDate day = LocalDate.now().plusDays(3);
        long appointmentId = jdbc.queryForObject("""
                insert into appointments (customer_id, facility_id, type, date, started_at, end_at,
                    arrived_at, status)
                values (?, ?, 'CHECKIN', ?, ?::timestamptz, ?::timestamptz, now(), 'Done') returning id""",
                Long.class, customerId, facilityId, day, day + "T08:00:00+07:00", day + "T10:00:00+07:00");
        jdbc.update("insert into rental_appointments (appointment_id, order_id) values (?, ?)",
                appointmentId, orderId);
        long recordId = jdbc.queryForObject("""
                insert into handover_records (order_id, appointment_id, unit_id, result, due_at)
                values (?, ?, ?, ?, ?) returning id""",
                Long.class, orderId, appointmentId, unitId, recordResult, timestamp(dueAt));
        long contractId = jdbc.queryForObject("""
                insert into rental_contracts (order_id, customer_id, unit_id, code, terms_version,
                    monthly_price, deposit_amount, period, start_date, status)
                values (?, ?, ?, ?, 'v1', 500000, 500000, 3, current_date + 1, ?) returning id""",
                Long.class, orderId, customerId, unitId, "CT-" + UUID.randomUUID(), contractStatus);
        return new Fixture(recordId, orderId, contractId, unitId, customerId);
    }

    private String recordResult(long recordId) {
        return jdbc.queryForObject("select result from handover_records where id = ?", String.class, recordId);
    }

    private String rejectReason(long recordId) {
        return jdbc.queryForObject("select reject_reason from handover_records where id = ?",
                String.class, recordId);
    }

    private String contractStatus(long contractId) {
        return jdbc.queryForObject("select status from rental_contracts where id = ?",
                String.class, contractId);
    }

    private String orderStatus(long orderId) {
        return jdbc.queryForObject("select status from rental_orders where id = ?", String.class, orderId);
    }

    private String unitStatus(long unitId) {
        return jdbc.queryForObject("select status from storage_units where id = ?", String.class, unitId);
    }

    private long auditCount(String action) {
        return jdbc.queryForObject("select count(*) from audit_logs where action = ?", Long.class, action);
    }

    private long notificationCount(String type, long customerId) {
        return jdbc.queryForObject("select count(*) from notifications where type = ? and account_id = ?",
                Long.class, type, customerId);
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private record Fixture(long recordId, long orderId, long contractId, long unitId, long customerId) {
    }
}
