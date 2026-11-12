package vn.lemar.selfstorage.payment.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.lemar.selfstorage.payment.application.PaymentGateway;
import vn.lemar.selfstorage.payment.application.PaymentService;
import vn.lemar.selfstorage.payment.application.dto.IpnResponse;

/**
 * Hai đường VNPay gọi ngược về sau khi khách thanh toán.
 *
 * <p><strong>Cả hai đều public</strong> (xem {@code SecurityConfig}): server VNPay gọi {@code /ipn}
 * và không có JWT, còn {@code /return} là redirect trình duyệt nên cũng không mang
 * {@code Authorization}. Thứ thay cho token là chữ ký HMAC-SHA512 trong {@code vnp_SecureHash}.
 *
 * <p><strong>Ngoại lệ về envelope:</strong> {@code /ipn} <em>không</em> dùng {@code ApiEnvelope}
 * như mọi route khác. VNPay đọc phản hồi theo đúng hai key {@code RspCode}/{@code Message} ở mức
 * gốc; bọc thêm một lớp là VNPay không parse được, coi như merchant chưa xác nhận và retry đủ
 * 10 lần. Đây là contract của bên thứ ba, không phải chỗ để áp quy ước nội bộ.
 */
@RestController
@RequestMapping("/api/payments/vnpay")
public class VnPayCallbackController {

    private static final Logger LOG = LoggerFactory.getLogger(VnPayCallbackController.class);

    private final PaymentService paymentService;
    private final PaymentGateway gateway;

    public VnPayCallbackController(PaymentService paymentService, PaymentGateway gateway) {
        this.paymentService = paymentService;
        this.gateway = gateway;
    }

    /**
     * Kiểm chữ ký tham số VNPay gắn trên Return URL. Chỉ hiển thị kết quả, **không** cập nhật dữ liệu —
     * chốt thanh toán đi qua IPN, hoặc {@code payment-check} khi trang kết quả FE gửi kèm bộ tham số có chữ ký.
     */
    @SecurityRequirements
    @Operation(summary = "Return URL của VNPay (kiểm chữ ký)",
            description = "Trả kết quả kiểm chữ ký của tham số VNPay — dùng làm Return URL khi test backend không có "
                    + "FE. Không ghi dữ liệu; chốt thanh toán qua IPN hoặc payment-check (trang kết quả FE gửi kèm "
                    + "tham số có chữ ký).")
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "Luôn 200, kể cả khi chữ ký sai — kết quả nằm trong `validSignature`",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {"validSignature":true,"responseCode":"00","txnRef":"ORD1738"}""")))
    })
    @GetMapping("/return")
    public ResponseEntity<Map<String, Object>> handleReturn(@RequestParam Map<String, String> params) {
        boolean validSignature = gateway.verifySignature(params);
        return ResponseEntity.ok(Map.of(
                "validSignature", validSignature,
                "responseCode", params.getOrDefault("vnp_ResponseCode", ""),
                "txnRef", params.getOrDefault("vnp_TxnRef", "")));
    }

    /**
     * Nơi server VNPay gọi vào. Đây mới là nguồn xác nhận thanh toán.
     *
     * <p>Lỗi ngoài dự kiến được dịch thành {@code 99} thay vì để bật lên HTTP 500: VNPay đọc được
     * mã này và sẽ retry. Bắt ở đây chứ không bắt trong service để transaction vẫn rollback.
     */
    @SecurityRequirements
    @Operation(summary = "IPN của VNPay",
            description = "Server VNPay gọi vào để xác nhận thanh toán. Public vì VNPay không có "
                    + "JWT; xác thực bằng chữ ký HMAC-SHA512 trong `vnp_SecureHash`. Idempotent "
                    + "theo `vnp_TxnRef` — gọi lại lần hai trả `02` và không ghi gì thêm. "
                    + "**Không dùng ApiEnvelope**: VNPay đòi đúng hai key `RspCode`/`Message`.")
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "Luôn 200. Kết quả nằm ở `RspCode`: `00` xác nhận thành công · "
                            + "`02` đơn đã xác nhận trước đó · `01` không tìm thấy đơn · "
                            + "`04` số tiền không khớp · `97` sai chữ ký · `99` lỗi không xác định. "
                            + "VNPay retry tối đa 10 lần, cách nhau 5 phút, khi nhận `01/04/97/99`.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = IpnResponse.class),
                            examples = {
                                    @ExampleObject(name = "Xác nhận thành công",
                                            value = """
                                                    {"RspCode":"00","Message":"Confirm Success"}"""),
                                    @ExampleObject(name = "IPN lặp — đã xác nhận trước đó",
                                            value = """
                                                    {"RspCode":"02","Message":"Order already confirmed"}"""),
                                    @ExampleObject(name = "Sai chữ ký",
                                            value = """
                                                    {"RspCode":"97","Message":"Invalid signature"}""")
                            }))
    })
    @GetMapping("/ipn")
    public ResponseEntity<IpnResponse> handleIpn(@RequestParam Map<String, String> params) {
        try {
            return ResponseEntity.ok(paymentService.handleIpn(params));
        } catch (RuntimeException e) {
            LOG.error("IPN lỗi ngoài dự kiến, vnp_TxnRef={}", params.get("vnp_TxnRef"), e);
            return ResponseEntity.ok(IpnResponse.unknownError());
        }
    }
}
