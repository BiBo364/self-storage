package vn.lemar.selfstorage.booking;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.Callable;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.lemar.selfstorage.booking.application.AppointmentService;
import vn.lemar.selfstorage.booking.application.exception.AppointmentConflictException;

/**
 * Chạy thật trên Postgres: hai FM gán hai FS khác nhau cho cùng một lịch check-in đồng thời. Khoá đơn
 * (rồi lịch) tuần tự hoá hai request — một request thắng, request còn lại nhận 409 và không ghi audit.
 *
 * <p>Không dùng {@code @Transactional}: mỗi request phải commit độc lập để tái hiện race.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class AppointmentAssignConcurrencyTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private AppointmentService service;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void twoConcurrentAssignsOnlyOneWins() throws Exception {
        long fmId = accountId("fm1@lemar.vn");
        long fs1 = accountId("fs1@lemar.vn");
        long fs2 = secondStaffOfQ7(fs1);
        long customerId = accountId("customer1@lemar.vn");
        long q7 = jdbc.queryForObject("select id from facilities where code = 'Q7'", Long.class);
        long unitId = jdbc.queryForObject("select id from storage_units where code = 'A-01'", Long.class);
        jdbc.update("update storage_units set status = 'Reserved' where id = ?", unitId);

        Long requestId = jdbc.queryForObject("""
                insert into rental_requests (facility_id, customer_name, normalized_customer_email,
                    customer_phone, unit_type_id, start_date, period, unit_id, status)
                values (?, 'Khách Test', 'customer1@lemar.vn', '0900000000',
                        (select id from unit_types where name = 'Small'), current_date + 7, 3, ?, 'Converted')
                returning id""", Long.class, q7, unitId);
        long orderId = jdbc.queryForObject("""
                insert into rental_orders (request_id, customer_id, unit_id, status, expires_at)
                values (?, ?, ?, 'Scheduled', now() + interval '30 days') returning id""",
                Long.class, requestId, customerId, unitId);
        LocalDate day = LocalDate.now().plusDays(3);
        long appointmentId = jdbc.queryForObject("""
                insert into appointments (customer_id, facility_id, type, date, started_at, end_at, status)
                values (?, ?, 'CHECKIN', ?, ?::timestamptz, ?::timestamptz, 'Pending') returning id""",
                Long.class, customerId, q7, day, day + "T08:00:00+07:00", day + "T10:00:00+07:00");
        jdbc.update("insert into rental_appointments (appointment_id, order_id) values (?, ?)",
                appointmentId, orderId);

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Object> first = pool.submit(assignWhenReleased(start, appointmentId, fs1, fmId));
            Future<Object> second = pool.submit(assignWhenReleased(start, appointmentId, fs2, fmId));
            start.countDown();

            List<Object> results = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertThat(results).filteredOn(result -> result instanceof AppointmentConflictException)
                    .singleElement()
                    .satisfies(result -> assertThat(((AppointmentConflictException) result).getMessage())
                            .isEqualTo("Lịch hẹn đã có FS phụ trách"));
            assertThat(results).filteredOn(result -> !(result instanceof AppointmentConflictException))
                    .hasSize(1);
        } finally {
            pool.shutdownNow();
        }

        Long winner = jdbc.queryForObject("select staff_id from appointments where id = ?", Long.class,
                appointmentId);
        assertThat(winner).isIn(fs1, fs2);
        assertThat(jdbc.queryForObject("select status from rental_orders where id = ?", String.class, orderId))
                .isEqualTo("InProgress");
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action = 'FS_ASSIGNED' "
                + "and entity_type = 'Appointment' and entity_id = ?", Long.class, String.valueOf(appointmentId)))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action = 'FS_ASSIGNED' "
                + "and entity_type = 'RentalOrder' and entity_id = ?", Long.class, String.valueOf(orderId)))
                .isEqualTo(1L);
    }

    private Callable<Object> assignWhenReleased(CountDownLatch start, long appointmentId, long staffId,
                                                long actorId) {
        return () -> {
            start.await();
            SecurityContextHolder.getContext().setAuthentication(
                    UsernamePasswordAuthenticationToken.authenticated("fm1@lemar.vn", null, List.of()));
            try {
                return service.assignStaff(appointmentId, staffId, actorId);
            } catch (RuntimeException ex) {
                return ex;
            } finally {
                SecurityContextHolder.clearContext();
            }
        };
    }

    private long accountId(String email) {
        return jdbc.queryForObject("select id from accounts where email = ?", Long.class, email);
    }

    private long secondStaffOfQ7(long fsId) {
        long id = jdbc.queryForObject("""
                insert into accounts (email, password_hash, email_verified_at, role_id, status)
                values ('fs2@lemar.vn', (select password_hash from accounts where id = ?), now(),
                        (select id from roles where name = 'FS'), 'ACTIVE') returning id""", Long.class, fsId);
        jdbc.update("insert into account_facility_assignments (account_id, facility_id) values (?, "
                + "(select id from facilities where code = 'Q7'))", id);
        return id;
    }
}
