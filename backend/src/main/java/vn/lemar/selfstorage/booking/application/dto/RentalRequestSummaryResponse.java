package vn.lemar.selfstorage.booking.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import vn.lemar.selfstorage.booking.domain.RentalRequest;
import vn.lemar.selfstorage.booking.domain.RentalRequestNextAction;
import vn.lemar.selfstorage.booking.domain.RentalRequestStatus;
import vn.lemar.selfstorage.booking.repository.BookingReadRepository;

/**
 * Một dòng trong hàng đợi FM — không có field facility (spec draft.md NOTES §1.1:
 * FM chỉ thấy cơ sở của mình).
 *
 * <p>Ngoài dữ liệu request, response mang thêm trạng thái đơn/proposal để FM biết bước tiếp theo.
 * {@code nextAction} do BE tính; FE không tự suy luận.
 */
public record RentalRequestSummaryResponse(
        @Schema(description = "Id yêu cầu") Long id,
        @Schema(description = "Tên khách hàng") String customerName,
        @Schema(description = "Email đã normalize") String customerEmail,
        @Schema(description = "Số điện thoại") String customerPhone,
        @Schema(description = "Loại khoang khách chọn") Long unitTypeId,
        @Schema(description = "Ngày bắt đầu thuê") LocalDate startDate,
        @Schema(description = "Số tháng thuê") Integer period,
        @Schema(description = "Trạng thái", example = "Pending") String status,
        @Schema(description = "Thời điểm tạo") Instant createdAt,
        @Schema(description = "Hạn xử lý") Instant expiresAt,
        @Schema(description = "Id đơn thuê nếu đã convert; null nếu chưa") Long orderId,
        @Schema(description = "Trạng thái đơn thuê; null nếu chưa convert", example = "Pending")
        String orderStatus,
        @Schema(description = "APPROVE_REQUEST / WAIT_CUSTOMER / REPROPOSE_UNIT / VIEW")
        String nextAction,
        @Schema(description = "Proposal gần nhất của đơn; null nếu chưa có") LastProposal lastProposal,
        @Schema(description = "Số proposal bị từ chối của đơn", example = "1") long rejectionCount,
        @Schema(description = "Ngưỡng `proposal.max_rejection_count`", example = "3") int maxRejectionCount
) {

    public static RentalRequestSummaryResponse from(RentalRequest request,
            BookingReadRepository.QueueEnrichment enrichment, int maxRejection) {
        LastProposal lastProposal = enrichment == null || enrichment.lastProposalId() == null
                ? null
                : new LastProposal(enrichment.lastProposalId(), enrichment.lastProposalUnitCode(),
                        enrichment.lastProposalStatus(), enrichment.lastProposalNote(),
                        enrichment.lastProposalCreatedAt());
        return new RentalRequestSummaryResponse(request.getId(), request.getCustomerName(),
                request.getNormalizedCustomerEmail(), request.getCustomerPhone(),
                request.getUnitTypeId(), request.getStartDate(), request.getPeriod(),
                request.getStatus().name(), request.getCreatedAt(), request.getExpiresAt(),
                enrichment == null ? null : enrichment.orderId(),
                enrichment == null ? null : enrichment.orderStatus(), nextAction(request, enrichment).name(),
                lastProposal, enrichment == null ? 0 : enrichment.rejectionCount(), maxRejection);
    }

    private static RentalRequestNextAction nextAction(RentalRequest request,
            BookingReadRepository.QueueEnrichment enrichment) {
        if (enrichment == null || enrichment.orderId() == null) {
            return request.getStatus() == RentalRequestStatus.Pending
                    ? RentalRequestNextAction.APPROVE_REQUEST : RentalRequestNextAction.VIEW;
        }
        if ("Canceled".equals(enrichment.orderStatus()) || "Done".equals(enrichment.orderStatus())) {
            return RentalRequestNextAction.VIEW;
        }
        if (enrichment.pendingProposalCount() > 0) {
            return RentalRequestNextAction.WAIT_CUSTOMER;
        }
        if ("Agreed".equals(enrichment.lastProposalStatus())) {
            return RentalRequestNextAction.VIEW;
        }
        boolean canRepropose = ("Pending".equals(enrichment.orderStatus()) || enrichment.needsReproposal())
                && (enrichment.lastProposalStatus() == null
                        || "Rejected".equals(enrichment.lastProposalStatus())
                        || "Canceled".equals(enrichment.lastProposalStatus()));
        return canRepropose ? RentalRequestNextAction.REPROPOSE_UNIT : RentalRequestNextAction.VIEW;
    }

    @Schema(name = "RentalRequestLastProposal")
    public record LastProposal(
            @Schema(description = "Id proposal") Long id,
            @Schema(description = "Mã khoang", example = "A-01") String unitCode,
            @Schema(description = "Pending / Agreed / Rejected / Canceled") String status,
            @Schema(description = "Lý do khách từ chối (nếu có)") String note,
            @Schema(description = "Thời điểm đề xuất") Instant createdAt) {
    }
}
