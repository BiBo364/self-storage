package vn.lemar.selfstorage.staffing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
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
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.lemar.selfstorage.backoffice.application.BackofficeService;
import vn.lemar.selfstorage.backoffice.application.BackofficeService.StaffReport;
import vn.lemar.selfstorage.backoffice.repository.BackofficeReadRepository.StaffReportRow;
import vn.lemar.selfstorage.booking.application.AppointmentQueryService;
import vn.lemar.selfstorage.booking.application.AppointmentService;
import vn.lemar.selfstorage.booking.application.dto.FmFacilityStaffResponse;
import vn.lemar.selfstorage.booking.application.exception.AppointmentConflictException;
import vn.lemar.selfstorage.facility.application.FacilityAdminCommands;
import vn.lemar.selfstorage.facility.application.FacilityAdminCommands.FacilityRow;
import vn.lemar.selfstorage.identity.application.exception.ForbiddenException;
import vn.lemar.selfstorage.staffing.application.StaffLeaveService;
import vn.lemar.selfstorage.staffing.application.StaffLeaveService.LeaveView;
import vn.lemar.selfstorage.staffing.application.StaffProfileService;
import vn.lemar.selfstorage.staffing.application.StaffProfileService.ProfileInput;
import vn.lemar.selfstorage.staffing.application.StaffProfileService.ProfileSummary;
import vn.lemar.selfstorage.staffing.application.StaffProfileService.ProfileView;
import vn.lemar.selfstorage.staffing.application.StaffShiftService;
import vn.lemar.selfstorage.staffing.application.StaffShiftService.CreateInput;
import vn.lemar.selfstorage.staffing.application.StaffShiftService.ShiftView;
import vn.lemar.selfstorage.staffing.application.StaffShiftService.UpdateInput;
import vn.lemar.selfstorage.staffing.application.StaffingException;
import vn.lemar.selfstorage.support.application.SupportService;
import vn.lemar.selfstorage.tenancy.application.TenancyException;
import vn.lemar.selfstorage.tenancy.application.TenancyService;

