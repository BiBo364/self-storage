package vn.lemar.selfstorage.booking.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.lemar.selfstorage.ApiEnvelope;
import vn.lemar.selfstorage.booking.application.AppointmentQueryService;
import vn.lemar.selfstorage.booking.application.AppointmentService;
import vn.lemar.selfstorage.booking.application.dto.FmAppointmentResponse;
import vn.lemar.selfstorage.booking.application.dto.FmFacilityStaffResponse;
import vn.lemar.selfstorage.identity.application.AccountQueryService;

/**
 * Màn lịch hẹn & phân công FS của FM (spec 1.5). Role-bound {@code /api/fm/**} (FM) + permission theo cơ sở
 * ({@code appointment.read}, {@code appointment.assign}) kiểm trong service.
 */
@RestController
@RequestMapping("/api/fm")
public class FmAppointmentController {

    static final String LIST_MESSAGE = "Lấy danh sách lịch hẹn thành công";
    static final String STAFF_MESSAGE = "Lấy danh sách nhân viên cơ sở thành công";
    static final String ASSIGN_MESSAGE = "Đã phân công nhân viên phụ trách lịch hẹn";
    static final String REASSIGN_MESSAGE = "Đã đổi nhân viên phụ trách lịch hẹn";

    private final AppointmentQueryService queryService;
    private final AppointmentService appointmentService;
    private final AccountQueryService accountQueryService;

    public FmAppointmentController(AppointmentQueryService queryService, AppointmentService appointmentService,
                                   AccountQueryService accountQueryService) {
        this.queryService = queryService;
        this.appointmentService = appointmentService;
        this.accountQueryService = accountQueryService;
    }

