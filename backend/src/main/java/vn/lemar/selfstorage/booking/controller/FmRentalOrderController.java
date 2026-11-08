package vn.lemar.selfstorage.booking.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.lemar.selfstorage.ApiEnvelope;
import vn.lemar.selfstorage.booking.application.ProposalQueryService;
import vn.lemar.selfstorage.booking.application.dto.FmRentalOrderResponse;

/**
 * Màn FM xem đơn để đề xuất lại. Role-bound {@code /api/fm/**} (FM) + permission {@code rental_order.read} theo cơ sở
 * (cùng cách màn duyệt yêu cầu đặt kho của FM). Thao tác đề xuất lại nằm ở
 * {@link RentalOrderProposalController}.
 */
@RestController
@RequestMapping("/api/fm/rental-orders")
public class FmRentalOrderController {

    static final String DETAIL_MESSAGE = "Lấy chi tiết đơn thuê thành công";

    private final ProposalQueryService proposalQueryService;

    public FmRentalOrderController(ProposalQueryService proposalQueryService) {
        this.proposalQueryService = proposalQueryService;
    }

    @Operation(summary = "Chi tiết đơn thuê để đề xuất lại",
            description = "Đơn + nhu cầu ở form yêu cầu + lịch sử proposal (mới nhất trước, kèm lý do từ chối) + "
                    + "số lần từ chối / ngưỡng + khoang `Available` cùng cơ sở, cùng loại (đánh dấu khoang khách đã "
                    + "từ chối). Quyền `rental_order.read` theo cơ sở của đơn.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Lấy chi tiết thành công"),
            @ApiResponse(responseCode = "401", description = "Thiếu / sai / hết hạn token"),
            @ApiResponse(responseCode = "403", description = "Không phải role FM, hoặc đơn thuộc cơ sở khác"),
            @ApiResponse(responseCode = "404", description = "Đơn không tồn tại")
    })
    @GetMapping("/{orderId}")
    public ResponseEntity<ApiEnvelope<FmRentalOrderResponse>> detail(@PathVariable Long orderId) {
        FmRentalOrderResponse order = proposalQueryService.orderForFacilityManager(orderId);
        return ResponseEntity.ok(ApiEnvelope.ok(DETAIL_MESSAGE, order));
    }
}
