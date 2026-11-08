package vn.lemar.selfstorage.backoffice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.lemar.selfstorage.identity.application.AccountAdminService;
import vn.lemar.selfstorage.identity.application.FacilityStaffQuery;
import vn.lemar.selfstorage.payment.application.PaymentGateway;

/**
 * Gán / đổi cơ sở cho FS chạy thật trên Postgres: gỡ FS khỏi cơ sở mà FS còn việc mở ở đó bị 409 và không đổi gì;
 * FM đang phân công FS cùng lúc thì lần gỡ chờ rồi thấy việc vừa giao. Không bọc {@code @Transactional} để kiểm được
 * rollback — mỗi test tự tạo FS riêng.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class StaffFacilityAssignmentIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @MockBean
    private PaymentGateway gateway;
    @MockBean
    private JavaMailSender mailSender;

    @Autowired
    private AccountAdminService accounts;
    @Autowired
    private FacilityStaffQuery staffQuery;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private long adminId;
    private long customerId;
    private long q7;
    private long td;

    @BeforeEach
    void ids() {
        adminId = accountId("admin@lemar.vn");
        customerId = accountId("customer1@lemar.vn");
        q7 = facilityId("Q7");
        td = facilityId("TD");
    }

    @Test
    void staffWithoutOpenWorkMovesToAnotherFacilityAndIsAudited() {
        long fs = newStaffAt(q7);

        accounts.setStaffFacilities(fs, List.of(td), adminId);

        assertThat(assignedFacilities(fs)).containsExactly(td);
        assertThat(jdbc.queryForObject("""
                select old_value::text || ' -> ' || new_value::text from audit_logs
                where action = 'ACCOUNT_UPDATED' and entity_id = ? order by id desc limit 1""",
                String.class, String.valueOf(fs))).isEqualTo("{\"facilityIds\": [" + q7 + "]} -> {\"facilityIds\": ["
                + td + "]}");
    }

    @Test
    void keptFacilityKeepsItsAssignedAt() {
        long fs = newStaffAt(q7);
        jdbc.update("update account_facility_assignments set assigned_at = timestamptz '2026-01-01 00:00+00' "
                + "where account_id = ?", fs);

        accounts.setStaffFacilities(fs, List.of(q7, td, td), adminId);

        assertThat(assignedFacilities(fs)).containsExactly(q7, td);
        assertThat(jdbc.queryForObject("select assigned_at::date from account_facility_assignments "
                + "where account_id = ? and facility_id = ?", LocalDate.class, fs, q7))
                .isEqualTo(LocalDate.of(2026, 1, 1));
    }

    @Test
    void pendingAppointmentBlocksRemovalAndNothingChanges() {
        long fs = newStaffAt(q7);
        long appointmentId = pendingAppointment(fs, q7, "CHECKIN");

        assertThatThrownBy(() -> accounts.setStaffFacilities(fs, List.of(td), adminId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cơ sở Q7")
                .hasMessageContaining("lịch check-in #" + appointmentId);

        assertThat(assignedFacilities(fs)).containsExactly(q7);
        assertThat(auditCount(fs)).isZero();
    }

    @Test
    void openWorkAtAnotherFacilityDoesNotBlock() {
        long fs = newStaffAt(q7);
        jdbc.update("insert into account_facility_assignments (account_id, facility_id) values (?, ?)", fs, td);
        pendingAppointment(fs, q7, "CHECKIN");

        accounts.setStaffFacilities(fs, List.of(q7), adminId);

        assertThat(assignedFacilities(fs)).containsExactly(q7);
    }

    @Test
    void closedWorkDoesNotBlock() {
        long fs = newStaffAt(q7);
        long appointmentId = pendingAppointment(fs, q7, "RETURN");
        jdbc.update("update appointments set status = 'Done' where id = ?", appointmentId);
        long supportId = assignedSupportRequest(fs, q7);
        jdbc.update("update support_requests set status = 'Resolved' where id = ?", supportId);

        accounts.setStaffFacilities(fs, List.of(), adminId);

        assertThat(assignedFacilities(fs)).isEmpty();
    }

    @Test
    void assignedSupportRequestAndOpenHandoverBlockRemoval() {
        long fs = newStaffAt(q7);
        long supportId = assignedSupportRequest(fs, q7);
        long handoverId = arrivedHandoverInProgress(fs, q7);

        assertThatThrownBy(() -> accounts.setStaffFacilities(fs, List.of(), adminId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("yêu cầu hỗ trợ #" + supportId)
                .hasMessageContaining("phiên bàn giao #" + handoverId);

        assertThat(assignedFacilities(fs)).containsExactly(q7);
    }

    @Test
    void longListIsTruncated() {
        long fs = newStaffAt(q7);
        for (int i = 0; i < 7; i++) {
            assignedSupportRequest(fs, q7);
        }

        assertThatThrownBy(() -> accounts.setStaffFacilities(fs, List.of(), adminId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("và 2 việc khác");
    }

    @Test
    void removalWaitsForAConcurrentAssignmentAndSeesTheNewWork() throws Exception {
        long fs = newStaffAt(q7);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // FM: kiểm FS thuộc cơ sở (khoá FOR SHARE), giao việc, giữ transaction mở tới khi được thả.
            Future<Long> assignment = pool.submit(() -> tx.execute(status -> {
                assertThat(staffQuery.isActiveStaffOf(fs, q7)).isTrue();
                long id = assignedSupportRequest(fs, q7);
                locked.countDown();
                await(release);
                return id;
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

            Future<Object> removal = pool.submit(() -> {
                try {
                    return accounts.setStaffFacilities(fs, List.of(), adminId);
                } catch (IllegalStateException ex) {
                    return ex;
                }
            });
            Thread.sleep(300);
            assertThat(removal.isDone()).as("gỡ FS phải chờ khoá của FM").isFalse();
            release.countDown();

            long supportId = assignment.get(10, TimeUnit.SECONDS);
            assertThat(removal.get(10, TimeUnit.SECONDS)).isInstanceOfSatisfying(IllegalStateException.class,
                    ex -> assertThat(ex.getMessage()).contains("yêu cầu hỗ trợ #" + supportId));
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertThat(assignedFacilities(fs)).containsExactly(q7);
    }

    @Test
    void assignmentAfterRemovalIsRejected() {
        long fs = newStaffAt(q7);
        accounts.setStaffFacilities(fs, List.of(td), adminId);

        Boolean stillStaff = new TransactionTemplate(transactionManager)
                .execute(status -> staffQuery.isActiveStaffOf(fs, q7));
        assertThat(stillStaff).isFalse();
    }

    // ---------------------------------------------------------------- helpers

    private long newStaffAt(long facilityId) {
        long id = jdbc.queryForObject("""
                insert into accounts (email, password_hash, email_verified_at, role_id, status)
                values (?, (select password_hash from accounts where email = 'fs1@lemar.vn'), now(),
                        (select id from roles where name = 'FS'), 'ACTIVE') returning id""",
                Long.class, "fs-move-" + SEQ.incrementAndGet() + "@lemar.vn");
        jdbc.update("insert into account_facility_assignments (account_id, facility_id) values (?, ?)", id,
                facilityId);
        return id;
    }

    private long pendingAppointment(long staffId, long facilityId, String type) {
        LocalDate day = LocalDate.now().plusDays(3);
        return jdbc.queryForObject("""
                insert into appointments (customer_id, facility_id, staff_id, type, date, started_at, end_at, status)
                values (?, ?, ?, ?, ?, ?::timestamptz, ?::timestamptz, 'Pending') returning id""",
                Long.class, customerId, facilityId, staffId, type, day, day + "T08:00:00+07:00",
                day + "T10:00:00+07:00");
    }

    private long assignedSupportRequest(long staffId, long facilityId) {
        return jdbc.queryForObject("""
                insert into support_requests (customer_id, facility_id, category, title, description, status,
                    assigned_staff_id, assigned_at)
                values (?, ?, 'OTHER', 'Cửa kẹt', 'Cửa khoang bị kẹt', 'Assigned', ?, now()) returning id""",
                Long.class, customerId, facilityId, staffId);
    }

    /** Phiên bàn giao đang mở, khách đã đến (lịch {@code Done}) — FS của lịch làm tiếp. */
    private long arrivedHandoverInProgress(long staffId, long facilityId) {
        long unitId = jdbc.queryForObject("""
                insert into storage_units (facility_id, unit_type_id, code, status)
                values (?, (select id from unit_types where name = 'Small'), ?, 'Reserved') returning id""",
                Long.class, facilityId, "MV-" + SEQ.incrementAndGet());
        long requestId = jdbc.queryForObject("""
                insert into rental_requests (facility_id, customer_name, normalized_customer_email, customer_phone,
                    unit_type_id, start_date, period, unit_id, status)
                values (?, 'Khách Test', 'customer1@lemar.vn', '0900000000',
                        (select id from unit_types where name = 'Small'), current_date + 7, 3, ?, 'Converted')
                returning id""", Long.class, facilityId, unitId);
        long orderId = jdbc.queryForObject("""
                insert into rental_orders (request_id, customer_id, unit_id, status, expires_at)
                values (?, ?, ?, 'InProgress', now() + interval '30 days') returning id""",
                Long.class, requestId, customerId, unitId);
        long appointmentId = pendingAppointment(staffId, facilityId, "CHECKIN");
        jdbc.update("update appointments set status = 'Done', arrived_at = now() where id = ?", appointmentId);
        jdbc.update("insert into rental_appointments (appointment_id, order_id) values (?, ?)", appointmentId,
                orderId);
        return jdbc.queryForObject("""
                insert into handover_records (order_id, appointment_id, unit_id) values (?, ?, ?) returning id""",
                Long.class, orderId, appointmentId, unitId);
    }

    private List<Long> assignedFacilities(long accountId) {
        return jdbc.queryForList("select facility_id from account_facility_assignments where account_id = ? "
                + "order by facility_id", Long.class, accountId);
    }

    private long auditCount(long accountId) {
        return jdbc.queryForObject("select count(*) from audit_logs where action = 'ACCOUNT_UPDATED' "
                + "and entity_id = ?", Long.class, String.valueOf(accountId));
    }

    private long accountId(String email) {
        return jdbc.queryForObject("select id from accounts where email = ?", Long.class, email);
    }

    private long facilityId(String code) {
        return jdbc.queryForObject("select id from facilities where code = ?", Long.class, code);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
