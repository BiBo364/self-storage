package vn.lemar.selfstorage.booking.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Chi tiết đơn thuê cho màn "Đề xuất lại khoang" của FM. */
@Schema(description = "Đơn thuê + lịch sử proposal + khoang trống cùng loại cùng cơ sở")
public record FmRentalOrderResponse(
        @Schema(description = "Id đơn — cũng là mã đơn hiển thị", example = "1") Long id,
        @Schema(description = "Pending / Deposited / Scheduled / InProgress / Canceled / Done", example = "Pending")
        String status,
        @Schema(description = "Lý do huỷ (khi Canceled)") String cancelReason,
        @Schema(description = "Hạn hoàn thành đơn") Instant expiresAt,
        @Schema(description = "Khoang đang gán cho đơn (proposal Agreed mới nhất); null nếu chưa") Long unitId,
        @Schema(description = "Id cơ sở", example = "1") Long facilityId,
        @Schema(description = "Id yêu cầu gốc") Long requestId,
        @Schema(description = "Trạng thái yêu cầu gốc", example = "Converted") String requestStatus,
        @Schema(description = "Đơn đang cần FM đề xuất lại") boolean needsReproposal,
        @Schema(description = "Khách hàng") Customer customer,
        @Schema(description = "Nhu cầu khách gửi ở form yêu cầu") Requested requested,
        @Schema(description = "Số proposal Rejected của đơn", example = "1") long rejectionCount,
        @Schema(description = "Ngưỡng `proposal.max_rejection_count`", example = "3") int maxRejectionCount,
        @Schema(description = "Lịch sử proposal, mới nhất trước") List<Proposal> proposals,
        @Schema(description = "Khoang Available cùng cơ sở, cùng loại khách cần") List<AvailableUnit> availableUnits) {

    @Schema(name = "FmRentalOrderCustomer")
    public record Customer(
            @Schema(description = "Id account khách", example = "6") Long accountId,
            @Schema(description = "Tên khách ở form yêu cầu") String name,
            @Schema(description = "Email đã chuẩn hoá") String email,
            @Schema(description = "Số điện thoại") String phone) {
    }

    @Schema(name = "FmRentalOrderRequested")
    public record Requested(
            @Schema(description = "Id loại khoang", example = "1") Long unitTypeId,
            @Schema(description = "Tên loại khoang", example = "Small") String unitTypeName,
            @Schema(description = "Ngày bắt đầu thuê mong muốn") LocalDate startDate,
            @Schema(description = "Số tháng thuê", example = "3") int period) {
    }

    @Schema(name = "FmRentalOrderProposal")
    public record Proposal(
            @Schema(description = "Id proposal") Long id,
            @Schema(description = "Id khoang") Long unitId,
            @Schema(description = "Mã khoang", example = "A-01") String unitCode,
            @Schema(description = "Pending / Agreed / Rejected / Canceled") String status,
            @Schema(description = "Lý do khách từ chối (nếu có)") String note,
            @Schema(description = "Thời điểm đề xuất") Instant createdAt) {
    }

    @Schema(name = "FmRentalOrderAvailableUnit")
    public record AvailableUnit(
            @Schema(description = "Id khoang") Long id,
            @Schema(description = "Mã khoang", example = "A-02") String code,
            @Schema(description = "Kích thước") String size,
            @Schema(description = "Giá thuê tháng hiệu lực", example = "500000.00") BigDecimal monthlyPrice,
            @Schema(description = "Khách đã từ chối khoang này trong đơn — chọn lại cần `overrideReason`")
            boolean previouslyRejected) {
    }
}