/**
 * Nhân sự chạy thật trên Postgres với policy {@code staff.shift_required} = {@code true} (seed V32): ca làm việc, nghỉ
 * phép, hồ sơ, luật phân công theo ca / nghỉ phép ở cả 4 đường giao việc, trạng thái ngày trên màn phân công, báo cáo
 * nhân sự. Mỗi test tự tạo FS riêng (không có ca mẫu) và rollback cuối test.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@Transactional
class StaffingIntegrationTest {

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final String FM1 = "fm1@lemar.vn";
    private static final String FM2 = "fm2@lemar.vn";
    private static final String ADMIN = "admin@lemar.vn";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @MockBean
    private JavaMailSender mailSender;

    @Autowired
    private StaffShiftService shifts;
    @Autowired
    private StaffLeaveService leaves;
    @Autowired
    private StaffProfileService profiles;
    @Autowired
    private AppointmentService appointments;
    @Autowired
    private AppointmentQueryService appointmentQuery;
    @Autowired
    private TenancyService tenancy;
    @Autowired
    private SupportService support;
    @Autowired
    private BackofficeService backoffice;
    @Autowired
    private FacilityAdminCommands facilities;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;

    private long q7;
    private long td;
    private long fm1;
    private long customerId;
    private final LocalDate day = LocalDate.now(VN).plusDays(5);

    @BeforeEach
    void setUp() {
        q7 = id("select id from facilities where code = 'Q7'");
        td = id("select id from facilities where code = 'TD'");
        fm1 = id("select id from accounts where email = '" + FM1 + "'");
        customerId = id("select id from accounts where email = 'customer1@lemar.vn'");
        when(mailSender.createMimeMessage()).thenReturn(
                new jakarta.mail.internet.MimeMessage(jakarta.mail.Session.getInstance(new java.util.Properties())));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------------ ca làm việc

    @Test
    void fmCreatesFutureShiftWithAuditAndStaffNotification() {
        long fs = newStaff(q7);
        loginAs(FM1);

        ShiftView shift = shifts.create(new CreateInput(q7, fs, day, LocalTime.of(8, 0), LocalTime.of(12, 0),
                " Ca sáng "));

        assertThat(shift.facilityCode()).isEqualTo("Q7");
        assertThat(shift.note()).isEqualTo("Ca sáng");
        assertThat(audit("STAFF_SHIFT_CREATED", shift.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select new_value ->> 'startTime' from audit_logs where action = "
                + "'STAFF_SHIFT_CREATED' and entity_id = ?", String.class, String.valueOf(shift.id())))
                .isEqualTo("08:00");
        assertThat(notificationBodies(fs, "STAFF_SHIFT_CHANGED")).singleElement().asString()
                .contains("Q7").contains("đã thêm ca 08:00–12:00 ngày " + DMY.format(day));
        assertThat(shifts.facilityShifts(q7, day, day)).extracting(ShiftView::id).contains(shift.id());
    }

    @Test
    void pastShiftIsNotNotified() {
        long fs = newStaff(q7);
        loginAs(FM1);

        shifts.create(new CreateInput(q7, fs, LocalDate.now(VN).minusDays(2), LocalTime.of(8, 0),
                LocalTime.of(12, 0), null));

        assertThat(notificationBodies(fs, "STAFF_SHIFT_CHANGED")).isEmpty();
    }

    @Test
    void overlappingShiftAtAnyFacilityIsRejectedButTouchingIsAllowed() {
        long fs = newStaff(q7);
        assign(fs, td);
        shift(fs, td, day, "08:00", "12:00");
        loginAs(FM1);

        assertThatThrownBy(() -> shifts.create(new CreateInput(q7, fs, day, LocalTime.of(11, 0),
                LocalTime.of(15, 0), null)))
                .isInstanceOf(StaffingException.class)
                .hasMessageContaining("Ca chồng giờ với ca 08:00–12:00")
                .hasMessageContaining("TD")
                .extracting(e -> ((StaffingException) e).kind()).isEqualTo(StaffingException.Kind.CONFLICT);

        ShiftView touching = shifts.create(new CreateInput(q7, fs, day, LocalTime.of(12, 0), LocalTime.of(15, 0),
                null));
        assertThat(touching.startTime()).isEqualTo(LocalTime.of(12, 0));
    }

    @Test
    void shiftInputIsValidated() {
        long fs = newStaff(q7);
        long other = newStaff(td);
        loginAs(FM1);

        assertKind(() -> shifts.create(new CreateInput(q7, fs, day, LocalTime.of(12, 0), LocalTime.of(8, 0),
                null)), StaffingException.Kind.VALIDATION);
        assertKind(() -> shifts.create(new CreateInput(q7, fs, null, LocalTime.of(8, 0), LocalTime.of(9, 0),
                null)), StaffingException.Kind.VALIDATION);
        assertKind(() -> shifts.create(new CreateInput(q7, fs, day, LocalTime.of(8, 0), LocalTime.of(9, 0),
                "x".repeat(501))), StaffingException.Kind.VALIDATION);
        assertKind(() -> shifts.create(new CreateInput(null, fs, day, LocalTime.of(8, 0), LocalTime.of(9, 0),
                null)), StaffingException.Kind.VALIDATION);
        assertKind(() -> shifts.create(new CreateInput(q7, other, day, LocalTime.of(8, 0), LocalTime.of(9, 0),
                null)), StaffingException.Kind.VALIDATION);
        assertKind(() -> shifts.create(new CreateInput(q7, null, day, LocalTime.of(8, 0), LocalTime.of(9, 0),
                null)), StaffingException.Kind.VALIDATION);
        assertKind(() -> shifts.facilityShifts(q7, day, day.plusDays(62)), StaffingException.Kind.VALIDATION);
        assertKind(() -> shifts.facilityShifts(q7, day, day.minusDays(1)), StaffingException.Kind.VALIDATION);
        assertThatThrownBy(() -> shifts.facilityShifts(td, null, null)).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void shiftCannotBePlacedOnApprovedLeaveDay() {
        long fs = newStaff(q7);
        approvedLeave(fs, day, day);
        loginAs(FM1);

        assertThatThrownBy(() -> shifts.create(new CreateInput(q7, fs, day, LocalTime.of(8, 0),
                LocalTime.of(12, 0), null))).isInstanceOf(StaffingException.class)
                .hasMessageContaining("FS đang nghỉ phép ngày " + DMY.format(day));
    }

    @Test
    void deletingOrShrinkingShiftUnderAssignedAppointmentIsBlocked() {
        long fs = newStaff(q7);
        long shiftId = shift(fs, q7, day, "07:00", "12:00");
        long appointmentId = pendingAppointment(fs, q7, day, "08:00", "10:00", "CHECKIN");
        loginAs(FM1);

        assertThatThrownBy(() -> shifts.delete(shiftId)).isInstanceOf(StaffingException.class)
                .hasMessageContaining("lịch check-in #" + appointmentId + " 08:00–10:00 (FM đổi FS)");
        assertThatThrownBy(() -> shifts.update(shiftId, new UpdateInput(day, LocalTime.of(9, 0),
                LocalTime.of(12, 0), null))).isInstanceOf(StaffingException.class)
                .hasMessageContaining("#" + appointmentId);
        assertThatThrownBy(() -> shifts.update(shiftId, new UpdateInput(day.plusDays(1), LocalTime.of(7, 0),
                LocalTime.of(12, 0), null))).isInstanceOf(StaffingException.class);

        ShiftView shrunk = shifts.update(shiftId, new UpdateInput(day, LocalTime.of(8, 0), LocalTime.of(10, 0),
                "Thu hẹp"));
        assertThat(shrunk.endTime()).isEqualTo(LocalTime.of(10, 0));
        assertThat(audit("STAFF_SHIFT_UPDATED", shiftId)).isEqualTo(1);
        assertThat(notificationBodies(fs, "STAFF_SHIFT_CHANGED")).singleElement().asString()
                .contains("đổi ca 07:00–12:00 ngày " + DMY.format(day) + " thành 08:00–10:00");
        assertThat(shifts.facilityShifts(q7, day, day)).extracting(ShiftView::id).contains(shiftId);
    }

    @Test
    void shiftNotCoveringAnyAppointmentIsDeletedAndNotified() {
        long fs = newStaff(q7);
        long morning = shift(fs, q7, day, "07:00", "12:00");
        shift(fs, q7, day, "13:00", "17:00");
        pendingAppointment(fs, q7, day, "13:00", "15:00", "RETURN");
        loginAs(FM1);

        shifts.delete(morning);

        assertThat(count("select count(*) from staff_shifts where id = ?", morning)).isZero();
        assertThat(audit("STAFF_SHIFT_DELETED", morning)).isEqualTo(1);
        assertThat(notificationBodies(fs, "STAFF_SHIFT_CHANGED")).singleElement().asString()
                .contains("đã xoá ca 07:00–12:00");
        assertKind(() -> shifts.delete(morning), StaffingException.Kind.NOT_FOUND);
        assertKind(() -> shifts.update(morning, new UpdateInput(day, LocalTime.of(7, 0), LocalTime.of(8, 0),
                null)), StaffingException.Kind.NOT_FOUND);
    }

    @Test
    void shiftOfStaffRemovedFromFacilityCanOnlyBeDeleted() {
        long fs = newStaff(q7);
        long shiftId = shift(fs, q7, day, "07:00", "12:00");
        jdbc.update("delete from account_facility_assignments where account_id = ?", fs);
        loginAs(FM1);

        assertKind(() -> shifts.update(shiftId, new UpdateInput(day, LocalTime.of(8, 0), LocalTime.of(12, 0),
                null)), StaffingException.Kind.CONFLICT);
        shifts.delete(shiftId);
        assertThat(count("select count(*) from staff_shifts where id = ?", shiftId)).isZero();
    }

    @Test
    void staffSeesOwnShiftsAtEveryFacility() {
        long fs = newStaff(q7);
        assign(fs, td);
        shift(fs, q7, day, "07:00", "12:00");
        shift(fs, td, day.plusDays(1), "13:00", "17:00");
        long other = newStaff(q7);
        shift(other, q7, day, "07:00", "12:00");
        loginAs(email(fs));

        assertThat(shifts.myShifts(day, day.plusDays(1))).extracting(ShiftView::facilityCode)
                .containsExactly("Q7", "TD");
        assertThat(shifts.myShifts(null, null)).allSatisfy(s -> assertThat(s.staffId()).isEqualTo(fs));
    }

    // ------------------------------------------------------------------ luật phân công

    @Test
    void checkinAssignNeedsShiftCoveringTheWholeAppointment() {
        long fs = newStaff(q7);
        long appointmentId = scheduledCheckin(day, "08:00", "10:00");
        loginAs(FM1);

        assertThatThrownBy(() -> appointments.assignStaff(appointmentId, fs, fm1))
                .isInstanceOf(AppointmentConflictException.class)
                .hasMessage("FS không có ca làm việc tại cơ sở bao trọn giờ hẹn 08:00–10:00 ngày "
                        + DMY.format(day));

        long shiftId = shift(fs, q7, day, "09:00", "12:00");
        assertThatThrownBy(() -> appointments.assignStaff(appointmentId, fs, fm1))
                .isInstanceOf(AppointmentConflictException.class);

        jdbc.update("update staff_shifts set start_time = '08:00' where id = ?", shiftId);
        assertThat(appointments.assignStaff(appointmentId, fs, fm1).staffId()).isEqualTo(fs);
    }

    @Test
    void shiftAtAnotherFacilityDoesNotCount() {
        long fs = newStaff(q7);
        assign(fs, td);
        shift(fs, td, day, "07:00", "21:00");
        long appointmentId = scheduledCheckin(day, "08:00", "10:00");
        loginAs(FM1);

        assertThatThrownBy(() -> appointments.assignStaff(appointmentId, fs, fm1))
                .isInstanceOf(AppointmentConflictException.class);
    }

    @Test
    void policyOffSkipsShiftButLeaveStillBlocks() {
        jdbc.update("update policies set value = 'false' where key = 'staff.shift_required'");
        long fs = newStaff(q7);
        long other = newStaff(q7);
        approvedLeave(other, day, day);
        long first = scheduledCheckin(day, "08:00", "10:00");
        long second = scheduledCheckin(day, "13:00", "15:00");
        loginAs(FM1);

        assertThat(appointments.assignStaff(first, fs, fm1).staffId()).isEqualTo(fs);
        assertThatThrownBy(() -> appointments.assignStaff(second, other, fm1))
                .isInstanceOf(AppointmentConflictException.class)
                .hasMessage("FS đang nghỉ phép ngày " + DMY.format(day));
    }

    @Test
    void reassignChecksTheNewStaff() {
        long fs = newStaff(q7);
        long replacement = newStaff(q7);
        shift(fs, q7, day, "07:00", "21:00");
        long appointmentId = scheduledCheckin(day, "08:00", "10:00");
        loginAs(FM1);
        appointments.assignStaff(appointmentId, fs, fm1);

        assertThatThrownBy(() -> appointments.reassignStaff(appointmentId, replacement, fm1))
                .isInstanceOf(AppointmentConflictException.class)
                .hasMessageContaining("bao trọn giờ hẹn 08:00–10:00");

        shift(replacement, q7, day, "07:00", "21:00");
        assertThat(appointments.reassignStaff(appointmentId, replacement, fm1).staffId()).isEqualTo(replacement);
    }

    @Test
    void returnAssignChecksShiftOfTheBookedSlot() {
        long fs = newStaff(q7);
        long contractId = activeContract();
        long requestId = id("insert into return_requests (contract_id, customer_id, facility_id, preferred_date) "
                + "values (" + contractId + ", " + customerId + ", " + q7 + ", '" + day + "') returning id");
        loginAs(FM1);

        assertThatThrownBy(() -> tenancy.assign(requestId, fs)).isInstanceOf(TenancyException.class)
                .hasMessageContaining("FS không có ca làm việc tại cơ sở bao trọn giờ hẹn");

        shift(fs, q7, day, "07:00", "21:00");
        assertThat(tenancy.assign(requestId, fs).status()).isEqualTo("Assigned");
    }

    @Test
    void returnAssignPicksTheFirstFreeSlotInsideTheShift() {
        long fs = newStaff(q7);
        long contractId = activeContract();
        long requestId = id("insert into return_requests (contract_id, customer_id, facility_id, preferred_date) "
                + "values (" + contractId + ", " + customerId + ", " + q7 + ", '" + day + "') returning id");
        // FS chỉ có ca chiều: khung 08:00 còn trống nhưng ngoài ca → phải xếp vào khung 13:00, không 409.
        shift(fs, q7, day, "13:00", "16:00");
        loginAs(FM1);

        var assigned = tenancy.assign(requestId, fs);
        assertThat(assigned.status()).isEqualTo("Assigned");
        assertThat(assigned.appointmentStart().atZone(VN).toLocalTime()).isEqualTo(java.time.LocalTime.of(13, 0));
    }

    @Test
    void supportAssignNeedsShiftTodayAndNoLeave() {
        long fs = newStaff(q7);
        long requestId = id("insert into support_requests (customer_id, facility_id, category, title, description) "
                + "values (" + customerId + ", " + q7 + ", 'OTHER', 'Hỏi', 'Mô tả') returning id");
        LocalDate today = LocalDate.now(VN);
        loginAs(FM1);

        assertThatThrownBy(() -> support.assign(requestId, fs)).isInstanceOf(TenancyException.class)
                .hasMessage("FS không có ca làm việc tại cơ sở hôm nay (" + DMY.format(today) + ")");

        long leaveFs = newStaff(q7);
        shift(leaveFs, q7, today, "00:00", "23:59");
        approvedLeave(leaveFs, today, today);
        assertThatThrownBy(() -> support.assign(requestId, leaveFs)).isInstanceOf(TenancyException.class)
                .hasMessage("FS đang nghỉ phép ngày " + DMY.format(today));

        shift(fs, q7, today, "00:00", "23:59");
        assertThat(support.assign(requestId, fs).status()).isEqualTo("Assigned");
    }

    @Test
    void assignmentScreenShowsShiftLeaveAndOpenWork() {
        long fs = newStaff(q7);
        long onLeave = newStaff(q7);
        shift(fs, q7, day, "13:00", "17:00");
        shift(fs, q7, day, "07:00", "12:00");
        pendingAppointment(fs, q7, day, "08:00", "10:00", "CHECKIN");
        pendingAppointment(fs, q7, day, "13:00", "15:00", "RETURN");
        pendingAppointment(fs, q7, day.plusDays(1), "08:00", "10:00", "CHECKIN");
        jdbc.update("insert into support_requests (customer_id, facility_id, category, title, description, status, "
                + "assigned_staff_id) values (?, ?, 'OTHER', 'Hỏi', 'Mô tả', 'Assigned', ?)", customerId, q7, fs);
        approvedLeave(onLeave, day, day);
        loginAs(FM1);

        List<FmFacilityStaffResponse> staff = appointmentQuery.staffOfFacility(q7, day);

        FmFacilityStaffResponse row = staff.stream().filter(s -> s.id().equals(fs)).findFirst().orElseThrow();
        assertThat(row.date()).isEqualTo(day);
        assertThat(row.shift().startTime()).isEqualTo(LocalTime.of(7, 0));
        assertThat(row.shifts()).hasSize(2);
        assertThat(row.onLeave()).isFalse();
        assertThat(row.openWork().checkins()).isEqualTo(1);
        assertThat(row.openWork().returns()).isEqualTo(1);
        assertThat(row.openSupport()).isEqualTo(1);
        FmFacilityStaffResponse leaveRow = staff.stream().filter(s -> s.id().equals(onLeave)).findFirst()
                .orElseThrow();
        assertThat(leaveRow.shift()).isNull();
        assertThat(leaveRow.onLeave()).isTrue();
        assertThat(appointmentQuery.staffOfFacility(q7, null)).isNotEmpty();
    }

    // ------------------------------------------------------------------ nghỉ phép

    @Test
    void staffRequestsLeaveAndFacilityManagersAreNotified() {
        long fs = newStaff(q7);
        assign(fs, td);
        loginAs(email(fs));

        LeaveView leave = leaves.request(day, day.plusDays(2), "Việc gia đình");

        assertThat(leave.status()).isEqualTo("Pending");
        assertThat(leave.cancelable()).isTrue();
        assertThat(audit("STAFF_LEAVE_REQUESTED", leave.id())).isEqualTo(1);
        assertThat(notificationBodies(fm1, "STAFF_LEAVE_REQUESTED")).singleElement().asString()
                .contains("Việc gia đình").contains(DMY.format(day));
        assertThat(notificationBodies(id("select id from accounts where email = '" + FM2 + "'"),
                "STAFF_LEAVE_REQUESTED")).hasSize(1);
        assertThat(leaves.myLeaves()).extracting(LeaveView::id).containsExactly(leave.id());

        assertThatThrownBy(() -> leaves.request(day.plusDays(2), day.plusDays(4), "Trùng"))
                .isInstanceOf(StaffingException.class).hasMessageContaining("Trùng đơn nghỉ #" + leave.id());
        assertKind(() -> leaves.request(day, day.minusDays(1), "x"), StaffingException.Kind.VALIDATION);
        assertKind(() -> leaves.request(null, day, "x"), StaffingException.Kind.VALIDATION);
        assertKind(() -> leaves.request(LocalDate.now(VN).minusDays(1), day, "x"),
                StaffingException.Kind.VALIDATION);
        assertKind(() -> leaves.request(day.plusDays(10), day.plusDays(10), " "), StaffingException.Kind.VALIDATION);
    }

    @Test
    void approveIsBlockedByOpenWorkInRangeAtAnyFacility() {
        long fs = newStaff(q7);
        assign(fs, td);
        long leaveId = pendingLeave(fs, day, day.plusDays(1));
        long appointmentId = pendingAppointment(fs, td, day.plusDays(1), "08:00", "10:00", "CHECKIN");
        pendingAppointment(fs, q7, day.plusDays(3), "08:00", "10:00", "CHECKIN");
        long supportId = id("insert into support_requests (customer_id, facility_id, category, title, description, "
                + "status, assigned_staff_id) values (" + customerId + ", " + q7 + ", 'OTHER', 'Hỏi', 'Mô tả', "
                + "'Assigned', " + fs + ") returning id");
        loginAs(FM1);

        assertThatThrownBy(() -> leaves.approve(leaveId, null)).isInstanceOf(StaffingException.class)
                .hasMessage("FS còn việc trong thời gian nghỉ: lịch check-in #" + appointmentId + " ngày "
                        + DMY.format(day.plusDays(1)) + " (FM đổi FS), yêu cầu hỗ trợ #" + supportId
                        + " (FM giao lại). Xử lý các việc này trước khi duyệt nghỉ phép");
        assertThat(status(leaveId)).isEqualTo("Pending");
    }

    @Test
    void approveDeletesShiftsInRangeAuditsAndNotifies() {
        long fs = newStaff(q7);
        long inRange = shift(fs, q7, day, "07:00", "21:00");
        long outside = shift(fs, q7, day.plusDays(2), "07:00", "21:00");
        long leaveId = pendingLeave(fs, day, day.plusDays(1));
        loginAs(FM1);

        LeaveView approved = leaves.approve(leaveId, " Đồng ý ");

        assertThat(approved.status()).isEqualTo("Approved");
        assertThat(approved.reviewedBy()).isEqualTo(fm1);
        assertThat(approved.reviewNote()).isEqualTo("Đồng ý");
        assertThat(approved.cancelable()).isTrue();
        assertThat(count("select count(*) from staff_shifts where id = ?", inRange)).isZero();
        assertThat(count("select count(*) from staff_shifts where id = ?", outside)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select reason from audit_logs where action = 'STAFF_SHIFT_DELETED' "
                + "and entity_id = ?", String.class, String.valueOf(inRange))).isEqualTo("Duyệt nghỉ phép #"
                + leaveId);
        assertThat(audit("STAFF_LEAVE_APPROVED", leaveId)).isEqualTo(1);
        assertThat(notificationBodies(fs, "STAFF_LEAVE_REVIEWED")).singleElement().asString()
                .contains("đã được duyệt").contains("Ghi chú: Đồng ý");
        assertKind(() -> leaves.approve(leaveId, null), StaffingException.Kind.CONFLICT);
        assertThat(leaves.facilityLeaves(q7, "Approved")).extracting(LeaveView::id).contains(leaveId);
    }

    @Test
    void rejectRecordsNoteAndNotifies() {
        long fs = newStaff(q7);
        long leaveId = pendingLeave(fs, day, day);
        loginAs(FM1);

        assertKind(() -> leaves.reject(leaveId, "x".repeat(501)), StaffingException.Kind.VALIDATION);
        LeaveView rejected = leaves.reject(leaveId, null);

        assertThat(rejected.status()).isEqualTo("Rejected");
        assertThat(rejected.cancelable()).isFalse();
        assertThat(audit("STAFF_LEAVE_REJECTED", leaveId)).isEqualTo(1);
        assertThat(notificationBodies(fs, "STAFF_LEAVE_REVIEWED")).singleElement().asString()
                .contains("bị từ chối").doesNotContain("Ghi chú");
        assertThat(leaves.facilityLeaves(q7, null)).extracting(LeaveView::id).contains(leaveId);
        assertKind(() -> leaves.reject(leaveId, null), StaffingException.Kind.CONFLICT);
        assertKind(() -> leaves.reject(-1L, null), StaffingException.Kind.NOT_FOUND);
    }

    @Test
    void onlyManagerOfOneOfTheStaffFacilitiesReviews() {
        long fs = newStaff(q7);
        long leaveId = pendingLeave(fs, day, day);
        long unassigned = newStaff(q7);
        jdbc.update("delete from account_facility_assignments where account_id = ?", unassigned);
        long orphanLeave = pendingLeave(unassigned, day, day);
        loginAs(FM2);

        assertThatThrownBy(() -> leaves.approve(leaveId, null)).isInstanceOf(ForbiddenException.class);
        assertKind(() -> leaves.approve(orphanLeave, null), StaffingException.Kind.FORBIDDEN);
        assertThatThrownBy(() -> leaves.facilityLeaves(q7, null)).isInstanceOf(ForbiddenException.class);

        assign(fs, td);
        assertThat(leaves.approve(leaveId, null).status()).isEqualTo("Approved");
    }

    @Test
    void staffCancelsPendingOrFutureApprovedLeaveOnly() {
        long fs = newStaff(q7);
        long pending = pendingLeave(fs, day, day);
        long futureApproved = approvedLeave(fs, day.plusDays(3), day.plusDays(3));
        long started = approvedLeave(fs, LocalDate.now(VN), LocalDate.now(VN).plusDays(1));
        long other = pendingLeave(newStaff(q7), day, day);
        loginAs(email(fs));

        assertThat(leaves.cancel(pending).status()).isEqualTo("Canceled");
        assertThat(leaves.cancel(futureApproved).status()).isEqualTo("Canceled");
        assertThat(audit("STAFF_LEAVE_CANCELED", pending)).isEqualTo(1);
        assertKind(() -> leaves.cancel(started), StaffingException.Kind.CONFLICT);
        assertKind(() -> leaves.cancel(pending), StaffingException.Kind.CONFLICT);
        assertKind(() -> leaves.cancel(other), StaffingException.Kind.NOT_FOUND);
    }

    // ------------------------------------------------------------------ hồ sơ

    @Test
    void idNumberIsVisibleToAdminOnlyAndMaskedInAudit() throws Exception {
        long fs = newStaff(q7);
        loginAs(ADMIN);

        ProfileView saved = profiles.update(fs, new ProfileInput("Nhân viên kho", LocalDate.of(2025, 3, 1),
                " 079123456789 ", "Nguyễn Văn A", "0901 234 567", "Ca sáng"));

        assertThat(saved.idNumber()).isEqualTo("079123456789");
        assertThat(profiles.get(fs).position()).isEqualTo("Nhân viên kho");
        assertThat(jdbc.queryForObject("select new_value ->> 'idNumber' from audit_logs where action = "
                + "'STAFF_PROFILE_UPDATED' and entity_id = ?", String.class, String.valueOf(fs)))
                .isEqualTo("********6789");
        assertThat(jdbc.queryForObject("select old_value ->> 'idNumber' from audit_logs where action = "
                + "'STAFF_PROFILE_UPDATED' and entity_id = ?", String.class, String.valueOf(fs))).isNull();

        loginAs(FM1);
        ProfileSummary forManager = profiles.forFacilityManager(fs);
        assertThat(forManager.position()).isEqualTo("Nhân viên kho");
        assertThat(objectMapper.writeValueAsString(forManager)).doesNotContain("idNumber").doesNotContain("6789");

        loginAs(email(fs));
        assertThat(objectMapper.writeValueAsString(profiles.mine())).doesNotContain("idNumber")
                .contains("Nguyễn Văn A");
        assertThatThrownBy(() -> profiles.get(fs)).isInstanceOf(ForbiddenException.class);

        loginAs(FM2);
        assertThatThrownBy(() -> profiles.forFacilityManager(fs)).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void profileOnlyForStaffAccountsAndValidated() {
        long fs = newStaff(q7);
        loginAs(ADMIN);

        assertThat(profiles.get(fm1).role()).isEqualTo("FM");
        assertThat(profiles.get(fm1).position()).isNull();
        assertKind(() -> profiles.get(customerId), StaffingException.Kind.NOT_FOUND);
        assertKind(() -> profiles.update(fs, new ProfileInput(null, null, "12-34", null, null, null)),
                StaffingException.Kind.VALIDATION);
        assertKind(() -> profiles.update(fs, new ProfileInput(null, null, null, null, "abc", null)),
                StaffingException.Kind.VALIDATION);
        assertKind(() -> profiles.update(fs, new ProfileInput("x".repeat(101), null, null, null, null, null)),
                StaffingException.Kind.VALIDATION);
        assertKind(() -> profiles.update(fs, new ProfileInput(null, LocalDate.now(VN).plusDays(1), null, null, null,
                null)), StaffingException.Kind.VALIDATION);

        profiles.update(fs, new ProfileInput("A", null, "ab1234", null, null, null));
        ProfileView cleared = profiles.update(fs, new ProfileInput(" ", null, null, null, null, null));
        assertThat(cleared.position()).isNull();
        assertThat(cleared.idNumber()).isNull();
        assertThat(jdbc.queryForObject("select old_value ->> 'idNumber' from audit_logs where action = "
                + "'STAFF_PROFILE_UPDATED' and entity_id = ? order by id desc limit 1", String.class,
                String.valueOf(fs))).isEqualTo("**1234");

        loginAs(FM1);
        assertKind(() -> profiles.forFacilityManager(fm1), StaffingException.Kind.NOT_FOUND);
    }

    // ------------------------------------------------------------------ báo cáo + headcount

    @Test
    void staffReportCountsCompletedWorkShiftHoursAndLeaveDays() {
        long fs = newStaff(q7);
        LocalDate today = LocalDate.now(VN);
        LocalDate from = today.minusDays(3);
        long checkin = doneAppointment(fs, q7, today.minusDays(1), "CHECKIN");
        doneAppointment(fs, q7, today.minusDays(1), "RETURN");
        doneAppointment(fs, td, today.minusDays(1), "CHECKIN");
        long contractId = activeContract();
        long orderId = id("select order_id from rental_contracts where id = " + contractId);
        long unitId = id("select unit_id from rental_contracts where id = " + contractId);
        jdbc.update("insert into handover_records (order_id, appointment_id, unit_id, result, completed_at) "
                + "values (?, ?, ?, 'COMPLETED', now())", orderId, checkin, unitId);
        long returnId = id("insert into return_requests (contract_id, customer_id, facility_id, preferred_date, "
                + "status, assigned_staff_id) values (" + contractId + ", " + customerId + ", " + q7 + ", '"
                + today + "', 'Completed', " + fs + ") returning id");
        jdbc.update("insert into checkout_records (return_request_id, contract_id, unit_id, result, completed_at) "
                + "values (?, ?, ?, 'COMPLETED', now())", returnId, contractId, unitId);
        jdbc.update("insert into support_requests (customer_id, facility_id, category, title, description, status, "
                + "assigned_staff_id, resolved_at) values (?, ?, 'OTHER', 'Hỏi', 'Mô tả', 'Resolved', ?, now())",
                customerId, q7, fs);
        shift(fs, q7, today, "07:00", "12:30");
        shift(fs, q7, from.minusDays(1), "07:00", "12:00");
        approvedLeave(fs, from.minusDays(2), from.plusDays(1));
        loginAs(FM1);

        StaffReport report = backoffice.facilityStaffReport(q7, from, today);

        StaffReportRow row = report.rows().stream().filter(r -> r.staffId().equals(fs)).findFirst().orElseThrow();
        assertThat(row.completedCheckins()).isEqualTo(1);
        assertThat(row.completedReturns()).isEqualTo(1);
        assertThat(row.handoversCompleted()).isEqualTo(1);
        assertThat(row.checkoutsCompleted()).isEqualTo(1);
        assertThat(row.supportResolved()).isEqualTo(1);
        assertThat(row.shiftHours()).isEqualByComparingTo("5.5");
        assertThat(row.leaveDays()).isEqualTo(2);

        StaffReport chain = backoffice.chainStaffReport(null, from, today);
        assertThat(chain.rows().stream().filter(r -> r.staffId().equals(fs)).findFirst().orElseThrow()
                .completedCheckins()).isEqualTo(2);
        StaffReport defaults = backoffice.chainStaffReport(td, null, null);
        assertThat(defaults.from()).isEqualTo(today.withDayOfMonth(1));
        assertThat(defaults.to()).isEqualTo(today.withDayOfMonth(today.lengthOfMonth()));
        assertThatThrownBy(() -> backoffice.chainStaffReport(-1L, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> backoffice.chainStaffReport(null, today, today.minusDays(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> backoffice.facilityStaffReport(td, null, null))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void facilityListCountsActiveAssignedStaff() {
        long before = facilities.list().stream().filter(f -> f.id().equals(q7)).findFirst().orElseThrow()
                .staffCount();
        newStaff(q7);
        long banned = newStaff(q7);
        jdbc.update("update accounts set status = 'BANNED' where id = ?", banned);

        FacilityRow after = facilities.list().stream().filter(f -> f.id().equals(q7)).findFirst().orElseThrow();
        assertThat(after.staffCount()).isEqualTo(before + 1);
    }

    @Test
    void booleanPolicyOnlyAcceptsTrueOrFalse() {
        loginAs("bom1@lemar.vn");

        assertThatThrownBy(() -> backoffice.updatePolicy("staff.shift_required", "yes"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(backoffice.updatePolicy("staff.shift_required", "false").value()).isEqualTo("false");
    }

    // ------------------------------------------------------------------ dữ liệu

    private long newStaff(long facilityId) {
        int n = SEQ.incrementAndGet();
        long id = id("insert into accounts (email, password_hash, email_verified_at, role_id, status, name) values "
                + "('hr-fs" + n + "-" + System.nanoTime() % 100000 + "@lemar.vn', 'x', now(), "
                + "(select id from roles where name = 'FS'), 'ACTIVE', " + (n % 2 == 0 ? "null" : "'FS HR " + n + "'")
                + ") returning id");
        assign(id, facilityId);
        return id;
    }

    private void assign(long staffId, long facilityId) {
        jdbc.update("insert into account_facility_assignments (account_id, facility_id) values (?, ?)", staffId,
                facilityId);
    }

    private long shift(long staffId, long facilityId, LocalDate date, String start, String end) {
        return id("insert into staff_shifts (facility_id, staff_id, shift_date, start_time, end_time) values ("
                + facilityId + ", " + staffId + ", '" + date + "', '" + start + "', '" + end + "') returning id");
    }

    private long pendingLeave(long staffId, LocalDate from, LocalDate to) {
        return id("insert into staff_leaves (staff_id, from_date, to_date, reason) values (" + staffId + ", '" + from
                + "', '" + to + "', 'Nghỉ') returning id");
    }

    private long approvedLeave(long staffId, LocalDate from, LocalDate to) {
        return id("insert into staff_leaves (staff_id, from_date, to_date, reason, status) values (" + staffId
                + ", '" + from + "', '" + to + "', 'Nghỉ', 'Approved') returning id");
    }

    private long pendingAppointment(long staffId, long facilityId, LocalDate date, String start, String end,
                                    String type) {
        return id("insert into appointments (customer_id, facility_id, staff_id, type, date, started_at, end_at) "
                + "values (" + customerId + ", " + facilityId + ", " + staffId + ", '" + type + "', '" + date
                + "', '" + date + "T" + start + ":00+07:00', '" + date + "T" + end + ":00+07:00') returning id");
    }

    private long doneAppointment(long staffId, long facilityId, LocalDate date, String type) {
        return id("insert into appointments (customer_id, facility_id, staff_id, type, date, started_at, end_at, "
                + "arrived_at, status) values (" + customerId + ", " + facilityId + ", " + staffId + ", '" + type
                + "', '" + date + "', '" + date + "T08:00:00+07:00', '" + date + "T10:00:00+07:00', '" + date
                + "T08:05:00+07:00', 'Done') returning id");
    }

    /** Đơn {@code Scheduled} + lịch check-in chưa có FS ở Q7, khoang riêng. */
    private long scheduledCheckin(LocalDate date, String start, String end) {
        long unitId = newUnit("Reserved");
        long requestId = id("insert into rental_requests (facility_id, customer_name, normalized_customer_email, "
                + "customer_phone, unit_type_id, start_date, period, unit_id, status) values (" + q7
                + ", 'Khách', 'customer1@lemar.vn', '0900000000', (select id from unit_types where name = 'Small'), "
                + "current_date + 7, 3, " + unitId + ", 'Converted') returning id");
        long orderId = id("insert into rental_orders (request_id, customer_id, unit_id, status, expires_at) values ("
                + requestId + ", " + customerId + ", " + unitId + ", 'Scheduled', now() + interval '30 days') "
                + "returning id");
        long appointmentId = id("insert into appointments (customer_id, facility_id, type, date, started_at, end_at) "
                + "values (" + customerId + ", " + q7 + ", 'CHECKIN', '" + date + "', '" + date + "T" + start
                + ":00+07:00', '" + date + "T" + end + ":00+07:00') returning id");
        jdbc.update("insert into rental_appointments (appointment_id, order_id) values (?, ?)", appointmentId,
                orderId);
        return appointmentId;
    }

    /** Hợp đồng {@code Active} ở Q7 (đơn {@code Done}, khoang {@code Rented}). */
    private long activeContract() {
        String suffix = SEQ.incrementAndGet() + "-" + System.nanoTime() % 100000;
        long unitId = newUnit("Rented");
        long requestId = id("insert into rental_requests (facility_id, customer_name, normalized_customer_email, "
                + "customer_phone, unit_type_id, start_date, period, status, unit_id) values (" + q7
                + ", 'Khách', 'customer1@lemar.vn', '0900000000', (select id from unit_types where name = 'Small'), "
                + "current_date, 3, 'Converted', " + unitId + ") returning id");
        long orderId = id("insert into rental_orders (request_id, customer_id, unit_id, status) values ("
                + requestId + ", " + customerId + ", " + unitId + ", 'Done') returning id");
        LocalDate end = LocalDate.now(VN).plusMonths(2);
        return id("insert into rental_contracts (order_id, customer_id, unit_id, code, terms_version, monthly_price, "
                + "deposit_amount, period, start_date, end_date, signed_at, status) values (" + orderId + ", "
                + customerId + ", " + unitId + ", 'HD-HR-" + suffix + "', 'builtin-v1', 100000, 500000, 3, '"
                + end.minusMonths(3) + "', '" + end + "', now(), 'Active') returning id");
    }

    private long newUnit(String status) {
        return id("insert into storage_units (code, facility_id, unit_type_id, status) values ('HR-"
                + SEQ.incrementAndGet() + "-" + System.nanoTime() % 100000 + "', " + q7
                + ", (select id from unit_types where name = 'Small'), '" + status + "') returning id");
    }

    private String email(long accountId) {
        return jdbc.queryForObject("select email from accounts where id = ?", String.class, accountId);
    }

    private String status(long leaveId) {
        return jdbc.queryForObject("select status from staff_leaves where id = ?", String.class, leaveId);
    }

    private int audit(String action, long entityId) {
        return count("select count(*) from audit_logs where action = ? and entity_id = ?", action,
                String.valueOf(entityId));
    }

    private List<String> notificationBodies(long accountId, String type) {
        return jdbc.queryForList("select body from notifications where account_id = ? and type = ? order by id",
                String.class, accountId, type);
    }

    private long id(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private static void assertKind(org.assertj.core.api.ThrowableAssert.ThrowingCallable call,
                                   StaffingException.Kind kind) {
        assertThatThrownBy(call).isInstanceOf(StaffingException.class)
                .extracting(e -> ((StaffingException) e).kind()).isEqualTo(kind);
    }

    private static void loginAs(String email) {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(email, null, List.of()));
    }
}
