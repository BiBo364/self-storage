package vn.lemar.selfstorage.handover;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.lemar.selfstorage.handover.application.HandoverOverdueService;

/**
 * Hai instance cùng chạy cron trên một record: row lock bảo đảm một bên xử lý, bên kia thấy
 * record đã CANCELED và bỏ qua; audit/notification không nhân đôi.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class HandoverOverdueConcurrencyTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private HandoverOverdueService service;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void concurrentOverdueRunProcessesOnce() throws Exception {
        Fixture fixture = seed();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Boolean> first = pool.submit(() -> cancelAfterStart(ready, start, fixture.recordId()));
            Future<Boolean> second = pool.submit(() -> cancelAfterStart(ready, start, fixture.recordId()));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            boolean firstResult = first.get(15, TimeUnit.SECONDS);
            boolean secondResult = second.get(15, TimeUnit.SECONDS);

            assertThat(firstResult ^ secondResult).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(jdbc.queryForObject("select result from handover_records where id = ?",
                String.class, fixture.recordId())).isEqualTo("CANCELED");
        assertThat(jdbc.queryForObject("select status from rental_contracts where id = ?",
                String.class, fixture.contractId())).isEqualTo("Canceled");
        assertThat(jdbc.queryForObject("select status from rental_orders where id = ?",
                String.class, fixture.orderId())).isEqualTo("Deposited");
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action = 'HANDOVER_CANCELED'",
                Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action = 'CONTRACT_CANCELED'",
                Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action = 'RENTAL_ORDER_DEPOSITED'",
                Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from notifications where type = 'HANDOVER_OVERDUE'",
                Long.class)).isEqualTo(1L);
    }

    private boolean cancelAfterStart(CountDownLatch ready, CountDownLatch start, long recordId) {
        ready.countDown();
        try {
            start.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        return service.cancelIfOverdue(recordId);
    }

    private Fixture seed() {
        long customerId = jdbc.queryForObject(
                "select id from accounts where email = 'customer1@lemar.vn'", Long.class);
        long facilityId = jdbc.queryForObject("select id from facilities where code = 'Q7'", Long.class);
        long unitId = jdbc.queryForObject("select id from storage_units where code = 'A-01'", Long.class);
        jdbc.update("update storage_units set status = 'Reserved' where id = ?", unitId);
        Long requestId = jdbc.queryForObject("""
                insert into rental_requests (facility_id, customer_name, normalized_customer_email,
                    customer_phone, unit_type_id, start_date, period, unit_id, status)
                values (?, 'OverdueConcurrency', 'customer1@lemar.vn', '0900000000',
                    (select id from unit_types where name = 'Small'), current_date + 7, 3, ?, 'Converted')
                returning id""", Long.class, facilityId, unitId);
        long orderId = jdbc.queryForObject("""
                insert into rental_orders (request_id, customer_id, unit_id, status, expires_at)
                values (?, ?, ?, 'InProgress', now() + interval '30 days') returning id""",
                Long.class, requestId, customerId, unitId);
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
                values (?, ?, ?, 'IN_PROGRESS', ?) returning id""",
                Long.class, orderId, appointmentId, unitId, Timestamp.from(Instant.now().minusSeconds(60)));
        long contractId = jdbc.queryForObject("""
                insert into rental_contracts (order_id, customer_id, unit_id, code, terms_version,
                    monthly_price, deposit_amount, period, start_date, status)
                values (?, ?, ?, ?, 'v1', 500000, 500000, 3, current_date + 1, 'Draft') returning id""",
                Long.class, orderId, customerId, unitId, "CT-" + UUID.randomUUID());
        return new Fixture(recordId, orderId, contractId);
    }

    private record Fixture(long recordId, long orderId, long contractId) {
    }
}
