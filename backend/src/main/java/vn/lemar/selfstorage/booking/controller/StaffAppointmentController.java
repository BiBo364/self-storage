package vn.lemar.selfstorage.booking.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.lemar.selfstorage.ApiEnvelope;
import vn.lemar.selfstorage.booking.application.StaffAppointmentActionService;
import vn.lemar.selfstorage.booking.application.exception.AppointmentConflictException;
import vn.lemar.selfstorage.booking.application.StaffAppointmentQueryService;
import vn.lemar.selfstorage.booking.application.dto.StaffAppointmentResponse;

/**
 * Lịch và action của FS (Flow 2.1). Role-bound {@code /api/staff/**} (FS) + permission theo cơ sở;
 * chỉ tác động lịch có {@code staff_id} = FS đang đăng nhập. {@code Appointment} dùng chung, không
 * thuộc riêng handover.
 */
@RestController
@RequestMapping("/api/staff")
public class StaffAppointmentController {

    static final String MESSAGE = "Lấy lịch làm việc thành công";
    static final String ARRIVE_MESSAGE = "Đã xác nhận khách đến";
    static final String CANCEL_MESSAGE = "Đã huỷ lịch hẹn";

    private final StaffAppointmentQueryService queryService;
    private final StaffAppointmentActionService actionService;

    public StaffAppointmentController(StaffAppointmentQueryService queryService,
                                      StaffAppointmentActionService actionService) {
        this.queryService = queryService;
        this.actionService = actionService;
    }

    @Operation(summary = "Lịch làm việc của FS theo ngày",
            description = "Chỉ trả lịch có `staff_id` = FS đang đăng nhập, thuộc cơ sở FS được gán. "
                    + "Thiếu `date` → hôm nay (giờ VN).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Lấy lịch làm việc thành công"),
            @ApiResponse(responseCode = "401", description = "Thiếu / sai / hết hạn token"),
            @ApiResponse(responseCode = "403", description = "Không phải role FS, hoặc ngoài phạm vi cơ sở")
    })
    @GetMapping("/appointments")
    public ResponseEntity<ApiEnvelope<List<StaffAppointmentResponse>>> list(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok(ApiEnvelope.ok(MESSAGE, queryService.listForCurrentStaff(date)));
    }

    @Operation(summary = "FS xác nhận khách đã đến",
            description = "Lịch `Pending` của chính FS: `status = Done`, `arrived_at = now`, audit "
                    + "`APPOINTMENT_ARRIVED`; handover nghe event `AppointmentArrived` để set `due_at`.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Đã xác nhận khách đến"),
            @ApiResponse(responseCode = "401", description = "Thiếu / sai / hết hạn token"),
            @ApiResponse(responseCode = "403", description = "Không phải FS phụ trách lịch / ngoài cơ sở"),
            @ApiResponse(responseCode = "404", description = "Lịch hẹn không tồn tại"),
            @ApiResponse(responseCode = "409", description = "Lịch không còn `Pending`")
    })
    @PostMapping("/appointments/{appointmentId}/arrive")
    public ResponseEntity<ApiEnvelope<StaffAppointmentResponse>> arrive(@PathVariable Long appointmentId) {
        StaffAppointmentActionService.ActionResult result = actionService.arrive(appointmentId);
        if (result.conflict()) {
            throw new AppointmentConflictException("Lịch đã quá khung giờ, không thể xác nhận khách đến");
        }
        return ResponseEntity.ok(ApiEnvelope.ok(ARRIVE_MESSAGE, result.appointment()));
    }

    @Operation(summary = "FS chủ động huỷ lịch đang chờ",
            description = "Lịch `Pending` của chính FS và chưa quá `end_at`: `status = Canceled`, "
                    + "`cancel_reason = reason`, audit `APPOINTMENT_CANCELED` (actor FS). Không đụng "
                    + "`RentalOrder`; quá `end_at` → huỷ no-show ngay rồi trả 409.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Đã huỷ lịch hẹn"),
            @ApiResponse(responseCode = "400", description = "Thiếu lý do huỷ"),
            @ApiResponse(responseCode = "401", description = "Thiếu / sai / hết hạn token"),
            @ApiResponse(responseCode = "403", description = "Không phải FS phụ trách lịch / ngoài cơ sở"),
            @ApiResponse(responseCode = "404", description = "Lịch hẹn không tồn tại"),
            @ApiResponse(responseCode = "409", description = "Lịch không còn `Pending` hoặc đã quá giờ")
    })
    @PostMapping("/appointments/{appointmentId}/cancel")
    public ResponseEntity<ApiEnvelope<StaffAppointmentResponse>> cancel(
            @PathVariable Long appointmentId, @Valid @RequestBody CancelAppointmentRequest request) {
        StaffAppointmentActionService.ActionResult result = actionService.cancel(appointmentId, request.reason());
        if (result.conflict()) {
            throw new AppointmentConflictException("Lịch đã quá giờ");
        }
        return ResponseEntity.ok(ApiEnvelope.ok(CANCEL_MESSAGE, result.appointment()));
    }

    @Schema(description = "Lý do FS huỷ lịch")
    public record CancelAppointmentRequest(
            @Schema(description = "Lý do huỷ", example = "Khách yêu cầu huỷ")
            @NotBlank(message = "Vui lòng nhập lý do huỷ")
            @Size(max = 1000)
            String reason) {
    }
}
