package vn.lemar.selfstorage.booking;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.lemar.selfstorage.booking.application.AppointmentNoShowService;

/**
 * Cron no-show trên Postgres: lịch CHECKIN Pending quá end_at → Canceled
 * (Quá giờ và khách không đến) + audit + notification
 * khách; not-due/Done/Canceled/RETURN không đụng; chạy lại idempotent.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@Transactional
class AppointmentNoShowServiceIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private AppointmentNoShowService service;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private EntityManager entityManager;

    private long customerId;
    private long q7;
    private long orderId;
    private long dueId;
    private long notDueId;
    private long doneId;
    private long canceledId;
    private long returnId;

    @BeforeEach
    void seed() {
        customerId = accountId("customer1@lemar.vn");
        long fsId = accountId("fs1@lemar.vn");
        q7 = jdbc.queryForObject("select id from facilities where code = 'Q7'", Long.class);
        long unitId = jdbc.queryForObject("select id from storage_units where code = 'A-01'", Long.class);
        Long requestId = jdbc.queryForObject("""
                insert into rental_requests (facility_id, customer_name, normalized_customer_email,
                    customer_phone, unit_type_id, start_date, period, unit_id, status)
                values (?, 'Khách Test', 'customer1@lemar.vn', '0900000000',
                        (select id from unit_types where name = 'Small'), current_date + 7, 3, ?, 'Converted')
                returning id""", Long.class, q7, unitId);
        orderId = jdbc.queryForObject("""
                insert into rental_orders (request_id, customer_id, unit_id, status, expires_at)
                values (?, ?, ?, 'InProgress', now() + interval '30 days') returning id""",
                Long.class, requestId, customerId, unitId);
        LocalDate today = LocalDate.now();

        dueId = jdbc.queryForObject("""
                insert into appointments (customer_id, facility_id, staff_id, type, date, started_at, end_at, status)
                values (?, ?, ?, 'CHECKIN', ?, now() - interval '3 hours', now() - interval '1 hour',
                        'Pending') returning id""",
                Long.class, customerId, q7, fsId, today);
        jdbc.update("insert into rental_appointments (appointment_id, order_id) values (?, ?)", dueId, orderId);
        notDueId = jdbc.queryForObject("""
                insert into appointments (customer_id, facility_id, staff_id, type, date, started_at, end_at, status)
                values (?, ?, ?, 'CHECKIN', ?, now() + interval '1 hour', now() + interval '3 hours',
                        'Pending') returning id""",
                Long.class, customerId, q7, fsId, today);
        doneId = jdbc.queryForObject("""
                insert into appointments (customer_id, facility_id, staff_id, type, date, started_at, end_at, status)
                values (?, ?, ?, 'CHECKIN', ?, now() - interval '3 hours', now() - interval '1 hour',
                        'Done') returning id""",
                Long.class, customerId, q7, fsId, today);
        canceledId = jdbc.queryForObject("""
                insert into appointments (customer_id, facility_id, staff_id, type, date, started_at, end_at, status)
                values (?, ?, ?, 'CHECKIN', ?, now() - interval '3 hours', now() - interval '1 hour',
                        'Canceled') returning id""",
                Long.class, customerId, q7, fsId, today);
        returnId = jdbc.queryForObject("""
                insert into appointments (customer_id, facility_id, staff_id, type, date, started_at, end_at, status)
                values (?, ?, ?, 'RETURN', ?, now() - interval '3 hours', now() - interval '1 hour',
                        'Pending') returning id""",
                Long.class, customerId, q7, fsId, today);
    }

    @Test
    void cancelsOnlyDueCheckinAndNotifiesCustomer() {
        assertThat(service.findDueIds()).containsExactly(dueId);

        assertThat(service.cancelIfDue(dueId)).isTrue();
        entityManager.flush();

        assertThat(jdbc.queryForObject("select status from appointments where id = ?", String.class, dueId))
                .isEqualTo("Canceled");
        assertThat(jdbc.queryForObject("select cancel_reason from appointments where id = ?", String.class, dueId))
                .isEqualTo("Quá giờ và khách không đến");
        Map<String, Object> audit = jdbc.queryForMap("select action, actor_account_id, "
                + "old_value ->> 'status' old_status, new_value ->> 'status' new_status, reason "
                + "from audit_logs where action = 'APPOINTMENT_CANCELED_NO_SHOW' and entity_id = ?",
                String.valueOf(dueId));
        assertThat(audit.get("actor_account_id")).isNull();
        assertThat(audit.get("old_status")).isEqualTo("Pending");
        assertThat(audit.get("new_status")).isEqualTo("Canceled");
        assertThat(audit.get("reason")).isEqualTo("Quá giờ và khách không đến");

        Map<String, Object> notification = jdbc.queryForMap(
                "select type, title, body from notifications where account_id = ?", customerId);
        assertThat(notification.get("type")).isEqualTo("APPOINTMENT_CANCELED_NO_SHOW");
        assertThat((String) notification.get("body")).contains("http://localhost:5173/appointments/new");
        assertThat(jdbc.queryForObject("select order_id from order_notifications where notification_id = "
                + "(select id from notifications where account_id = ?)", Long.class, customerId)).isEqualTo(orderId);
    }

    @Test
    void cancelsDueAppointmentWithoutOrderAndStillNotifiesCustomer() {
        long noOrderId = jdbc.queryForObject("""
                insert into appointments (customer_id, facility_id, staff_id, type, date, started_at, end_at, status)
                values (?, ?, ?, 'CHECKIN', ?, now() - interval '3 hours', now() - interval '1 hour',
                        'Pending') returning id""",
                Long.class, customerId, q7, accountId("fs1@lemar.vn"), LocalDate.now());

        assertThat(service.cancelIfDue(noOrderId)).isTrue();
        entityManager.flush();

        assertThat(jdbc.queryForObject("select count(*) from notifications where account_id = ? and type = "
                + "'APPOINTMENT_CANCELED_NO_SHOW'", Long.class, customerId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from order_notifications o "
                + "join notifications n on n.id = o.notification_id where n.account_id = ?", Long.class,
                customerId)).isZero();
    }

    @Test
    void rerunIsIdempotent() {
        service.cancelIfDue(dueId);
        entityManager.flush();

        assertThat(service.cancelIfDue(dueId)).isFalse();
        entityManager.flush();
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action = "
                + "'APPOINTMENT_CANCELED_NO_SHOW' and entity_id = ?", Long.class, String.valueOf(dueId)))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from notifications where account_id = ?", Long.class,
                customerId)).isEqualTo(1L);
    }

    @Test
    void doesNotTouchNotDueDoneCanceledOrReturn() {
        for (long id : List.of(notDueId, doneId, canceledId, returnId)) {
            assertThat(service.cancelIfDue(id)).isFalse();
        }
        assertThat(service.findDueIds()).containsExactly(dueId);
        assertThat(jdbc.queryForObject("select count(*) from notifications where account_id = ?", Long.class,
                customerId)).isZero();
    }

    private long accountId(String email) {
        return jdbc.queryForObject("select id from accounts where email = ?", Long.class, email);
    }
}
