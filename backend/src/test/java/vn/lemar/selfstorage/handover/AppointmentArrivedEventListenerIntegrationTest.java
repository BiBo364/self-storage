package vn.lemar.selfstorage.handover;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
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
import vn.lemar.selfstorage.booking.application.StaffAppointmentActionService;

/**
 * Listener thật qua event {@code AppointmentArrived}: arrive commit thì {@code due_at} được set trong
 * cùng transaction; arrive không có {@code HandoverRecord} vẫn thành công (listener no-op).
 *
 * <p>Không đánh dấu {@code @Transactional} để transaction của arrive thực sự commit — đó là lúc
 * {@code @TransactionalEventListener(BEFORE_COMMIT)} chạy.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class AppointmentArrivedEventListenerIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private StaffAppointmentActionService appointmentActions;
    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void arriveSetsDueAtThroughPublishedEvent() {
        Fixture fixture = fixture();
        seedHandover(fixture);
        loginAs("fs1@lemar.vn");

        appointmentActions.arrive(fixture.appointmentId());

        Instant arrivedAt = jdbc.queryForObject("select arrived_at from appointments where id = ?",
                Timestamp.class, fixture.appointmentId()).toInstant();
        assertThat(jdbc.queryForObject("select status from appointments where id = ?", String.class,
                fixture.appointmentId())).isEqualTo("Done");
        assertThat(dueAt(fixture.appointmentId()))
                .isCloseTo(arrivedAt.plus(1, ChronoUnit.DAYS), within(1, ChronoUnit.MILLIS));
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action = 'APPOINTMENT_ARRIVED' "
                + "and entity_id = ?", Long.class, String.valueOf(fixture.appointmentId()))).isEqualTo(1L);
    }

    @Test
    void arriveWithoutHandoverRecordStillSucceeds() {
        Fixture fixture = fixture();
        loginAs("fs1@lemar.vn");

        appointmentActions.arrive(fixture.appointmentId());

        assertThat(jdbc.queryForObject("select status from appointments where id = ?", String.class,
                fixture.appointmentId())).isEqualTo("Done");
        assertThat(jdbc.queryForObject("select count(*) from handover_records where appointment_id = ?",
                Long.class, fixture.appointmentId())).isZero();
    }

    private Instant dueAt(long appointmentId) {
        Timestamp due = jdbc.queryForObject(
                "select due_at from handover_records where appointment_id = ?", Timestamp.class, appointmentId);
        return due == null ? null : due.toInstant();
    }

    private void seedHandover(Fixture fixture) {
        jdbc.update("""
                insert into handover_records (order_id, appointment_id, unit_id, result)
                values (?, ?, ?, 'IN_PROGRESS')""",
                fixture.orderId(), fixture.appointmentId(), fixture.unitId());
    }

    private Fixture fixture() {
        long fsId = accountId("fs1@lemar.vn");
        long customerId = accountId("customer1@lemar.vn");
        long facilityId = jdbc.queryForObject("select id from facilities where code = 'Q7'", Long.class);
        long unitId = jdbc.queryForObject("select id from storage_units where code = 'A-02'", Long.class);
        Long requestId = jdbc.queryForObject("""
                insert into rental_requests (facility_id, customer_name, normalized_customer_email,
                    customer_phone, unit_type_id, start_date, period, unit_id, status)
                values (?, 'Khách Test', 'customer1@lemar.vn', '0900000000',
                    (select id from unit_types where name = 'Small'), current_date + 7, 3, ?, 'Converted')
                returning id""", Long.class, facilityId, unitId);
        long orderId = jdbc.queryForObject("""
                insert into rental_orders (request_id, customer_id, unit_id, status, expires_at)
                values (?, ?, ?, 'InProgress', now() + interval '30 days') returning id""",
                Long.class, requestId, customerId, unitId);
        LocalDate day = LocalDate.now().plusDays(3);
        long appointmentId = jdbc.queryForObject("""
                insert into appointments (customer_id, facility_id, staff_id, type, date, started_at, end_at, status)
                values (?, ?, ?, 'CHECKIN', ?, ?::timestamptz, ?::timestamptz, 'Pending') returning id""",
                Long.class, customerId, facilityId, fsId, day, day + "T08:00:00+07:00",
                day + "T10:00:00+07:00");
        jdbc.update("insert into rental_appointments (appointment_id, order_id) values (?, ?)",
                appointmentId, orderId);
        // Điều kiện vào Flow 2: đơn đã cọc thì khoang đang Reserved cho đơn.
        jdbc.update("update storage_units set status = 'Reserved' where id = ?", unitId);
        return new Fixture(orderId, appointmentId, unitId);
    }

    private long accountId(String email) {
        return jdbc.queryForObject("select id from accounts where email = ?", Long.class, email);
    }

    private static void loginAs(String email) {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(email, null, List.of()));
    }

    private record Fixture(long orderId, long appointmentId, long unitId) {
    }
}
