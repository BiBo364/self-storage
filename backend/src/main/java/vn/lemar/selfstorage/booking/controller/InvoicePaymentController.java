package vn.lemar.selfstorage.booking.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.lemar.selfstorage.ApiEnvelope;
import vn.lemar.selfstorage.ApiError;
import vn.lemar.selfstorage.booking.application.InvoiceListView;
import vn.lemar.selfstorage.booking.application.InvoicePaymentService;
import vn.lemar.selfstorage.booking.application.InvoicePaymentService.PayResult;
import vn.lemar.selfstorage.booking.application.InvoicePaymentService.PaymentCheck;
import vn.lemar.selfstorage.booking.application.InvoiceQueryService;
import vn.lemar.selfstorage.booking.application.dto.StartDepositResponse;
import vn.lemar.selfstorage.identity.application.AccountQueryService;

/**
 * Khách xem và tự mở phiên thanh toán cho hóa đơn của mình (spec 1.4 / 2.3 / 3.x). Route ownership trong
 * {@code /api/**} ({@code ACCOUNT_ACTIVE}); service kiểm hoá đơn thuộc khách đang đăng nhập.
 */
@RestController
@RequestMapping("/api/invoices")
public class InvoicePaymentController {

    static final String DETAIL_MESSAGE = "Lấy hóa đơn thành công";
    static final String PAY_MESSAGE = "Đã tạo phiên thanh toán";
    static final String CHECK_MESSAGE = "Đã kiểm tra trạng thái thanh toán";

    private final InvoicePaymentService paymentService;
    private final InvoiceQueryService invoiceQuery;
    private final AccountQueryService accountQueryService;

    public InvoicePaymentController(InvoicePaymentService paymentService, InvoiceQueryService invoiceQuery,
                                    AccountQueryService accountQueryService) {
        this.paymentService = paymentService;
        this.invoiceQuery = invoiceQuery;
        this.accountQueryService = accountQueryService;
    }

