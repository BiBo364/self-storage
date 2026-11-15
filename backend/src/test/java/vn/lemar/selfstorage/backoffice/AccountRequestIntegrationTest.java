package vn.lemar.selfstorage.backoffice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.lemar.selfstorage.backoffice.application.AccountRequestService;
import vn.lemar.selfstorage.backoffice.application.AccountRequestService.AccountRequestRow;
import vn.lemar.selfstorage.backoffice.application.BackofficeService;
import vn.lemar.selfstorage.facility.application.FacilityAdminCommands.FacilityRow;
import vn.lemar.selfstorage.identity.application.AccountAdminService;
import vn.lemar.selfstorage.identity.repository.AccountRepository;
import vn.lemar.selfstorage.payment.application.PaymentGateway;

/** Yêu cầu tạo tài khoản nhân sự (BOM gửi → ADMIN duyệt / từ chối) chạy thật trên Postgres. */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class AccountRequestIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final String BOM = "bom1@lemar.vn";
    private static final String ADMIN = "admin@lemar.vn";
    private static final String PASSWORD = "Khoimoc@2026";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @MockBean
    private PaymentGateway gateway;
    @MockBean
    private JavaMailSender mailSender;

    @Autowired
    private AccountRequestService requests;
    @Autowired
    private BackofficeService backoffice;
    @Autowired
    private AccountAdminService accounts;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private AccountRepository accountRepository;

    private long q7;

    @BeforeEach
    void setUp() {
        q7 = id("select id from facilities where code = 'Q7'");
        when(mailSender.createMimeMessage()).thenReturn(
                new jakarta.mail.internet.MimeMessage(jakarta.mail.Session.getInstance(new java.util.Properties())));
        when(gateway.newTxnRef(any())).thenCallRealMethod();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void fsRequestIsApprovedIntoAnAssignedAccount() {
        loginAs(BOM);
        String email = uniqueEmail("fs");
        int adminNoticesBefore = notifications(ADMIN, "ACCOUNT_REQUEST_SUBMITTED");
        AccountRequestRow request = requests.submit("FS", " " + email.toUpperCase() + " ", "Nguyễn Văn Mới",
                "0901234567", List.of(q7, q7), "Ca sáng", null);

        assertThat(request.status()).isEqualTo("Pending");
        assertThat(request.email()).isEqualTo(email);
        assertThat(request.facilityIds()).containsExactly(q7);
        assertThat(request.facilityCodes()).containsExactly("Q7");
        assertThat(request.requestedByEmail()).isEqualTo(BOM);
        assertThat(notifications(ADMIN, "ACCOUNT_REQUEST_SUBMITTED")).isEqualTo(adminNoticesBefore + 1);
        assertThatThrownBy(() -> requests.submit("FS", email, "Trùng", null, List.of(q7), null, null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("đang có yêu cầu chờ duyệt");
        assertThatThrownBy(() -> requests.submit("FS", "fs1@lemar.vn", "Đã có", null, List.of(q7), null, null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("đã có tài khoản");

        loginAs(ADMIN);
        assertThatThrownBy(() -> requests.approve(request.id(), "ngan"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(requests.get(request.id()).status()).isEqualTo("Pending");
        assertThat(count("select count(*) from accounts where email = ?", email)).isZero();

        AccountRequestRow approved = requests.approve(request.id(), PASSWORD);
        assertThat(approved.status()).isEqualTo("Approved");
        assertThat(approved.reviewedByEmail()).isEqualTo(ADMIN);
        long accountId = approved.createdAccountId();
        assertThat(jdbc.queryForObject("select r.name from accounts a join roles r on r.id = a.role_id "
                + "where a.id = ?", String.class, accountId)).isEqualTo("FS");
        assertThat(passwordEncoder.matches(PASSWORD, jdbc.queryForObject(
                "select password_hash from accounts where id = ?", String.class, accountId))).isTrue();
        assertThat(count("select count(*) from account_facility_assignments where account_id = ? and facility_id = ?",
                accountId, q7)).isEqualTo(1);
        assertThat(notifications(BOM, "ACCOUNT_REQUEST_REVIEWED")).isPositive();
        assertThat(notifications(email, "ACCOUNT_READY")).isEqualTo(1);
        assertThat(count("select count(*) from audit_logs where action = 'ACCOUNT_REQUEST_APPROVED' "
                + "and entity_id = ?", String.valueOf(request.id()))).isEqualTo(1);
        assertThatThrownBy(() -> requests.approve(request.id(), PASSWORD))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("đã được xử lý");
        assertThat(requests.list("Approved")).extracting(AccountRequestRow::id).contains(request.id());
    }

    @Test
    void fmRequestNeedsAFacilityWithoutManagerAtSubmitAndApproval() {
        loginAs(BOM);
        assertThatThrownBy(() -> requests.submit("FM", uniqueEmail("fm"), "FM thừa", null, List.of(q7), null, null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("đã có FM");
        assertThatThrownBy(() -> requests.submit("FM", uniqueEmail("fm"), "FM hai nơi", null,
                List.of(q7, id("select id from facilities where code = 'TD'")), null, null))
                .isInstanceOf(IllegalArgumentException.class);
        String code = "R" + SEQ.incrementAndGet() + System.nanoTime() % 1000;
        FacilityRow fresh = backoffice.createFacility(code, "Cơ sở mới cần FM", null, null, null, null, null);
        String email = uniqueEmail("fm");
        AccountRequestRow request = requests.submit("FM", email, "Trần Quản Lý", null, List.of(fresh.id()), null, null);

        loginAs(ADMIN);
        Long otherFm = accounts.create(uniqueEmail("fmx"), PASSWORD, "FM khác", null, "FM", 1L).id();
        backoffice.assignManager(fresh.id(), otherFm, true);
        assertThatThrownBy(() -> requests.approve(request.id(), PASSWORD))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("đã có FM");
        assertThat(requests.get(request.id()).status()).isEqualTo("Pending");
        assertThat(count("select count(*) from accounts where email = ?", email)).isZero();

        backoffice.assignManager(fresh.id(), null, false);
        AccountRequestRow approved = requests.approve(request.id(), PASSWORD);
        assertThat(jdbc.queryForObject("select fm_account_id from facilities where id = ?", Long.class, fresh.id()))
                .isEqualTo(approved.createdAccountId());
    }

    @Test
    void requestCanBeRejectedOrCanceledOnlyByItsSender() {
        loginAs(BOM);
        assertThatThrownBy(() -> requests.submit("BOM", uniqueEmail("bom"), "BOM mới", null, List.of(), null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("tài khoản BOM do Admin tạo");
        assertThatThrownBy(() -> requests.submit("ADMIN", uniqueEmail("ad"), "Admin", null, List.of(), null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> requests.submit("FS", uniqueEmail("fs"), "FS không cơ sở", null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> requests.submit("FS", "khong-phai-email", "X", null, List.of(q7), null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> requests.submit("FS", uniqueEmail("fs"), " ", null, List.of(q7), null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> requests.submit("FS", uniqueEmail("fs"), "X", null, List.of(-1L), null, null))
                .isInstanceOf(IllegalArgumentException.class);
        AccountRequestRow rejectable = requests.submit("FS", uniqueEmail("fs"), "Lê Nhân Viên", null, List.of(q7),
                null, null);
        AccountRequestRow cancelable = requests.submit("FS", uniqueEmail("fs"), "Phạm Nhân Viên", null, List.of(q7),
                null, null);

        loginAs(ADMIN);
        assertThatThrownBy(() -> requests.reject(rejectable.id(), " "))
                .isInstanceOf(IllegalArgumentException.class);
        AccountRequestRow rejected = requests.reject(rejectable.id(), "Đã đủ nhân sự");
        assertThat(rejected.status()).isEqualTo("Rejected");
        assertThat(rejected.reviewNote()).isEqualTo("Đã đủ nhân sự");
        assertThatThrownBy(() -> requests.list("Unknown")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> requests.get(-1L)).isInstanceOf(IllegalArgumentException.class);

        String otherBom = uniqueEmail("bom2");
        accounts.create(otherBom, PASSWORD, "BOM khác", null, "BOM", 1L);
        loginAs(otherBom);
        assertThatThrownBy(() -> requests.cancel(cancelable.id()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Chỉ người gửi");
        loginAs(BOM);
        assertThatThrownBy(() -> requests.cancel(rejectable.id())).isInstanceOf(IllegalStateException.class);
        assertThat(requests.cancel(cancelable.id()).status()).isEqualTo("Canceled");
        assertThat(requests.list(null)).extracting(AccountRequestRow::id)
                .contains(rejectable.id(), cancelable.id());
    }

    @Test
    void fsReplacementMovesOpenWorkAndRetiresTheOldAccount() {
        loginAs(ADMIN);
        Long oldFs = accounts.create(uniqueEmail("fsold"), PASSWORD, "Võ Nghỉ Việc", null, "FS", List.of(q7), 1L)
                .id();
        Long customer = accounts.create(uniqueEmail("kh"), PASSWORD, "Khách Hàng", null, "CUSTOMER", 1L).id();
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"));
        long appointment = id("insert into appointments (customer_id, facility_id, staff_id, type, date, started_at, "
                + "end_at) values (" + customer + ", " + q7 + ", " + oldFs + ", 'CHECKIN', '" + today.plusDays(1)
                + "', now() + interval '1 day', now() + interval '1 day 1 hour') returning id");
        long support = id("insert into support_requests (customer_id, facility_id, category, title, description, "
                + "status, assigned_staff_id) values (" + customer + ", " + q7 + ", 'OTHER', 'Cửa kẹt', 'Mô tả', "
                + "'Assigned', " + oldFs + ") returning id");
        long futureShift = shift(oldFs, today.plusDays(1));
        long pastShift = shift(oldFs, today.minusDays(1));
        long leave = id("insert into staff_leaves (staff_id, from_date, to_date, reason) values (" + oldFs + ", '"
                + today.plusDays(3) + "', '" + today.plusDays(3) + "', 'Việc riêng') returning id");

        loginAs(BOM);
        String email = uniqueEmail("fsnew");
        AccountRequestRow request = requests.submit("FS", email, "Đặng Thay Thế", null, List.of(), null, oldFs);
        assertThat(request.replacesAccountId()).isEqualTo(oldFs);
        assertThat(request.replacesName()).isEqualTo("Võ Nghỉ Việc");
        assertThat(request.facilityIds()).containsExactly(q7);
        assertThatThrownBy(() -> requests.submit("FS", uniqueEmail("fs"), "Thay lần hai", null, List.of(), null,
                oldFs)).isInstanceOf(IllegalStateException.class).hasMessageContaining("yêu cầu thay thế");
        assertThatThrownBy(() -> requests.submit("FM", uniqueEmail("fm"), "Sai vai trò", null, List.of(), null,
                oldFs)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cùng vai trò");

        loginAs(ADMIN);
        AccountRequestRow approved = requests.approve(request.id(), PASSWORD);
        long newFs = approved.createdAccountId();
        assertThat(staffOf("select staff_id from appointments where id = ?", appointment)).isEqualTo(newFs);
        assertThat(staffOf("select assigned_staff_id from support_requests where id = ?", support)).isEqualTo(newFs);
        assertThat(staffOf("select staff_id from staff_shifts where id = ?", futureShift)).isEqualTo(newFs);
        assertThat(staffOf("select staff_id from staff_shifts where id = ?", pastShift)).isEqualTo(oldFs);
        assertThat(jdbc.queryForObject("select status from staff_leaves where id = ?", String.class, leave))
                .isEqualTo("Canceled");
        assertThat(accounts.get(oldFs).status()).isEqualTo("INACTIVE");
        assertThat(accounts.get(oldFs).facilityIds()).isEmpty();
        assertThat(accounts.get(newFs).facilityIds()).containsExactly(q7);
        assertThat(accountRepository.findByEmail(accounts.get(oldFs).email()).orElseThrow().isLoginAllowed())
                .isFalse();
        assertThat(jdbc.queryForObject("select new_value::text from audit_logs where action = "
                + "'ACCOUNT_REQUEST_APPROVED' and entity_id = ?", String.class, String.valueOf(request.id())))
                .contains("replacedAccountId").contains("lịch hẹn").contains("ca làm");
        assertThat(jdbc.queryForObject("select n.body from notifications n join accounts a on a.id = n.account_id "
                + "where a.email = ? and n.type = 'ACCOUNT_REQUEST_REVIEWED' order by n.id desc limit 1",
                String.class, BOM)).contains("Đã chuyển sang người mới").contains("Đã nghỉ việc");

        loginAs(BOM);
        assertThatThrownBy(() -> requests.submit("FS", uniqueEmail("fs"), "Thay người đã nghỉ", null, List.of(), null,
                oldFs)).isInstanceOf(IllegalStateException.class).hasMessageContaining("không còn hoạt động");
    }

    @Test
    void fmReplacementTakesOverTheFacility() {
        loginAs(ADMIN);
        String code = "M" + SEQ.incrementAndGet() + System.nanoTime() % 1000;
        FacilityRow facility = backoffice.createFacility(code, "Cơ sở đổi FM", null, null, null, null, null);
        Long oldFm = accounts.create(uniqueEmail("fmold"), PASSWORD, "Lý Quản Lý Cũ", null, "FM", 1L).id();
        Long idleFm = accounts.create(uniqueEmail("fmidle"), PASSWORD, "FM Chưa Gán", null, "FM", 1L).id();
        backoffice.assignManager(facility.id(), oldFm, true);

        loginAs(BOM);
        assertThatThrownBy(() -> requests.submit("FM", uniqueEmail("fm"), "Thay FM trống", null, List.of(), null,
                idleFm)).isInstanceOf(IllegalStateException.class).hasMessageContaining("không phụ trách");
        AccountRequestRow request = requests.submit("FM", uniqueEmail("fmnew"), "Hồ Quản Lý Mới", null, List.of(),
                null, oldFm);
        assertThat(request.facilityIds()).containsExactly(facility.id());

        loginAs(ADMIN);
        AccountRequestRow approved = requests.approve(request.id(), PASSWORD);
        assertThat(jdbc.queryForObject("select fm_account_id from facilities where id = ?", Long.class,
                facility.id())).isEqualTo(approved.createdAccountId());
        assertThat(accounts.get(oldFm).status()).isEqualTo("INACTIVE");
    }

    // ------------------------------------------------------------------ helpers

    private long shift(Long staffId, LocalDate date) {
        return id("insert into staff_shifts (facility_id, staff_id, shift_date, start_time, end_time) values ("
                + q7 + ", " + staffId + ", '" + date + "', '08:00', '12:00') returning id");
    }

    private Long staffOf(String sql, long id) {
        return jdbc.queryForObject(sql, Long.class, id);
    }

    private static String uniqueEmail(String prefix) {
        return prefix + SEQ.incrementAndGet() + "-" + System.nanoTime() % 100000 + "@example.com";
    }

    private long id(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private int notifications(String email, String type) {
        return count("select count(*) from notifications n join accounts a on a.id = n.account_id "
                + "where a.email = ? and n.type = ?", email, type);
    }

    private static void loginAs(String email) {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(email, null, List.of()));
    }
}
