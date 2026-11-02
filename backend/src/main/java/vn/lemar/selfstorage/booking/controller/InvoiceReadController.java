package vn.lemar.selfstorage.booking.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.lemar.selfstorage.ApiEnvelope;
import vn.lemar.selfstorage.booking.application.InvoiceListView;
import vn.lemar.selfstorage.booking.application.InvoiceListQueryService;

/**
 * Khách xem toàn bộ hoá đơn của mình. Ownership: service lọc theo {@code customer_id}; không dùng
 * RBAC permission. Route nằm trong {@code /api/**} chung, không phải {@code /api/customer/**}.
 */
@RestController
@RequestMapping("/api/invoices")
public class InvoiceReadController {

    static final String LIST_MESSAGE = "Lấy danh sách hoá đơn thành công";

    private final InvoiceListQueryService invoiceListQueryService;

    public InvoiceReadController(InvoiceListQueryService invoiceListQueryService) {
        this.invoiceListQueryService = invoiceListQueryService;
    }

    @Operation(summary = "Hoá đơn của tôi",
            description = "Toàn bộ hoá đơn của account đang đăng nhập, mới nhất trước. MVP không lọc/phân trang. "
                    + "Kèm khoang và cơ sở khi hoá đơn gắn đơn thuê; không trả dữ liệu thanh toán.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Lấy danh sách thành công"),
            @ApiResponse(responseCode = "401", description = "Thiếu / sai / hết hạn token"),
            @ApiResponse(responseCode = "403", description = "Tài khoản không còn hoạt động hoặc chưa xác minh email")
    })
    @GetMapping
    public ResponseEntity<ApiEnvelope<List<InvoiceListView>>> list() {
        return ResponseEntity.ok(ApiEnvelope.ok(LIST_MESSAGE, invoiceListQueryService.listOwn()));
    }
}
