package vn.lemar.selfstorage.booking.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.lemar.selfstorage.ApiEnvelope;
import vn.lemar.selfstorage.booking.application.FmFacilityReadService;
import vn.lemar.selfstorage.booking.application.OrderOverviewQueryService;
import vn.lemar.selfstorage.booking.application.OrderOverviewQueryService.OrderOverview;
import vn.lemar.selfstorage.booking.application.RentalOrderCancelService;
import vn.lemar.selfstorage.booking.application.RentalOrderCancelService.CancelResult;
import vn.lemar.selfstorage.identity.application.CurrentAccountProvider;

/**
 * Màn FM theo dõi đơn thuê của cơ sở (fe-pages fm/12) và huỷ đơn (spec 1.3 "Khách không phản hồi" → Hủy đơn).
 * Role-bound {@code /api/fm/**} + permission theo cơ sở ({@code rental_order.read} / {@code rental_order.cancel}).
 */
@RestController
@RequestMapping("/api/fm/rental-orders")
public class FmRentalOrderActionController {

    static final String LIST_MESSAGE = "Lấy danh sách đơn thuê thành công";
    static final String CANCEL_MESSAGE = "Đã huỷ đơn thuê";

    private final OrderOverviewQueryService overviewQuery;
    private final RentalOrderCancelService cancelService;
    private final FmFacilityReadService facilityReadService;
    private final CurrentAccountProvider currentAccount;

    public FmRentalOrderActionController(OrderOverviewQueryService overviewQuery,
                                         RentalOrderCancelService cancelService,
                                         FmFacilityReadService facilityReadService,
                                         CurrentAccountProvider currentAccount) {
        this.overviewQuery = overviewQuery;
        this.cancelService = cancelService;
        this.facilityReadService = facilityReadService;
        this.currentAccount = currentAccount;
    }

    @Operation(summary = "Đơn thuê của cơ sở",
            description = "Đơn của cơ sở FM đang phụ trách, mới nhất trước; lọc `status`. Mỗi đơn kèm "
                    + "`availableActions` cho FM (REPROPOSE, ASSIGN_STAFF, CANCEL).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Lấy danh sách thành công"),
            @ApiResponse(responseCode = "400", description = "`status` không hợp lệ"),
            @ApiResponse(responseCode = "403", description = "Không phải FM / không có quyền"),
            @ApiResponse(responseCode = "404", description = "FM chưa được gán cơ sở")
    })
    @GetMapping
    public ResponseEntity<ApiEnvelope<List<OrderOverview>>> list(
            @Parameter(description = "Pending / Deposited / Scheduled / InProgress / Canceled / Done")
            @RequestParam(required = false) String status) {
        Long facilityId = facilityReadService.currentFacility().id();
        return ResponseEntity.ok(ApiEnvelope.ok(LIST_MESSAGE, overviewQuery.listForFacility(facilityId, status)));
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
