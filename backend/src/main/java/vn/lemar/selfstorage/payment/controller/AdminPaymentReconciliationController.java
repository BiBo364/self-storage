package vn.lemar.selfstorage.payment.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.lemar.selfstorage.ApiEnvelope;
import vn.lemar.selfstorage.payment.application.PaymentService;
import vn.lemar.selfstorage.payment.application.dto.ManualReconciliationRequest;
import vn.lemar.selfstorage.payment.application.dto.ManualReconciliationResponse;

/**
 * Lớp đối soát thủ công: ADMIN tra portal VNPay rồi nhập kết quả cho giao dịch còn Pending.
 *
 * <p>Đây là thay thế cho job querydr tự động. Endpoint chỉ nhận giao dịch {@code Pending}, khoá row
 * và ghi audit + event như IPN; không sửa DB tay.
 */
@RestController
@RequestMapping("/api/admin/payments")
public class AdminPaymentReconciliationController {

    private static final String RECONCILED_MESSAGE = "Đã đối soát giao dịch";

    private final PaymentService paymentService;

    public AdminPaymentReconciliationController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @Operation(summary = "Chốt thủ công giao dịch thanh toán Pending",
            description = "ADMIN nhập kết quả tra cứu portal VNPay. Chỉ giao dịch `Pending`; ghi "
                    + "audit, phát event như IPN. Giao dịch đã chốt hoặc `gatewayTransactionNo` "
                    + "trùng trả 409; invoice đã có `PAY/Success` khác trả 200 với status "
                    + "`DUPLICATE`.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Đã chốt giao dịch, hoặc "
                    + "status=DUPLICATE khi invoice đã có PAY/Success khác"),
            @ApiResponse(responseCode = "400", description = "Thiếu/sai outcome, note, hoặc "
                    + "paidAt/gatewayTransactionNo khi SUCCESS"),
            @ApiResponse(responseCode = "403", description = "Không phải ADMIN"),
            @ApiResponse(responseCode = "404", description = "Không tìm thấy giao dịch"),
            @ApiResponse(responseCode = "409", description = "Giao dịch đã chốt hoặc "
                    + "gatewayTransactionNo trùng")
    })
    @PostMapping("/{txnRef}/reconcile")
    public ResponseEntity<ApiEnvelope<ManualReconciliationResponse>> reconcile(
            @PathVariable String txnRef,
            @Valid @RequestBody ManualReconciliationRequest request,
            Authentication authentication) {
        ManualReconciliationResponse response =
                paymentService.reconcileManually(txnRef, request, authentication.getName());
        return ResponseEntity.ok(ApiEnvelope.ok(RECONCILED_MESSAGE, response));
    }
}
