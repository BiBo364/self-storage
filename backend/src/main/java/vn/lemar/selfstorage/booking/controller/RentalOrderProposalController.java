package vn.lemar.selfstorage.booking.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.lemar.selfstorage.ApiEnvelope;
import vn.lemar.selfstorage.booking.application.ProposalService;
import vn.lemar.selfstorage.booking.application.ProposalView;
import vn.lemar.selfstorage.identity.application.AccountQueryService;

/**
 * FM đề xuất lại khoang cho đơn: {@code POST /api/rental-orders/{orderId}/proposals}. Route
 * permission-bound trong {@code /api/**} ({@code ACCOUNT_ACTIVE}); quyền {@code proposal_feedback.repropose}
 * (+ {@code .override}) theo cơ sở kiểm trong service qua {@code Access.can}.
 */
@RestController
@RequestMapping("/api/rental-orders")
public class RentalOrderProposalController {

    static final String REPROPOSE_MESSAGE = "Đã gửi đề xuất khoang mới cho khách";

    private final ProposalService proposalService;
    private final AccountQueryService accountQueryService;

    public RentalOrderProposalController(ProposalService proposalService, AccountQueryService accountQueryService) {
        this.proposalService = proposalService;
        this.accountQueryService = accountQueryService;
    }

    @Operation(summary = "Đề xuất lại khoang",
            description = "Tạo `ProposalFeedback` `Pending` mới cho đơn, khách nhận `PROPOSAL_REPROPOSED`. "
                    + "Được đề xuất khi đơn chưa đóng, chưa quá `expires_at`, không có proposal `Pending`, và đơn "
                    + "đang `Pending` hoặc `needs_reproposal`. "
                    + "Khoang phải cùng cơ sở và còn `Available`. Khoang khách đã từ chối: bắt buộc `overrideReason` + "
                    + "quyền `proposal_feedback.override`, lý do ghi AuditLog. Hoá đơn cọc `Unpaid` cũ của đơn bị huỷ.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Đã tạo proposal mới"),
            @ApiResponse(responseCode = "400", description = "Thiếu `unitId`, hoặc thiếu `overrideReason` khi chọn "
                    + "lại khoang khách đã từ chối"),
            @ApiResponse(responseCode = "401", description = "Thiếu / sai / hết hạn token"),
            @ApiResponse(responseCode = "403", description = "Thiếu quyền đề xuất lại / override, hoặc đơn thuộc cơ "
                    + "sở khác"),
            @ApiResponse(responseCode = "404",
                    description = "Đơn không tồn tại, hoặc khoang không thuộc cơ sở của đơn"),
            @ApiResponse(responseCode = "409", description = "Khoang không còn trống; đơn đã đóng hoặc quá "
                    + "`expires_at`; đơn đang có proposal chờ khách phản hồi; hoặc đơn không ở trạng thái cho phép "
                    + "đề xuất lại")
    })
    @PostMapping("/{orderId}/proposals")
    public ResponseEntity<ApiEnvelope<ProposalView>> repropose(Authentication authentication,
                                                               @PathVariable Long orderId,
                                                               @Valid @RequestBody ReproposeRequest request) {
        ProposalView proposal = proposalService.repropose(orderId, request.unitId(), request.overrideReason(),
                accountQueryService.requireIdByEmail(authentication.getName()));
        return ResponseEntity.ok(ApiEnvelope.ok(REPROPOSE_MESSAGE, proposal));
    }

    @Schema(description = "Khoang FM đề xuất lại")
    public record ReproposeRequest(
            @Schema(description = "Id khoang mới", example = "2") @NotNull(message = "Vui lòng chọn khoang")
            Long unitId,
            @Schema(description = "Bắt buộc khi khoang đã bị khách từ chối trong đơn") String overrideReason) {
    }
}
