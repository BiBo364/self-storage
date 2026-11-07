package vn.lemar.selfstorage.payment.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.lemar.selfstorage.payment.application.PaymentService;
import vn.lemar.selfstorage.payment.application.PaymentService.NotificationResult;

/**
 * Webhook payOS — server payOS báo kết quả chuyển khoản. Public (payOS không có JWT); xác thực bằng chữ ký
 * HMAC-SHA256 trong body. Không dùng {@code ApiEnvelope}: payOS chỉ cần HTTP 2xx.
 */
@RestController
@RequestMapping("/api/payments/payos")
public class PayOsWebhookController {

    private static final Logger LOG = LoggerFactory.getLogger(PayOsWebhookController.class);

    private final PaymentService paymentService;

    public PayOsWebhookController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @SecurityRequirements
    @Operation(summary = "Webhook payOS",
            description = "payOS gọi khi có chuyển khoản khớp link. Kiểm chữ ký `data` (HMAC-SHA256, "
                    + "checksum key) → tìm giao dịch theo `orderCode` (khoá row) → số tiền → còn `Pending` thì "
                    + "ghi `Success`/`Failed` và phát event chốt hoá đơn. Gọi lặp trả `ALREADY_SETTLED`. "
                    + "Mã giao dịch không tồn tại (request thử lúc khai báo webhook) vẫn trả 200.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "`{success, result}` — `ACCEPTED`, `ALREADY_SETTLED`, "
                    + "`NOT_FOUND`"),
            @ApiResponse(responseCode = "400", description = "Sai chữ ký / body không đọc được / số tiền lệch")
    })
    @PostMapping("/webhook")
    public ResponseEntity<Map<String, Object>> webhook(@RequestBody String body) {
        NotificationResult result;
        try {
            result = paymentService.handleNotification(body);
        } catch (RuntimeException e) {
            LOG.error("Webhook payOS lỗi ngoài dự kiến", e);
            return ResponseEntity.internalServerError().body(Map.of("success", false, "result", "ERROR"));
        }
        boolean rejected = result == NotificationResult.INVALID_SIGNATURE
                || result == NotificationResult.INVALID_AMOUNT;
        Map<String, Object> response = Map.of("success", !rejected, "result", result.name());
        return rejected ? ResponseEntity.badRequest().body(response) : ResponseEntity.ok(response);
    }
}