    @Operation(summary = "Lịch check-in của cơ sở theo ngày",
            description = "Lịch check-in (`type = CHECKIN`, `status = Pending`) trong ngày, xếp theo giờ bắt đầu, "
                    + "kèm đơn thuê, khách, khoang và FS phụ trách. Quyền `appointment.read` theo cơ sở.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Lấy danh sách thành công"),
            @ApiResponse(responseCode = "400", description = "Thiếu `facilityId` hoặc `date` (ISO `yyyy-MM-dd`)"),
            @ApiResponse(responseCode = "401", description = "Thiếu / sai / hết hạn token"),
            @ApiResponse(responseCode = "403", description = "Không phải role FM, hoặc ngoài phạm vi cơ sở"),
            @ApiResponse(responseCode = "404", description = "Cơ sở không tồn tại")
    })
    @GetMapping("/appointments")
    public ResponseEntity<ApiEnvelope<List<FmAppointmentResponse>>> list(
            @RequestParam Long facilityId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok(ApiEnvelope.ok(LIST_MESSAGE, queryService.listForFacility(facilityId, date)));
    }

    @Operation(summary = "Nhân viên FS của cơ sở",
            description = "FS đang hoạt động được gán vào cơ sở, để FM chọn khi phân công. Mỗi FS kèm ca tại cơ sở "
                    + "(`shift` = ca đầu tiên, `shifts` = mọi ca), `onLeave`, `openWork` (lịch check-in / trả kho "
                    + "`Pending` trong ngày) và `openSupport` của ngày `date` (mặc định hôm nay, giờ VN). "
                    + "Quyền `appointment.assign` theo cơ sở.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Lấy danh sách thành công"),
            @ApiResponse(responseCode = "401", description = "Thiếu / sai / hết hạn token"),
            @ApiResponse(responseCode = "403", description = "Không phải role FM, hoặc ngoài phạm vi cơ sở"),
            @ApiResponse(responseCode = "404", description = "Cơ sở không tồn tại")
    })
    @GetMapping("/facilities/{facilityId}/staff")
    public ResponseEntity<ApiEnvelope<List<FmFacilityStaffResponse>>> staff(@PathVariable Long facilityId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok(ApiEnvelope.ok(STAFF_MESSAGE, queryService.staffOfFacility(facilityId, date)));
    }

    @Operation(summary = "Phân công FS cho lịch check-in",
            description = "Gán `Appointment.staff_id`, đơn `Scheduled → InProgress`, AuditLog `FS_ASSIGNED`, FS nhận "
                    + "notification `FS_ASSIGNED`. Chỉ lịch `CHECKIN` đang `Pending` và chưa có FS (lịch đã có FS → "
                    + "409; đổi FS dùng `PUT`); FS phải thuộc cơ sở của lịch. Quyền `appointment.assign` theo cơ sở.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Đã phân công"),
            @ApiResponse(responseCode = "400", description = "Thiếu `staffId`, hoặc nhân viên không phải FS của cơ sở"),
            @ApiResponse(responseCode = "401", description = "Thiếu / sai / hết hạn token"),
            @ApiResponse(responseCode = "403", description = "Không phải role FM, hoặc ngoài phạm vi cơ sở"),
            @ApiResponse(responseCode = "404", description = "Lịch hẹn không tồn tại"),
            @ApiResponse(responseCode = "409", description = "Lịch không phải check-in, không còn Pending, đã có FS, "
                    + "đơn không ở trạng thái chờ phân công, FS nghỉ phép ngày hẹn, hoặc (policy "
                    + "`staff.shift_required` bật) FS không có ca tại cơ sở bao trọn giờ hẹn")
    })
    @PostMapping("/appointments/{appointmentId}/staff")
    public ResponseEntity<ApiEnvelope<FmAppointmentResponse>> assignStaff(
            Authentication authentication, @PathVariable Long appointmentId,
            @Valid @RequestBody AssignStaffRequest request) {
        FmAppointmentResponse appointment = appointmentService.assignStaff(appointmentId, request.staffId(),
                accountQueryService.requireIdByEmail(authentication.getName()));
        return ResponseEntity.ok(ApiEnvelope.ok(ASSIGN_MESSAGE, appointment));
    }

    @Operation(summary = "Đổi FS của lịch check-in",
            description = "Lịch `CHECKIN` `Pending` đã có FS (khách chưa đến), đơn `InProgress`: đổi "
                    + "`Appointment.staff_id` sang FS khác của cùng cơ sở. Đơn giữ nguyên trạng thái; một AuditLog "
                    + "`FS_ASSIGNED` cho `Appointment` (`old_value.staffId` = FS cũ); FS mới nhận notification "
                    + "`FS_ASSIGNED`. Quyền `appointment.assign` theo cơ sở.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Đã đổi FS"),
            @ApiResponse(responseCode = "400", description = "Thiếu `staffId`, hoặc nhân viên không phải FS của cơ sở"),
            @ApiResponse(responseCode = "401", description = "Thiếu / sai / hết hạn token"),
            @ApiResponse(responseCode = "403", description = "Không phải role FM, hoặc ngoài phạm vi cơ sở"),
            @ApiResponse(responseCode = "404", description = "Lịch hẹn không tồn tại"),
            @ApiResponse(responseCode = "409", description = "Lịch không phải check-in, không còn Pending, chưa có FS, "
                    + "đã là FS này, đơn không ở InProgress, FS nghỉ phép ngày hẹn, hoặc (policy "
                    + "`staff.shift_required` bật) FS không có ca tại cơ sở bao trọn giờ hẹn")
    })
    @PutMapping("/appointments/{appointmentId}/staff")
    public ResponseEntity<ApiEnvelope<FmAppointmentResponse>> reassignStaff(
            Authentication authentication, @PathVariable Long appointmentId,
            @Valid @RequestBody AssignStaffRequest request) {
        FmAppointmentResponse appointment = appointmentService.reassignStaff(appointmentId, request.staffId(),
                accountQueryService.requireIdByEmail(authentication.getName()));
        return ResponseEntity.ok(ApiEnvelope.ok(REASSIGN_MESSAGE, appointment));
    }

    @Schema(description = "FS được phân công")
    public record AssignStaffRequest(
            @Schema(description = "Id tài khoản FS", example = "4") @NotNull(message = "Vui lòng chọn nhân viên")
            Long staffId) {
    }
}
