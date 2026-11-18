package vn.lemar.selfstorage.handover.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.lemar.selfstorage.ApiEnvelope;
import vn.lemar.selfstorage.booking.application.AppointmentBookingService.SlotWindow;
import vn.lemar.selfstorage.booking.application.CheckinRescheduleService.Rescheduled;
import vn.lemar.selfstorage.handover.application.HandoverRescheduleService;

/** FS dời lịch nhận kho (check-in) mình phụ trách — trước khi khách đến hoặc khi phiên bàn giao chưa thanh toán. */
@RestController
@RequestMapping("/api/staff/appointments")
public class StaffRescheduleController {

    private final HandoverRescheduleService rescheduleService;

    public StaffRescheduleController(HandoverRescheduleService rescheduleService) {
        this.rescheduleService = rescheduleService;
    }

    @Operation(summary = "Khung giờ để dời lịch nhận kho",
            description = "FS phụ trách lịch (`appointment.checkin`). Từ hôm nay tới ngày hết hạn giữ đơn, 3 khung / "
                    + "ngày; khung đã qua, kết thúc sau hạn giữ hoặc cơ sở đã có lịch → `available=false`.")
    @GetMapping("/{appointmentId}/reschedule-slots")
    public ResponseEntity<ApiEnvelope<SlotWindow>> slots(@PathVariable Long appointmentId) {
        return ResponseEntity.ok(ApiEnvelope.ok("Khung giờ dời lịch", rescheduleService.slots(appointmentId)));
    }

    @Operation(summary = "Dời lịch nhận kho",
            description = "Trước khi khách đến (lịch `Pending`, chưa quá giờ) hoặc khách đã đến nhưng chưa trả tiền "
                    + "tháng đầu. Lịch cũ chưa diễn ra → `Canceled`; tạo lịch mới gán sẵn FS; biên bản cũ → "
                    + "`CANCELED` (giữ làm lịch sử), hợp đồng nháp / đã ký chưa trả → `Canceled`, hoá đơn tháng đầu "
                    + "chưa trả → `Canceled`; phiên mới làm lại xác minh + kiểm tra khoang. Đã trả tháng đầu / đang "
                    + "có phiên thanh toán / khung đã có lịch / ngoài hạn giữ / FS nghỉ phép hoặc không có ca → 409.")
    @PostMapping("/{appointmentId}/reschedule")
    public ResponseEntity<ApiEnvelope<Rescheduled>> reschedule(@PathVariable Long appointmentId,
                                                               @Valid @RequestBody RescheduleRequest body) {
        return ResponseEntity.ok(ApiEnvelope.ok("Đã dời lịch nhận kho",
                rescheduleService.reschedule(appointmentId, body.date(), body.startAt(), body.reason())));
    }

    @Schema(description = "Khung mới và lý do dời lịch")
    public record RescheduleRequest(
            @Schema(description = "Ngày hẹn mới (giờ VN)", example = "2026-11-20") @NotNull LocalDate date,
            @Schema(description = "Giờ bắt đầu khung — đúng một trong 3 khung của ngày")
            @NotNull Instant startAt,
            @Schema(description = "Lý do dời", example = "Khách quên CCCD, hẹn quay lại")
            @NotBlank @Size(max = 500) String reason) {
    }
}
