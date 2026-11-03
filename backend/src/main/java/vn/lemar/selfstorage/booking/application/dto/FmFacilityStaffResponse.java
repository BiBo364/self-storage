package vn.lemar.selfstorage.booking.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/** Một FS đang hoạt động của cơ sở cho dropdown phân công lịch check-in. */
public record FmFacilityStaffResponse(
        @Schema(description = "Id tài khoản FS", example = "5") Long id,
        @Schema(description = "Tên FS", example = "FS Quận 7") String name,
        @Schema(description = "Email FS", example = "fs1@lemar.vn") String email,
        @Schema(description = "Số điện thoại", example = "0901234567") String phone,
        @Schema(description = "Thời điểm gán vào cơ sở") Instant assignedAt) {
}
