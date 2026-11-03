package vn.lemar.selfstorage.booking.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** Một FS đang hoạt động của cơ sở cho dropdown phân công lịch check-in. */
public record FmFacilityStaffResponse(
        @Schema(description = "Id tài khoản FS", example = "5") Long id,
        @Schema(description = "Email FS", example = "fs1@lemar.vn") String email) {
}
