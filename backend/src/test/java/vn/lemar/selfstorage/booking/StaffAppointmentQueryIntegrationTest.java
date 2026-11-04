package vn.lemar.selfstorage.booking;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.lemar.selfstorage.booking.application.StaffAppointmentQueryService;
import vn.lemar.selfstorage.booking.application.dto.StaffAppointmentResponse;

/**
 * Lịch làm việc FS chạy thật trên Postgres: chỉ trả lịch có staff_id = FS đang đăng nhập, kèm
 * khách/khoang/cơ sở; FS khác không thấy lịch của nhau. Read-only — chưa có arrive (C1).
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@Transactional
class StaffAppointmentQueryIntegrationTest {

    private static final ZoneId FACILITY_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private StaffAppointmentQueryService service;
    @Autowired
    private JdbcTemplate jdbc;

    private long fsId;
    private long fs2Id;
    private long customerId;
    private long q7;
    private long orderId;
    private long appointmentId;
    private long otherAppointmentId;
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
        orderId = jdbc.queryForObject("""
                insert into rental_orders (request_id, customer_id, unit_id, status, expires_at)
                values (?, ?, ?, 'Scheduled', now() + interval '30 days') returning id""",
                Long.class, requestId, customerId, unitId);
        appointmentId = jdbc.queryForObject("""
                insert into appointments (customer_id, facility_id, staff_id, type, date, started_at, end_at, status)
                values (?, ?, ?, 'CHECKIN', ?, ?::timestamptz, ?::timestamptz, 'Pending') returning id""",
                Long.class, customerId, q7, fsId, day, day + "T08:00:00+07:00", day + "T10:00:00+07:00");
        jdbc.update("insert into rental_appointments (appointment_id, order_id) values (?, ?)",
                appointmentId, orderId);

        otherAppointmentId = jdbc.queryForObject("""
                insert into appointments (customer_id, facility_id, staff_id, type, date, status)
                values (?, ?, ?, 'CHECKIN', ?, 'Pending') returning id""",
                Long.class, customerId, q7, fs2Id, day);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void returnsOnlyCurrentStaffAppointments() {
        loginAs("fs1@lemar.vn");

        List<StaffAppointmentResponse> result = service.listForCurrentStaff(day);

        assertThat(result).singleElement().satisfies(row -> {
            assertThat(row.id()).isEqualTo(appointmentId);
            assertThat(row.type()).isEqualTo("CHECKIN");
            assertThat(row.status()).isEqualTo("Pending");
            assertThat(row.date()).isEqualTo(day);
            assertThat(row.arrivedAt()).isNull();
            assertThat(row.noShowDue()).isFalse();
            assertThat(row.cancelReason()).isNull();
            assertThat(row.orderId()).isEqualTo(orderId);
            assertThat(row.unitCode()).isEqualTo("A-01");
            assertThat(row.facility().id()).isEqualTo(q7);
            assertThat(row.facility().name()).isEqualTo("Self Storage Quận 7");
            assertThat(row.customer().name()).isEqualTo("Khách Demo");
            assertThat(row.customer().email()).isEqualTo("customer1@lemar.vn");
            assertThat(row.customer().phone()).isEqualTo("0900000000");
        });
        assertThat(result).extracting(StaffAppointmentResponse::id).doesNotContain(otherAppointmentId);
    }

    @Test
    void marksOverduePendingAppointmentAsNoShowDue() {
        LocalDate today = LocalDate.now(FACILITY_ZONE);
        long dueId = jdbc.queryForObject("""
                insert into appointments (customer_id, facility_id, staff_id, type, date, started_at, end_at, status)
                values (?, ?, ?, 'CHECKIN', ?, now() - interval '3 hours', now() - interval '1 hour',
                        'Pending') returning id""",
                Long.class, customerId, q7, fsId, today);
        loginAs("fs1@lemar.vn");

        List<StaffAppointmentResponse> result = service.listForCurrentStaff(today);

        assertThat(result).singleElement().satisfies(row -> {
            assertThat(row.id()).isEqualTo(dueId);
            assertThat(row.noShowDue()).isTrue();
            assertThat(row.cancelReason()).isNull();
        });
    }

    @Test
    void returnsCancelReasonForCanceledAppointment() {
        jdbc.update("update appointments set status = 'Canceled', cancel_reason = 'NoShow' where id = ?",
                appointmentId);
        loginAs("fs1@lemar.vn");

        List<StaffAppointmentResponse> result = service.listForCurrentStaff(day);

        assertThat(result).singleElement().satisfies(row -> {
            assertThat(row.status()).isEqualTo("Canceled");
            assertThat(row.cancelReason()).isEqualTo("NoShow");
        });
    }

    @Test
    void missingDateUsesTodayInFacilityZone() {
        LocalDate today = LocalDate.now(FACILITY_ZONE);
        long todayAppointmentId = jdbc.queryForObject("""
                insert into appointments (customer_id, facility_id, staff_id, type, date, status)
                values (?, ?, ?, 'CHECKIN', ?, 'Pending') returning id""",
                Long.class, customerId, q7, fsId, today);
        loginAs("fs1@lemar.vn");

        List<StaffAppointmentResponse> result = service.listForCurrentStaff(null);

        assertThat(result).extracting(StaffAppointmentResponse::id).containsExactly(todayAppointmentId);
    }

    private long accountId(String email) {
        return jdbc.queryForObject("select id from accounts where email = ?", Long.class, email);
    }

    private static void loginAs(String email) {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(email, null, List.of()));
    }
}
