package vn.lemar.selfstorage.booking.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.lemar.selfstorage.ApiEnvelope;
import vn.lemar.selfstorage.booking.application.RentalOrderCancelService;
import vn.lemar.selfstorage.booking.application.RentalOrderCancelService.CancelResult;
import vn.lemar.selfstorage.identity.application.CurrentAccountProvider;

/**
 * FM huỷ đơn thuê (spec 1.3 "Khách không phản hồi" → Hủy đơn). Danh sách đơn nằm ở {@link FmRentalOrderController}.
 * Role-bound {@code /api/fm/**} + permission {@code rental_order.cancel} theo cơ sở.
 */
@RestController
@RequestMapping("/api/fm/rental-orders")
public class FmRentalOrderActionController {

    static final String CANCEL_MESSAGE = "Đã huỷ đơn thuê";

    private final RentalOrderCancelService cancelService;
    private final CurrentAccountProvider currentAccount;

    public FmRentalOrderActionController(RentalOrderCancelService cancelService,
                                         CurrentAccountProvider currentAccount) {
        this.cancelService = cancelService;
        this.currentAccount = currentAccount;
    }

    @Operation(summary = "FM huỷ đơn thuê",
            description = "Chạy Cancellation Cascade (proposal / hoá đơn Unpaid / lịch Pending / biên bản đang mở / "
                    + "hợp đồng chưa kích hoạt, nhả khoang nếu đơn đang giữ). `reason` bỏ trống = \"Khách không còn "
                    + "nhu cầu\".")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Đã huỷ"),
            @ApiResponse(responseCode = "400", description = "Lý do quá 500 ký tự"),
            @ApiResponse(responseCode = "403", description = "Đơn thuộc cơ sở khác"),
            @ApiResponse(responseCode = "404", description = "Đơn không tồn tại"),
            @ApiResponse(responseCode = "409", description = "Đơn đã đóng")
    })
    @PostMapping("/{orderId}/cancel")
    public ResponseEntity<ApiEnvelope<CancelResult>> cancel(@PathVariable Long orderId,
                                                            @RequestBody(required = false) CancelRequest request) {
        CancelResult result = cancelService.cancelByManager(orderId, request == null ? null : request.reason(),
                currentAccount.getCurrentAccountId());
        return ResponseEntity.ok(ApiEnvelope.ok(CANCEL_MESSAGE, result));
    }

    @Schema(description = "Lý do huỷ đơn")
    public record CancelRequest(@Schema(example = "Khách không còn nhu cầu") String reason) {
    }
}
