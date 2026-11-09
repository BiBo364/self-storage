package vn.lemar.selfstorage.staffing.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.lemar.selfstorage.ApiEnvelope;
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

/**
 * Nhân sự: ca làm việc (FM quản lý, FS xem), nghỉ phép (FS gửi / huỷ, FM duyệt), hồ sơ nhân viên (ADMIN / BOM sửa, FM /
 * FS xem bản không có số giấy tờ). Namespace role-bound theo {@code SecurityConfig}; quyền theo cơ sở kiểm ở service.
 */
@RestController
public class StaffingController {

    private final StaffShiftService shifts;
    private final StaffLeaveService leaves;
    private final StaffProfileService profiles;

    public StaffingController(StaffShiftService shifts, StaffLeaveService leaves, StaffProfileService profiles) {
        this.shifts = shifts;
        this.leaves = leaves;
        this.profiles = profiles;
    }

    // ------------------------------------------------------------------ ca làm việc

    @Operation(summary = "Ca làm việc của cơ sở",
            description = "Ca của FS tại cơ sở trong `from..to` (mặc định tuần hiện tại, tối đa 62 ngày). "
                    + "Quyền `staff_shift.manage` theo cơ sở.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Danh sách ca"),
            @ApiResponse(responseCode = "400", description = "Khoảng ngày không hợp lệ"),
            @ApiResponse(responseCode = "403", description = "Ngoài phạm vi cơ sở"),
            @ApiResponse(responseCode = "404", description = "Cơ sở không tồn tại")
    })
    @GetMapping("/api/fm/staff-shifts")
    public ResponseEntity<ApiEnvelope<List<ShiftView>>> facilityShifts(@RequestParam Long facilityId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ok("Ca làm việc của cơ sở", shifts.facilityShifts(facilityId, from, to));
    }

    @Operation(summary = "Thêm ca cho FS",
            description = "FS phải là FS đang hoạt động của cơ sở (400). Giờ bắt đầu < giờ kết thúc, cùng ngày (400). "
                    + "409: chồng giờ với ca khác của FS ở bất kỳ cơ sở nào; FS nghỉ phép đã duyệt ngày đó. "
                    + "Ca ở tương lai → FS nhận `STAFF_SHIFT_CHANGED`. Audit `STAFF_SHIFT_CREATED`.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Đã thêm ca"),
            @ApiResponse(responseCode = "400", description = "Dữ liệu sai / không phải FS của cơ sở"),
            @ApiResponse(responseCode = "403", description = "Ngoài phạm vi cơ sở"),
            @ApiResponse(responseCode = "409", description = "Chồng giờ / FS nghỉ phép")
    })
    @PostMapping("/api/fm/staff-shifts")
    public ResponseEntity<ApiEnvelope<ShiftView>> createShift(@Valid @RequestBody ShiftBody body) {
        return ok("Đã thêm ca làm việc", shifts.create(new CreateInput(body.facilityId(), body.staffId(),
                body.shiftDate(), body.startTime(), body.endTime(), body.note())));
    }

    @Operation(summary = "Sửa ca",
            description = "Đổi ngày / giờ / ghi chú; như thêm ca, cộng 409 khi lịch hẹn chờ đã giao cho FS đang nằm "
                    + "trong ca cũ không còn ca nào bao trọn sau khi sửa, hoặc FS không còn thuộc cơ sở. "
                    + "Audit `STAFF_SHIFT_UPDATED`.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Đã sửa ca"),
            @ApiResponse(responseCode = "404", description = "Không tìm thấy ca"),
            @ApiResponse(responseCode = "409", description = "Chồng giờ / nghỉ phép / lịch hẹn rơi khỏi ca")
    })
    @PutMapping("/api/fm/staff-shifts/{id}")
    public ResponseEntity<ApiEnvelope<ShiftView>> updateShift(@PathVariable Long id,
                                                              @Valid @RequestBody ShiftUpdateBody body) {
        return ok("Đã cập nhật ca làm việc", shifts.update(id, new UpdateInput(body.shiftDate(), body.startTime(),
                body.endTime(), body.note())));
    }

    @Operation(summary = "Xoá ca",
            description = "409 khi lịch hẹn chờ đã giao cho FS đang nằm trong ca không còn ca nào bao trọn. "
                    + "Audit `STAFF_SHIFT_DELETED`.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Đã xoá ca"),
            @ApiResponse(responseCode = "404", description = "Không tìm thấy ca"),
            @ApiResponse(responseCode = "409", description = "Lịch hẹn rơi khỏi ca")
    })
    @DeleteMapping("/api/fm/staff-shifts/{id}")
    public ResponseEntity<ApiEnvelope<Void>> deleteShift(@PathVariable Long id) {
        shifts.delete(id);
        return ok("Đã xoá ca làm việc", null);
    }

    @Operation(summary = "Ca của tôi", description = "Ca của FS đăng nhập ở mọi cơ sở; mặc định tuần hiện tại.")
    @GetMapping("/api/staff/shifts")
    public ResponseEntity<ApiEnvelope<List<ShiftView>>> myShifts(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ok("Ca làm việc của tôi", shifts.myShifts(from, to));
    }

    // ------------------------------------------------------------------ nghỉ phép

    @GetMapping("/api/staff/leaves")
    public ResponseEntity<ApiEnvelope<List<LeaveView>>> myLeaves() {
        return ok("Đơn nghỉ phép của tôi", leaves.myLeaves());
    }

    @Operation(summary = "Gửi đơn nghỉ phép",
            description = "`fromDate ≤ toDate`, `fromDate ≥` hôm nay, lý do bắt buộc (400). Trùng đơn chờ duyệt / "
                    + "đã duyệt → 409. FM các cơ sở của FS nhận `STAFF_LEAVE_REQUESTED`.")
    @PostMapping("/api/staff/leaves")
    public ResponseEntity<ApiEnvelope<LeaveView>> requestLeave(@Valid @RequestBody LeaveBody body) {
        return ok("Đã gửi đơn nghỉ phép", leaves.request(body.fromDate(), body.toDate(), body.reason()));
    }

    @Operation(summary = "Huỷ đơn nghỉ phép",
            description = "Đơn `Pending`, hoặc `Approved` chưa tới ngày nghỉ; khác → 409. Ca đã xoá không khôi phục.")
    @PostMapping("/api/staff/leaves/{id}/cancel")
    public ResponseEntity<ApiEnvelope<LeaveView>> cancelLeave(@PathVariable Long id) {
        return ok("Đã huỷ đơn nghỉ phép", leaves.cancel(id));
    }

    @Operation(summary = "Đơn nghỉ phép của FS thuộc cơ sở",
            description = "Quyền `staff_leave.review` theo cơ sở; `status` rỗng = mọi trạng thái (chờ duyệt lên đầu).")
    @GetMapping("/api/fm/staff-leaves")
    public ResponseEntity<ApiEnvelope<List<LeaveView>>> facilityLeaves(@RequestParam Long facilityId,
            @RequestParam(required = false) String status) {
        return ok("Đơn nghỉ phép của cơ sở", leaves.facilityLeaves(facilityId, status));
    }

    @Operation(summary = "Duyệt đơn nghỉ phép",
            description = "FS còn lịch hẹn chờ trong khoảng nghỉ hoặc yêu cầu hỗ trợ đang giao → 409 liệt kê việc. "
                    + "Duyệt xong xoá ca của FS trong khoảng nghỉ (audit từng ca); FS nhận `STAFF_LEAVE_REVIEWED`.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Đã duyệt"),
            @ApiResponse(responseCode = "403", description = "FS không thuộc cơ sở bạn phụ trách"),
            @ApiResponse(responseCode = "404", description = "Không tìm thấy đơn"),
            @ApiResponse(responseCode = "409", description = "Đơn không còn chờ duyệt / FS còn việc")
    })
    @PostMapping("/api/fm/staff-leaves/{id}/approve")
    public ResponseEntity<ApiEnvelope<LeaveView>> approveLeave(@PathVariable Long id,
                                                               @RequestBody(required = false) ReviewBody body) {
        return ok("Đã duyệt đơn nghỉ phép", leaves.approve(id, body == null ? null : body.note()));
    }

    @PostMapping("/api/fm/staff-leaves/{id}/reject")
    public ResponseEntity<ApiEnvelope<LeaveView>> rejectLeave(@PathVariable Long id,
                                                              @RequestBody(required = false) ReviewBody body) {
        return ok("Đã từ chối đơn nghỉ phép", leaves.reject(id, body == null ? null : body.note()));
    }

    // ------------------------------------------------------------------ hồ sơ

    @Operation(summary = "Hồ sơ nhân viên (Admin)", description = "Tài khoản FS / FM; gồm số giấy tờ tuỳ thân.")
    @GetMapping("/api/admin/accounts/{id}/profile")
    public ResponseEntity<ApiEnvelope<ProfileView>> adminProfile(@PathVariable Long id) {
        return ok("Hồ sơ nhân viên", profiles.get(id));
    }

    @Operation(summary = "Sửa hồ sơ nhân viên (Admin)",
            description = "Thay toàn bộ hồ sơ; audit `STAFF_PROFILE_UPDATED` (số giấy tờ che, giữ 4 ký tự cuối).")
    @PutMapping("/api/admin/accounts/{id}/profile")
    public ResponseEntity<ApiEnvelope<ProfileView>> adminUpdateProfile(@PathVariable Long id,
                                                                       @RequestBody ProfileBody body) {
        return ok("Đã cập nhật hồ sơ nhân viên", profiles.update(id, body.toInput()));
    }

    @GetMapping("/api/bom/staff/{id}/profile")
    public ResponseEntity<ApiEnvelope<ProfileView>> bomProfile(@PathVariable Long id) {
        return ok("Hồ sơ nhân viên", profiles.get(id));
    }

    @PutMapping("/api/bom/staff/{id}/profile")
    public ResponseEntity<ApiEnvelope<ProfileView>> bomUpdateProfile(@PathVariable Long id,
                                                                     @RequestBody ProfileBody body) {
        return ok("Đã cập nhật hồ sơ nhân viên", profiles.update(id, body.toInput()));
    }

    @Operation(summary = "Hồ sơ FS (FM)",
            description = "FS được gán vào cơ sở FM phụ trách; không có số giấy tờ. Quyền `staff_profile.read`.")
    @GetMapping("/api/fm/staff/{id}/profile")
    public ResponseEntity<ApiEnvelope<ProfileSummary>> fmProfile(@PathVariable Long id) {
        return ok("Hồ sơ nhân viên", profiles.forFacilityManager(id));
    }

    @GetMapping("/api/staff/profile")
    public ResponseEntity<ApiEnvelope<ProfileSummary>> myProfile() {
        return ok("Hồ sơ của tôi", profiles.mine());
    }

    // ------------------------------------------------------------------ body

    public record ShiftBody(@NotNull Long facilityId, @NotNull Long staffId,
                            @Schema(example = "2026-11-12") @NotNull LocalDate shiftDate,
                            @Schema(example = "07:00") @NotNull LocalTime startTime,
                            @Schema(example = "21:00") @NotNull LocalTime endTime, String note) {
    }

    public record ShiftUpdateBody(@NotNull LocalDate shiftDate, @NotNull LocalTime startTime,
                                  @NotNull LocalTime endTime, String note) {
    }

    public record LeaveBody(@NotNull LocalDate fromDate, @NotNull LocalDate toDate, String reason) {
    }

    public record ReviewBody(String note) {
    }

    public record ProfileBody(String position, LocalDate hireDate, String idNumber, String emergencyContactName,
                              String emergencyContactPhone, String note) {

        ProfileInput toInput() {
            return new ProfileInput(position, hireDate, idNumber, emergencyContactName, emergencyContactPhone, note);
        }
    }

    private static <T> ResponseEntity<ApiEnvelope<T>> ok(String message, T data) {
        return ResponseEntity.ok(ApiEnvelope.ok(message, data));
    }
}
