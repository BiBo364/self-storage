package vn.lemar.selfstorage.booking.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import vn.lemar.selfstorage.staffing.application.StaffAvailability.DayWork;
import vn.lemar.selfstorage.staffing.application.StaffAvailability.ShiftTime;

/** Một FS đang hoạt động của cơ sở cho dropdown phân công, kèm ca / nghỉ phép / việc còn mở của ngày {@code date}. */
public record FmFacilityStaffResponse(
        @Schema(description = "Id tài khoản FS", example = "5") Long id,
        @Schema(description = "Tên FS", example = "FS Quận 7") String name,
        @Schema(description = "Email FS", example = "fs1@lemar.vn") String email,
        @Schema(description = "Số điện thoại", example = "0901234567") String phone,
        @Schema(description = "Thời điểm gán vào cơ sở") Instant assignedAt,
        @Schema(description = "Ngày đang xem (mặc định hôm nay, giờ VN)", example = "2026-11-12") LocalDate date,
        @Schema(description = "Ca đầu tiên của FS tại cơ sở trong ngày; null = không có ca") ShiftTime shift,
        @Schema(description = "Mọi ca của FS tại cơ sở trong ngày, theo giờ bắt đầu") List<ShiftTime> shifts,
        @Schema(description = "FS nghỉ phép đã duyệt trong ngày") boolean onLeave,
        @Schema(description = "Lịch check-in / trả kho `Pending` đã giao cho FS tại cơ sở trong ngày") DayWork openWork,
        @Schema(description = "Yêu cầu hỗ trợ `Assigned` giao cho FS tại cơ sở (không gắn ngày)") int openSupport) {
}
