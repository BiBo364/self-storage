package vn.lemar.selfstorage.handover;

import static org.assertj.core.api.Assertions.assertThat;

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
import vn.lemar.selfstorage.handover.application.HandoverService;

/**
 * Hai instance cùng huỷ một đơn: row lock trên {@code handover_records} bảo đảm một bên thắng,
 * bên thua thấy trạng thái đã đổi và bỏ qua; audit đúng một bản ghi mỗi entity, không deadlock.
 *
 * <p>Không {@code @Transactional} để mỗi lời gọi service có transaction/commit riêng.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class HandoverCancellationConcurrencyTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private HandoverService handoverService;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void concurrentCancelWritesOneAuditPerEntity() throws Exception {
        Fixture fixture = seed();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> first = pool.submit(() -> cancelAfterStart(ready, start, fixture.orderId()));
            Future<?> second = pool.submit(() -> cancelAfterStart(ready, start, fixture.orderId()));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            first.get(15, TimeUnit.SECONDS);
            second.get(15, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(jdbc.queryForObject(
                "select result from handover_records where order_id = ?", String.class, fixture.orderId()))
                .isEqualTo("CANCELED");
        assertThat(jdbc.queryForObject(
                "select status from rental_contracts where order_id = ?", String.class, fixture.orderId()))
                .isEqualTo("Canceled");
        assertThat(jdbc.queryForObject(
                "select count(*) from audit_logs where action = 'HANDOVER_CANCELED'", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "select count(*) from audit_logs where action = 'CONTRACT_CANCELED'", Long.class)).isEqualTo(1L);
    }

    private void cancelAfterStart(CountDownLatch ready, CountDownLatch start, long orderId) {
        ready.countDown();
        try {
            start.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        handoverService.cancelByOrderId(orderId, "Chưa hoàn tất bàn giao trong hạn");
    }

    private Fixture seed() {
        long customerId = jdbc.queryForObject(
                "select id from accounts where email = 'customer1@lemar.vn'", Long.class);
        long facilityId = jdbc.queryForObject("select id from facilities where code = 'Q7'", Long.class);
        long unitId = jdbc.queryForObject("select id from storage_units where code = 'A-03'", Long.class);
        Long requestId = jdbc.queryForObject("""
                insert into rental_requests (facility_id, customer_name, normalized_customer_email,
                    customer_phone, unit_type_id, start_date, period, unit_id, status)
                values (?, 'Concurrency', 'customer1@lemar.vn', '0900000000',
                    (select id from unit_types where name = 'Small'), current_date + 7, 3, ?, 'Converted')
                returning id""", Long.class, facilityId, unitId);
        long orderId = jdbc.queryForObject("""
                insert into rental_orders (request_id, customer_id, unit_id, status, expires_at)
                values (?, ?, ?, 'InProgress', now() + interval '30 days') returning id""",
                Long.class, requestId, customerId, unitId);
        LocalDate day = LocalDate.now().plusDays(3);
        long appointmentId = jdbc.queryForObject("""
                insert into appointments (customer_id, facility_id, type, date, started_at, end_at, status)
                values (?, ?, 'CHECKIN', ?, ?::timestamptz, ?::timestamptz, 'Pending') returning id""",
                Long.class, customerId, facilityId, day, day + "T08:00:00+07:00", day + "T10:00:00+07:00");
        jdbc.update("insert into rental_appointments (appointment_id, order_id) values (?, ?)",
                appointmentId, orderId);
        jdbc.update("""
                insert into handover_records (order_id, appointment_id, unit_id, result)
                values (?, ?, ?, 'IN_PROGRESS')""", orderId, appointmentId, unitId);
        jdbc.update("""
                insert into rental_contracts (order_id, customer_id, unit_id, code, terms_version,
                    monthly_price, deposit_amount, period, start_date, status)
                values (?, ?, ?, ?, 'v1', 500000, 500000, 3, current_date + 1, 'Draft')""",
                orderId, customerId, unitId, "CT-" + UUID.randomUUID());
        return new Fixture(orderId);
    }

    private record Fixture(long orderId) {
    }
}
