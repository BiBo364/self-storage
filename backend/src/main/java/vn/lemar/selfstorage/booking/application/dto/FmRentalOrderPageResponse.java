package vn.lemar.selfstorage.booking.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** Một trang đơn thuê của cơ sở cho FM. */
public record FmRentalOrderPageResponse(
        @Schema(description = "Các đơn trong trang") List<FmRentalOrderSummaryResponse> items,
        @Schema(description = "Trang hiện tại (0-based)") int page,
        @Schema(description = "Kích thước trang") int size,
        @Schema(description = "Tổng số bản ghi") long total
) {
}
