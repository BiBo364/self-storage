package vn.lemar.selfstorage.booking.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/** Một dòng trong màn theo dõi đơn thuê của FM. */
public record FmRentalOrderSummaryResponse(
        @Schema(description = "Id đơn thuê") Long id,
        @Schema(description = "Trạng thái đơn", example = "Pending") String status,
        @Schema(description = "Lý do huỷ nếu đơn Canceled") String cancelReason,
        @Schema(description = "Hạn hoàn thành đơn") Instant expiresAt,
        @Schema(description = "Khoang đang gán cho đơn") Long unitId,
        @Schema(description = "Mã khoang") String unitCode,
        @Schema(description = "Id yêu cầu gốc") Long requestId,
        @Schema(description = "Tên khách") String customerName,
        @Schema(description = "Email khách") String customerEmail,
        @Schema(description = "Số điện thoại khách") String customerPhone,
        @Schema(description = "Flag raw trên order") boolean needsReproposal,
        @Schema(description = "Đơn cần FM đề xuất lại (BE tính)") boolean needsRepropose,
        @Schema(description = "Số proposal bị từ chối") long rejectionCount,
        @Schema(description = "Ngưỡng từ chối") int maxRejectionCount,
        @Schema(description = "Proposal gần nhất; null nếu chưa có") LastProposal lastProposal
) {

    @Schema(name = "FmRentalOrderLastProposal")
    public record LastProposal(
            @Schema(description = "Id proposal") Long id,
            @Schema(description = "Mã khoang") String unitCode,
            @Schema(description = "Pending / Agreed / Rejected / Canceled") String status,
            @Schema(description = "Lý do khách từ chối") String note,
            @Schema(description = "Thời điểm đề xuất") Instant createdAt) {
    }
}
