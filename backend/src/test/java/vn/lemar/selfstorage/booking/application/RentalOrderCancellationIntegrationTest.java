package vn.lemar.selfstorage.booking.application;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.lemar.selfstorage.booking.domain.RentalOrder;
import vn.lemar.selfstorage.booking.repository.RentalOrderRepository;

/**
 * Cancellation Cascade chạy thật trên Postgres: đơn {@code Scheduled} + lịch {@code CHECKIN Pending} →
 * huỷ đơn thì lịch sang {@code Canceled}, ghi {@code cancel_reason} và audit {@code APPOINTMENT_CANCELED}
 * với actor System. Test khoá đơn trước khi gọi cascade, đúng như caller thật.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@Transactional
class RentalOrderCancellationIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private RentalOrderCancellation cancellation;
    @Autowired
    private RentalOrderRepository orderRepository;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private EntityManager entityManager;

    @Test
    void cancelingOrderCancelsPendingAppointment() {
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

        RentalOrder order = orderRepository.findByIdForUpdate(orderId).orElseThrow();
        cancellation.cancel(order, "Khách không còn nhu cầu", customerId);
        entityManager.flush();

        assertThat(jdbc.queryForObject("select status from rental_orders where id = ?", String.class, orderId))
                .isEqualTo("Canceled");
        assertThat(jdbc.queryForObject("select status from appointments where id = ?", String.class, appointmentId))
                .isEqualTo("Canceled");
        assertThat(jdbc.queryForObject("select cancel_reason from appointments where id = ?", String.class,
                appointmentId)).isEqualTo("Khách không còn nhu cầu");
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action = 'APPOINTMENT_CANCELED'",
                Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select entity_type from audit_logs where action = 'APPOINTMENT_CANCELED'",
                String.class)).isEqualTo("Appointment");
        assertThat(jdbc.queryForObject("select entity_id from audit_logs where action = 'APPOINTMENT_CANCELED'",
                String.class)).isEqualTo(String.valueOf(appointmentId));
        assertThat(jdbc.queryForObject("select actor_account_id from audit_logs "
                + "where action = 'APPOINTMENT_CANCELED'", Long.class)).isNull();
        assertThat(jdbc.queryForObject("select old_value ->> 'status' from audit_logs "
                + "where action = 'APPOINTMENT_CANCELED'", String.class)).isEqualTo("Pending");
        assertThat(jdbc.queryForObject("select new_value ->> 'status' from audit_logs "
                + "where action = 'APPOINTMENT_CANCELED'", String.class)).isEqualTo("Canceled");
        assertThat(jdbc.queryForObject("select reason from audit_logs where action = 'APPOINTMENT_CANCELED'",
                String.class)).isEqualTo("Khách không còn nhu cầu");
    }

    private long accountId(String email) {
        return jdbc.queryForObject("select id from accounts where email = ?", Long.class, email);
    }
}