    @Operation(summary = "Hóa đơn của tôi",
            description = "Hóa đơn theo id, chỉ chủ hóa đơn đọc được. FE gọi lại đường này sau khi "
                    + "cổng thanh toán redirect về để lấy trạng thái thật — return URL chỉ nói giao "
                    + "dịch thành công hay không, trạng thái hóa đơn do IPN / webhook chốt ở server.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Lấy hóa đơn thành công"),
            @ApiResponse(responseCode = "401", description = "Thiếu / sai / hết hạn token"),
            @ApiResponse(responseCode = "403",
                    description = "Tài khoản không còn hoạt động hoặc chưa xác minh email"),
            @ApiResponse(responseCode = "404",
                    description = "Hóa đơn không tồn tại, hoặc không thuộc về người gọi",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiError.class),
                            examples = @ExampleObject(value = """
                                    {"message":"Không tìm thấy hóa đơn",
                                    "timestamp":"2026-11-02T08:14:28.048550698Z"}""")))
    })
    @GetMapping("/{invoiceId}")
    public ResponseEntity<ApiEnvelope<InvoiceListView>> detail(@PathVariable Long invoiceId) {
        return ResponseEntity.ok(ApiEnvelope.ok(DETAIL_MESSAGE, invoiceQuery.findOwn(invoiceId)));
    }

    @Operation(summary = "Mở phiên thanh toán hóa đơn",
            description = "Hoá đơn cọc (`Deposit`/`DepositSupplement`): kiểm năm nhánh theo spec Flow 1.4. Hoá đơn "
                    + "gắn hợp đồng (tháng đầu, gia hạn, tiền thuê, phạt): hoá đơn còn `Unpaid`, chưa quá hạn, "
                    + "đơn chưa đóng. Rồi tạo `PaymentTransaction(Pending)` và trả URL cổng; phiên còn mở trong 15' "
                    + "thì trả lại URL cũ. **Không** ghi nhận đã thu tiền — việc đó do IPN / webhook làm. "
                    + "Số tiền lấy từ hóa đơn trong DB, không nhận từ client.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Đã mở phiên, FE redirect sang `paymentUrl`"),
            @ApiResponse(responseCode = "403",
                    description = "Khách chưa xác minh email",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiError.class),
                            examples = @ExampleObject(value = """
                                    {"message":"Vui lòng xác minh email trước khi thực hiện thao tác này",
                                    "timestamp":"2026-10-13T08:14:28.048550698Z"}"""))),
            @ApiResponse(responseCode = "404",
                    description = "Hóa đơn không tồn tại, hoặc không thuộc về người gọi",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiError.class),
                            examples = @ExampleObject(value = """
                                    {"message":"Không tìm thấy hóa đơn",
                                    "timestamp":"2026-10-13T08:14:28.048550698Z"}"""))),
            @ApiResponse(responseCode = "409",
                    description = "Các nhánh từ chối: đơn quá hạn · khoang đã được đặt cọc · khoang "
                            + "đang bảo trì · khoang đang có phiên thanh toán khác · hoá đơn không còn "
                            + "chờ thanh toán / quá hạn · đơn đã đóng · hoá đơn đã có giao dịch thành công "
                            + "nhưng chưa chốt · phiên cũ không dựng lại được",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiError.class),
                            examples = {
                                    @ExampleObject(name = "Đơn quá hạn", value = """
                                            {"message":"Đơn thuê kho đã hết hạn. Vui lòng \
                                            liên hệ cơ sở để được hỗ trợ.","timestamp":"2026-10-13T08:14:28Z"}"""),
                                    @ExampleObject(name = "Khoang đã có người cọc", value = """
                                            {"message":"Khoang chứa đã được đặt cọc bởi khách hàng \
                                            khác. Hóa đơn này đã hết hiệu lực.","timestamp":"2026-10-13T08:14:28Z"}""")
                            }))
    })
    @PostMapping("/{invoiceId}/pay")
    public ResponseEntity<?> pay(Authentication authentication, @PathVariable Long invoiceId,
                                 HttpServletRequest request) {
        PayResult result = paymentService.start(invoiceId, currentAccountId(authentication), clientIp(request));
        if (result.rejected()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new PayRejected(result.rejectionMessage(), result.invoiceCanceled(),
                            ApiError.now(result.rejectionMessage()).timestamp()));
        }
        return ResponseEntity.ok(ApiEnvelope.ok(PAY_MESSAGE, new StartDepositResponse(result.paymentUrl())));
    }

    @Operation(summary = "Kiểm tra kết quả thanh toán",
            description = "Gọi khi khách quay về từ cổng. Body (tuỳ chọn) `gatewayParams` là các tham số `vnp_*` "
                    + "VNPay gắn trên Return URL: chữ ký HMAC đúng, đúng merchant, đúng số tiền và giao dịch thuộc hoá "
                    + "đơn này thì chốt như IPN. Sau đó hỏi cổng về giao dịch mới nhất của hoá đơn (VNPay `querydr` / "
                    + "tra cứu payOS — server-to-server, có chữ ký; nguồn dự phòng khi IPN / webhook chưa tới) rồi trả "
                    + "trạng thái hoá đơn + giao dịch. Giao dịch đã chốt thì không gọi cổng; cổng chưa có kết quả thì "
                    + "giao dịch giữ `Pending`. Tham số sai chữ ký / không thuộc hoá đơn bị bỏ qua, không lỗi.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Trạng thái hoá đơn/giao dịch sau khi hỏi cổng"),
            @ApiResponse(responseCode = "400", description = "`gatewayParams` quá 50 tham số"),
            @ApiResponse(responseCode = "401", description = "Thiếu / sai / hết hạn token"),
            @ApiResponse(responseCode = "404", description = "Hoá đơn không tồn tại hoặc không thuộc khách")
    })
    @PostMapping("/{invoiceId}/payment-check")
    public ResponseEntity<ApiEnvelope<PaymentCheck>> check(Authentication authentication,
                                                           @PathVariable Long invoiceId,
                                                           @Valid @RequestBody(required = false)
                                                           PaymentCheckRequest body,
                                                           HttpServletRequest request) {
        Map<String, String> gatewayParams = body == null || body.gatewayParams() == null
                ? Map.of() : body.gatewayParams();
        PaymentCheck check = paymentService.check(invoiceId, currentAccountId(authentication), clientIp(request),
                gatewayParams);
        return ResponseEntity.ok(ApiEnvelope.ok(CHECK_MESSAGE, check));
    }

    @Schema(description = "Tham số cổng khách mang về từ Return URL (VNPay: các khoá `vnp_*` kèm `vnp_SecureHash`)")
    public record PaymentCheckRequest(@Size(max = 50) Map<String, String> gatewayParams) {
    }

    private Long currentAccountId(Authentication authentication) {
        return accountQueryService.requireIdByEmail(authentication.getName());
    }

    /**
     * VNPay bắt buộc {@code vnp_IpAddr}: sau proxy lấy IP đầu tiên trong {@code X-Forwarded-For}; loopback IPv6 quy
     * về IPv4.
     */
    static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        String ip = request.getRemoteAddr();
        if (ip == null || ip.isBlank() || "0:0:0:0:0:0:0:1".equals(ip) || "::1".equals(ip)) {
            return "127.0.0.1";
        }
        return ip;
    }

    @Schema(description = "Từ chối thanh toán (409) — `invoiceCanceled` cho biết hoá đơn đã bị huỷ")
    public record PayRejected(String message, boolean invoiceCanceled, String timestamp) {
    }
}
