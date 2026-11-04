package vn.lemar.selfstorage.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.lemar.selfstorage.booking.application.AppointmentArrived;
import vn.lemar.selfstorage.booking.application.StaffAppointmentActionService;
import vn.lemar.selfstorage.booking.application.dto.StaffAppointmentResponse;
import vn.lemar.selfstorage.booking.application.exception.AppointmentConflictException;
import vn.lemar.selfstorage.booking.application.exception.AppointmentForbiddenException;

/**
 * Arrive/cancel FS chạy thật trên Postgres: arrive Pending→Done + audit + event; cancel chủ động
 * Pending→Canceled + reason + audit; FS khác/unassigned 403; quá end_at 409 (cron no-show).
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@Transactional
@RecordApplicationEvents
class StaffAppointmentActionIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private StaffAppointmentActionService service;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ApplicationEvents events;

    private long fsId;
    private long fs2Id;
    private long customerId;
    private long q7;
    private long appointmentId;
    private final LocalDate day = LocalDate.now().plusDays(3);

    @BeforeEach
    void seed() {
        fsId = accountId("fs1@lemar.vn");
        customerId = accountId("customer1@lemar.vn");
        q7 = jdbc.queryForObject("select id from facilities where code = 'Q7'", Long.class);
        long unitId = jdbc.queryForObject("select id from storage_units where code = 'A-01'", Long.class);
        jdbc.update("update storage_units set status = 'Reserved' where id = ?", unitId);

        fs2Id = jdbc.queryForObject("""
                insert into accounts (email, password_hash, email_verified_at, role_id, status, name)
                values ('fs2@lemar.vn', (select password_hash from accounts where id = ?), now(),
                        (select id from roles where name = 'FS'), 'ACTIVE', 'FS 2') returning id""",
                Long.class, fsId);
        jdbc.update("insert into account_facility_assignments (account_id, facility_id) values (?, ?)",
                fs2Id, q7);

        Long requestId = jdbc.queryForObject("""
                insert into rental_requests (facility_id, customer_name, normalized_customer_email,
                    customer_phone, unit_type_id, start_date, period, unit_id, status)
                values (?, 'Khách Test', 'customer1@lemar.vn', '0900000000',
                        (select id from unit_types where name = 'Small'), current_date + 7, 3, ?, 'Converted')
                returning id""", Long.class, q7, unitId);
        long orderId = jdbc.queryForObject("""
                insert into rental_orders (request_id, customer_id, unit_id, status, expires_at)
                values (?, ?, ?, 'InProgress', now() + interval '30 days') returning id""",
                Long.class, requestId, customerId, unitId);
        appointmentId = jdbc.queryForObject("""
                insert into appointments (customer_id, facility_id, staff_id, type, date, started_at, end_at, status)
                values (?, ?, ?, 'CHECKIN', ?, ?::timestamptz, ?::timestamptz, 'Pending') returning id""",
                Long.class, customerId, q7, fsId, day, day + "T08:00:00+07:00", day + "T10:00:00+07:00");
        jdbc.update("insert into rental_appointments (appointment_id, order_id) values (?, ?)",
                appointmentId, orderId);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void arriveMarksDoneAuditsAndPublishesEvent() {
        loginAs("fs1@lemar.vn");

        StaffAppointmentResponse result = service.arrive(appointmentId);

        assertThat(result.status()).isEqualTo("Done");
        assertThat(result.arrivedAt()).isNotNull();
        assertThat(jdbc.queryForObject("select status from appointments where id = ?", String.class, appointmentId))
                .isEqualTo("Done");
        assertThat(jdbc.queryForObject("select arrived_at from appointments where id = ?", Object.class,
                appointmentId)).isNotNull();
        Map<String, Object> audit = jdbc.queryForMap("select action, actor_account_id, "
                + "old_value ->> 'status' as old_status, new_value ->> 'status' as new_status "
                + "from audit_logs where entity_type = 'Appointment' and entity_id = ?",
                String.valueOf(appointmentId));
        assertThat(audit.get("action")).isEqualTo("APPOINTMENT_ARRIVED");
        assertThat(audit.get("actor_account_id")).isEqualTo(fsId);
        assertThat(audit.get("old_status")).isEqualTo("Pending");
        assertThat(audit.get("new_status")).isEqualTo("Done");
        assertThat(events.stream(AppointmentArrived.class)).hasSize(1);
    }

    @Test
    void cancelMarksCanceledWithReasonAndAudit() {
        loginAs("fs1@lemar.vn");

        StaffAppointmentResponse result = service.cancel(appointmentId, "Khách yêu cầu huỷ");

        assertThat(result.status()).isEqualTo("Canceled");
        assertThat(jdbc.queryForObject("select status from appointments where id = ?", String.class, appointmentId))
                .isEqualTo("Canceled");
        assertThat(jdbc.queryForObject("select cancel_reason from appointments where id = ?", String.class,
                appointmentId)).isEqualTo("Khách yêu cầu huỷ");
        Map<String, Object> audit = jdbc.queryForMap("select action, actor_account_id, reason, "
                + "old_value ->> 'status' as old_status, new_value ->> 'status' as new_status "
                + "from audit_logs where entity_type = 'Appointment' and entity_id = ?",
                String.valueOf(appointmentId));
        assertThat(audit.get("action")).isEqualTo("APPOINTMENT_CANCELED");
        assertThat(audit.get("actor_account_id")).isEqualTo(fsId);
        assertThat(audit.get("reason")).isEqualTo("Khách yêu cầu huỷ");
        assertThat(audit.get("old_status")).isEqualTo("Pending");
        assertThat(audit.get("new_status")).isEqualTo("Canceled");
        assertThat(events.stream(AppointmentArrived.class)).isEmpty();
    }

    @Test
    void otherStaffCannotActOnTheAppointment() {
        loginAs("fs2@lemar.vn");

        assertThatThrownBy(() -> service.arrive(appointmentId))
                .isInstanceOf(AppointmentForbiddenException.class);
        assertThat(jdbc.queryForObject("select status from appointments where id = ?", String.class, appointmentId))
                .isEqualTo("Pending");
    }

    @Test
    void unassignedAppointmentIsForbidden() {
        long orphanId = jdbc.queryForObject("""
                insert into appointments (customer_id, facility_id, type, date, status)
                values (?, ?, 'CHECKIN', ?, 'Pending') returning id""",
                Long.class, customerId, q7, day);
        loginAs("fs1@lemar.vn");

        assertThatThrownBy(() -> service.arrive(orphanId)).isInstanceOf(AppointmentForbiddenException.class);
    }

    @Test
    void expiredCancelConflictsAndLeavesPending() {
        jdbc.update("update appointments set started_at = now() - interval '3 hours', "
                + "end_at = now() - interval '1 hour' where id = ?", appointmentId);
        loginAs("fs1@lemar.vn");

        assertThatThrownBy(() -> service.cancel(appointmentId, "Khách không đến"))
                .isInstanceOf(AppointmentConflictException.class)
                .hasMessage("Lịch đã quá giờ, chờ cron no-show");
        assertThat(jdbc.queryForObject("select status from appointments where id = ?", String.class, appointmentId))
                .isEqualTo("Pending");
    }

    @Test
    void arrivingTwiceConflicts() {
        loginAs("fs1@lemar.vn");
        service.arrive(appointmentId);

        assertThatThrownBy(() -> service.arrive(appointmentId))
                .isInstanceOf(AppointmentConflictException.class)
                .hasMessage("Lịch hẹn không còn ở trạng thái chờ");
    }

    private long accountId(String email) {
        return jdbc.queryForObject("select id from accounts where email = ?", Long.class, email);
    }

    private static void loginAs(String email) {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(email, null, List.of()));
    }
}
