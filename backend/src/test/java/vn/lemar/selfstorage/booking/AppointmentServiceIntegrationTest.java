package vn.lemar.selfstorage.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManager;
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
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.lemar.selfstorage.booking.application.AppointmentQueryService;
import vn.lemar.selfstorage.booking.application.AppointmentService;
import vn.lemar.selfstorage.booking.application.dto.FmAppointmentResponse;
import vn.lemar.selfstorage.booking.application.exception.AppointmentConflictException;
import vn.lemar.selfstorage.booking.application.exception.AppointmentNotFoundException;
import vn.lemar.selfstorage.booking.application.exception.AppointmentValidationException;
import vn.lemar.selfstorage.identity.application.FacilityStaffQuery.StaffView;
import vn.lemar.selfstorage.identity.application.exception.FacilityNotFoundException;
import vn.lemar.selfstorage.identity.application.exception.ForbiddenException;

/**
 * Spec 1.5 phía FM chạy thật trên Postgres (Testcontainers) với seed V2/V3: xem lịch theo ngày, danh sách FS, phân
 * công FS. Đơn đã cọc + lịch hẹn do luồng đặt lịch của khách tạo nên test tự chèn bằng SQL. Máy không có Docker
 * thì skip.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@Transactional
class AppointmentServiceIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private AppointmentService service;
    @Autowired
    private AppointmentQueryService queryService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private EntityManager entityManager;

    private long fmId;
    private long fsId;
    private long q7;
    private long orderId;
    private long appointmentId;
    private final LocalDate day = LocalDate.now().plusDays(3);

    @BeforeEach
    void seedScheduledOrderWithCheckinAppointment() {
        fmId = accountId("fm1@lemar.vn");
        fsId = accountId("fs1@lemar.vn");
        long customerId = accountId("customer1@lemar.vn");
        q7 = jdbc.queryForObject("select id from facilities where code = 'Q7'", Long.class);
        long unitId = jdbc.queryForObject("select id from storage_units where code = 'A-01'", Long.class);
        jdbc.update("update storage_units set status = 'Reserved' where id = ?", unitId);
        Long requestId = jdbc.queryForObject("""
                insert into rental_requests (facility_id, customer_name, normalized_customer_email, customer_phone,
                    unit_type_id, start_date, period, unit_id, status)
                values (?, 'Khách Test', 'customer1@lemar.vn', '0900000000',
                        (select id from unit_types where name = 'Small'), current_date + 7, 3, ?, 'Converted')
                returning id""", Long.class, q7, unitId);
        orderId = jdbc.queryForObject("""
                insert into rental_orders (request_id, customer_id, unit_id, status, expires_at)
                values (?, ?, ?, 'Scheduled', now() + interval '30 days') returning id""",
                Long.class, requestId, customerId, unitId);
        appointmentId = jdbc.queryForObject("""
                insert into appointments (customer_id, facility_id, type, date, started_at, end_at, status)
                values (?, ?, 'CHECKIN', ?, ?::timestamptz, ?::timestamptz, 'Pending') returning id""",
                Long.class, customerId, q7, day, day + "T08:00:00+07:00", day + "T10:00:00+07:00");
        jdbc.update("insert into rental_appointments (appointment_id, order_id) values (?, ?)", appointmentId,
                orderId);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ---------------------------------------------------------------- phân công FS

    @Test
    void assignStaffMovesOrderToInProgressAuditsAndNotifiesStaff() {
        loginAs("fm1@lemar.vn");

        FmAppointmentResponse result = service.assignStaff(appointmentId, fsId, fmId);

        assertThat(result.staffId()).isEqualTo(fsId);
        assertThat(result.staffEmail()).isEqualTo("fs1@lemar.vn");
        assertThat(result.orderStatus()).isEqualTo("InProgress");
        assertThat(jdbc.queryForObject("select staff_id from appointments where id = ?", Long.class, appointmentId))
                .isEqualTo(fsId);
        assertThat(orderStatus()).isEqualTo("InProgress");
        assertThat(auditActions()).containsExactly("FS_ASSIGNED");
        assertThat(jdbc.queryForObject("select actor_account_id from audit_logs where entity_type = 'Appointment' "
                + "and entity_id = ?", Long.class, String.valueOf(appointmentId))).isEqualTo(fmId);
        List<Map<String, Object>> notifications = jdbc.queryForList(
                "select type, title, body from notifications where account_id = ?", fsId);
        assertThat(notifications).singleElement().satisfies(row -> {
            assertThat(row.get("type")).isEqualTo("FS_ASSIGNED");
            assertThat((String) row.get("body")).contains("08:00–10:00").contains("A-01")
                    .contains(String.valueOf(orderId));
        });
    }

    @Test
    void assigningTheSameStaffAgainConflicts() {
        loginAs("fm1@lemar.vn");
        service.assignStaff(appointmentId, fsId, fmId);

        assertThatThrownBy(() -> service.assignStaff(appointmentId, fsId, fmId))
                .isInstanceOf(AppointmentConflictException.class)
                .hasMessage("Lịch hẹn đã do FS này phụ trách");
        assertThat(auditActions()).containsExactly("FS_ASSIGNED");
    }

    @Test
    void pendingAppointmentCanBeReassignedKeepingOrderInProgress() {
        long newFsId = secondStaffOfQ7();
        loginAs("fm1@lemar.vn");
        service.assignStaff(appointmentId, fsId, fmId);

        FmAppointmentResponse result = service.assignStaff(appointmentId, newFsId, fmId);

        assertThat(result.staffId()).isEqualTo(newFsId);
        assertThat(result.staffEmail()).isEqualTo("fs2@lemar.vn");
        assertThat(orderStatus()).isEqualTo("InProgress");
        assertThat(auditActions()).containsExactly("FS_ASSIGNED", "FS_ASSIGNED");
        Map<String, Object> reassigned = jdbc.queryForMap("""
                select old_value ->> 'staffId' as old_staff, new_value ->> 'staffId' as new_staff,
                       new_value ->> 'orderStatus' as order_status, actor_account_id
                from audit_logs where entity_type = 'Appointment' and entity_id = ? order by id desc limit 1""",
                String.valueOf(appointmentId));
        assertThat(reassigned.get("old_staff")).isEqualTo(String.valueOf(fsId));
        assertThat(reassigned.get("new_staff")).isEqualTo(String.valueOf(newFsId));
        assertThat(reassigned.get("order_status")).isNull();
        assertThat(reassigned.get("actor_account_id")).isEqualTo(fmId);
        assertThat(notificationTypes(newFsId)).containsExactly("FS_ASSIGNED");
        assertThat(notificationTypes(fsId)).containsExactly("FS_ASSIGNED", "FS_REASSIGNED");
    }

    @Test
    void reassignWhenOrderIsNoLongerInProgressConflicts() {
        long newFsId = secondStaffOfQ7();
        loginAs("fm1@lemar.vn");
        service.assignStaff(appointmentId, fsId, fmId);
        entityManager.flush();
        jdbc.update("update rental_orders set status = 'Canceled' where id = ?", orderId);
        entityManager.clear();

        assertThatThrownBy(() -> service.assignStaff(appointmentId, newFsId, fmId))
                .isInstanceOf(AppointmentConflictException.class)
                .hasMessage("Đơn thuê không ở trạng thái đang tiếp đón");
        assertThat(notificationTypes(newFsId)).isEmpty();
    }

    @Test
    void reassigningToAccountOutsideTheFacilityIsRejected() {
        loginAs("fm1@lemar.vn");
        service.assignStaff(appointmentId, fsId, fmId);

        assertThatThrownBy(() -> service.assignStaff(appointmentId, fmId, fmId))
                .isInstanceOf(AppointmentValidationException.class)
                .hasMessage("Nhân viên không phải FS của cơ sở này");
        assertThat(notificationTypes(fsId)).containsExactly("FS_ASSIGNED");
    }

    @Test
    void accountThatIsNotStaffOfTheFacilityIsRejected() {
        loginAs("fm1@lemar.vn");

        assertThatThrownBy(() -> service.assignStaff(appointmentId, fmId, fmId))
                .isInstanceOf(AppointmentValidationException.class)
                .hasMessage("Nhân viên không phải FS của cơ sở này");
        assertThat(orderStatus()).isEqualTo("Scheduled");
    }

    @Test
    void managerOfAnotherFacilityIsForbidden() {
        loginAs("fm2@lemar.vn");

        assertThatThrownBy(() -> service.assignStaff(appointmentId, fsId, accountId("fm2@lemar.vn")))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void orderNotWaitingForAssignmentConflicts() {
        jdbc.update("update rental_orders set status = 'Deposited' where id = ?", orderId);
        loginAs("fm1@lemar.vn");

        assertThatThrownBy(() -> service.assignStaff(appointmentId, fsId, fmId))
                .isInstanceOf(AppointmentConflictException.class)
                .hasMessage("Đơn thuê không ở trạng thái chờ phân công");
        assertThat(jdbc.queryForObject("select staff_id from appointments where id = ?", Long.class, appointmentId))
                .isNull();
    }

    @Test
    void canceledAppointmentConflicts() {
        jdbc.update("update appointments set status = 'Canceled' where id = ?", appointmentId);
        loginAs("fm1@lemar.vn");

        assertThatThrownBy(() -> service.assignStaff(appointmentId, fsId, fmId))
                .isInstanceOf(AppointmentConflictException.class)
                .hasMessage("Lịch hẹn không còn ở trạng thái chờ");
    }

    @Test
    void nonCheckinAppointmentCannotBeAssigned() {
        jdbc.update("update appointments set type = 'HANDOVER' where id = ?", appointmentId);
        loginAs("fm1@lemar.vn");

        assertThatThrownBy(() -> service.assignStaff(appointmentId, fsId, fmId))
                .isInstanceOf(AppointmentConflictException.class)
                .hasMessage("Chỉ phân công FS cho lịch check-in");
        assertThat(jdbc.queryForObject("select staff_id from appointments where id = ?", Long.class, appointmentId))
                .isNull();
    }

    @Test
    void appointmentWithoutRentalOrderConflicts() {
        long orphanId = jdbc.queryForObject("""
                insert into appointments (customer_id, facility_id, type, date, status)
                values (?, ?, 'CHECKIN', ?, 'Pending') returning id""",
                Long.class, accountId("customer1@lemar.vn"), q7, day);
        loginAs("fm1@lemar.vn");

        assertThatThrownBy(() -> service.assignStaff(orphanId, fsId, fmId))
                .isInstanceOf(AppointmentConflictException.class)
                .hasMessage("Lịch hẹn không gắn với đơn thuê");
    }

    @Test
    void unknownAppointmentIsNotFound() {
        loginAs("fm1@lemar.vn");

        assertThatThrownBy(() -> service.assignStaff(-1L, fsId, fmId))
                .isInstanceOf(AppointmentNotFoundException.class);
    }

    // ---------------------------------------------------------------- màn lịch theo ngày + danh sách FS

    @Test
    void dayListShowsOrderUnitCustomerAndStaff() {
        loginAs("fm1@lemar.vn");

        List<FmAppointmentResponse> before = queryService.listForFacility(q7, day);
        service.assignStaff(appointmentId, fsId, fmId);
        entityManager.flush();
        List<FmAppointmentResponse> after = queryService.listForFacility(q7, day);

        assertThat(before).singleElement().satisfies(row -> {
            assertThat(row.type()).isEqualTo("CHECKIN");
            assertThat(row.orderId()).isEqualTo(orderId);
            assertThat(row.orderStatus()).isEqualTo("Scheduled");
            assertThat(row.unitCode()).isEqualTo("A-01");
            assertThat(row.customerEmail()).isEqualTo("customer1@lemar.vn");
            assertThat(row.staffId()).isNull();
        });
        assertThat(after).singleElement().satisfies(row -> assertThat(row.staffEmail()).isEqualTo("fs1@lemar.vn"));
        assertThat(queryService.listForFacility(q7, day.plusDays(1))).isEmpty();
    }

    @Test
    void dayListOnlyContainsCheckinAppointments() {
        jdbc.update("""
                insert into appointments (customer_id, facility_id, type, date, status)
                values (?, ?, 'HANDOVER', ?, 'Pending')""",
                accountId("customer1@lemar.vn"), q7, day);
        loginAs("fm1@lemar.vn");

        assertThat(queryService.listForFacility(q7, day)).extracting(FmAppointmentResponse::id)
                .containsExactly(appointmentId);
    }

    @Test
    void dayListIsScopedToTheFacility() {
        loginAs("fm2@lemar.vn");

        assertThatThrownBy(() -> queryService.listForFacility(q7, day)).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void unknownFacilityIsNotFound() {
        loginAs("fm1@lemar.vn");

        assertThatThrownBy(() -> queryService.listForFacility(-1L, day))
                .isInstanceOf(FacilityNotFoundException.class);
    }

    @Test
    void staffListContainsOnlyActiveStaffOfTheFacility() {
        loginAs("fm1@lemar.vn");

        assertThat(queryService.staffOfFacility(q7)).extracting(StaffView::email).containsExactly("fs1@lemar.vn");

        jdbc.update("update accounts set status = 'BANNED' where id = ?", fsId);
        assertThat(queryService.staffOfFacility(q7)).isEmpty();
    }

    // ---------------------------------------------------------------- helpers

    private long accountId(String email) {
        return jdbc.queryForObject("select id from accounts where email = ?", Long.class, email);
    }

    /** FS thứ hai của Q7 để thử đổi FS; seed chỉ có fs1. */
    private long secondStaffOfQ7() {
        long id = jdbc.queryForObject("""
                insert into accounts (email, password_hash, email_verified_at, role_id, status)
                values ('fs2@lemar.vn', (select password_hash from accounts where id = ?), now(),
                        (select id from roles where name = 'FS'), 'ACTIVE') returning id""", Long.class, fsId);
        jdbc.update("insert into account_facility_assignments (account_id, facility_id) values (?, ?)", id, q7);
        return id;
    }

    private List<String> notificationTypes(long accountId) {
        entityManager.flush();
        return jdbc.queryForList("select type from notifications where account_id = ? order by id", String.class,
                accountId);
    }

    private String orderStatus() {
        entityManager.flush();
        return jdbc.queryForObject("select status from rental_orders where id = ?", String.class, orderId);
    }

    private List<String> auditActions() {
        entityManager.flush();
        return jdbc.queryForList("select action from audit_logs where entity_type = 'Appointment' and entity_id = ? "
                + "order by id", String.class, String.valueOf(appointmentId));
    }

    private static void loginAs(String email) {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(email, null, List.of()));
    }
}
