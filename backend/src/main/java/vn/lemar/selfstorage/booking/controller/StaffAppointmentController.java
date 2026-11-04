package vn.lemar.selfstorage.booking.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.lemar.selfstorage.ApiEnvelope;
import vn.lemar.selfstorage.booking.application.StaffAppointmentQueryService;
import vn.lemar.selfstorage.booking.application.dto.StaffAppointmentResponse;

/**
 * Lịch làm việc của FS (Flow 2.1, read-only). Role-bound {@code /api/staff/**} (FS) + permission
 * {@code appointment.read} theo cơ sở; chỉ trả lịch có {@code staff_id} = FS đang đăng nhập.
 */
@RestController
@RequestMapping("/api/staff")
public class StaffAppointmentController {

    static final String MESSAGE = "Lấy lịch làm việc thành công";

    private final StaffAppointmentQueryService queryService;

    public StaffAppointmentController(StaffAppointmentQueryService queryService) {
        this.queryService = queryService;
    }

    @Operation(summary = "Lịch làm việc của FS theo ngày",
            description = "Chỉ trả lịch có `staff_id` = FS đang đăng nhập, thuộc cơ sở FS được gán. "
                    + "Thiếu `date` → hôm nay (giờ VN). Chưa có endpoint arrive (C1).")
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
}
