package vn.lemar.selfstorage.booking.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.lemar.selfstorage.ApiEnvelope;
import vn.lemar.selfstorage.ApiError;
import vn.lemar.selfstorage.booking.application.FmInvoicePageView;
import vn.lemar.selfstorage.booking.application.InvoiceListQueryService;

/**
 * Hoá đơn của cơ sở FM. Quyền {@code invoice.read} scope FACILITY; dữ liệu trả kèm trạng thái
 * giao dịch PAY gần nhất để FE highlight hoá đơn Pending quá 15 phút. Read-only, không đổi state.
 */
@RestController
@RequestMapping("/api/fm/invoices")
public class FmInvoiceController {

    static final String LIST_MESSAGE = "Lấy danh sách hoá đơn cơ sở thành công";

    private final InvoiceListQueryService invoiceListQueryService;

    public FmInvoiceController(InvoiceListQueryService invoiceListQueryService) {
        this.invoiceListQueryService = invoiceListQueryService;
    }

    @Operation(summary = "Hoá đơn của cơ sở",
            description = "Một trang hoá đơn thuộc cơ sở FM đang phụ trách, mới nhất trước. "
                    + "`type`/`status`/`paymentStatus` lọc theo loại, trạng thái invoice và giao dịch PAY "
                    + "gần nhất; `olderThanMinutes` chỉ giữ Pending đã quá số phút. "
                    + "`needsReconciliation=true` khi Pending quá 15 phút. Read-only.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Lấy danh sách thành công"),
            @ApiResponse(responseCode = "400", description = "Filter `type`, `status`, "
                    + "`paymentStatus`, `olderThanMinutes`, `page` hoặc `size` không hợp lệ",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401", description = "Thiếu / sai / hết hạn token"),
            @ApiResponse(responseCode = "403", description = "Không phải role FM, hoặc ngoài phạm vi cơ sở"),
            @ApiResponse(responseCode = "404", description = "Cơ sở không tồn tại",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiError.class)))
    })
    @GetMapping
    public ResponseEntity<ApiEnvelope<FmInvoicePageView>> list(
            @Parameter(description = "Id cơ sở") @RequestParam Long facilityId,
            @Parameter(description = "Unpaid / Paid / Canceled / Expired")
            @RequestParam(required = false) String status,
            @Parameter(description = "Deposit / DepositSupplement / Rental / Extension / Penalty / Service")
            @RequestParam(required = false) String type,
            @Parameter(description = "Pending / Success / Failed / Duplicate")
            @RequestParam(required = false) String paymentStatus,
            @Parameter(description = "Chỉ giữ giao dịch Pending đã quá số phút này")
            @RequestParam(required = false) Long olderThanMinutes,
            @Parameter(description = "Trang, bắt đầu từ 0") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Số dòng mỗi trang, tối đa 100") @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiEnvelope.ok(LIST_MESSAGE,
                invoiceListQueryService.listForFacility(facilityId, status, type, paymentStatus,
                        olderThanMinutes, page, size)));
    }
}
