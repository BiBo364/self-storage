package vn.lemar.selfstorage.payment.controller;

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
import vn.lemar.selfstorage.payment.application.PaymentReconciliationCaseService;
import vn.lemar.selfstorage.payment.application.dto.RefundCasePageView;

/** Danh sách invoice đang chờ hoàn tiền; read-only, ADMIN xử lý ngoài hệ thống. */
@RestController
@RequestMapping("/api/admin/payments/refunds")
public class AdminRefundPaymentController {

    static final String LIST_MESSAGE = "Lấy danh sách khoản cần hoàn tiền thành công";

    private final PaymentReconciliationCaseService caseService;

    public AdminRefundPaymentController(PaymentReconciliationCaseService caseService) {
        this.caseService = caseService;
    }

    @Operation(summary = "Khoản cần hoàn tiền",
            description = "Các invoice có refund_status=Required, kèm giao dịch PAY/Success gần nhất. "
                    + "page bắt đầu từ 0; size tối đa 100. Chỉ ADMIN; không đổi trạng thái.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Lấy danh sách thành công"),
            @ApiResponse(responseCode = "400", description = "Tham số page/size không hợp lệ",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401", description = "Thiếu / sai / hết hạn token"),
            @ApiResponse(responseCode = "403", description = "Không phải ADMIN")
    })
    @GetMapping
    public ResponseEntity<ApiEnvelope<RefundCasePageView>> list(
            @Parameter(description = "Trang, bắt đầu từ 0") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Số dòng mỗi trang, tối đa 100")
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiEnvelope.ok(LIST_MESSAGE,
                caseService.listRefundRequired(page, size)));
    }
}
