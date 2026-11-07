package vn.lemar.selfstorage.booking.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.lemar.selfstorage.ApiEnvelope;
import vn.lemar.selfstorage.ApiError;
import vn.lemar.selfstorage.booking.application.InvoicePaymentService;
import vn.lemar.selfstorage.booking.application.InvoicePaymentService.PayResult;
import vn.lemar.selfstorage.booking.application.InvoicePaymentService.PaymentCheck;
import vn.lemar.selfstorage.identity.application.AccountQueryService;

/**
 * Spec 1.4 / 2.3 — khách thanh toán hoá đơn qua VNPay. Route ownership trong {@code /api/**}
 * ({@code ACCOUNT_ACTIVE}); service kiểm hoá đơn thuộc khách đang đăng nhập.
 */
@RestController
@RequestMapping("/api/invoices")
public class InvoicePaymentController {

    static final String PAY_MESSAGE = "Đã tạo phiên thanh toán, chuyển sang cổng VNPay";
    static final String CHECK_MESSAGE = "Đã kiểm tra trạng thái thanh toán";

    private final InvoicePaymentService paymentService;
    private final AccountQueryService accountQueryService;

    public InvoicePaymentController(InvoicePaymentService paymentService, AccountQueryService accountQueryService) {
        this.paymentService = paymentService;
        this.accountQueryService = accountQueryService;
    }

    @Operation(summary = "Tiến hành thanh toán hoá đơn",
            description = "Kiểm theo thứ tự spec 1.4: đơn quá hạn → 409; khoang đã có người cọc / bảo trì → hoá đơn "
                    + "chuyển `Canceled`, FM nhận `PROPOSAL_REPROPOSAL_REQUIRED`, trả 409 (thay đổi vẫn được lưu, "
                    + "`invoiceCanceled = true`); khoang đang có phiên thanh toán khác chưa hết 15' → 409; còn lại ghi "
                    + "`PaymentTransaction(Pending)` và trả `paymentUrl` để FE redirect sang VNPay. Hoá đơn tháng đầu "
                    + "(gắn hợp đồng) bỏ qua các bước kiểm khoang.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Có `paymentUrl` để chuyển hướng"),
            @ApiResponse(responseCode = "401", description = "Thiếu / sai / hết hạn token"),
            @ApiResponse(responseCode = "404", description = "Hoá đơn không tồn tại hoặc không thuộc khách"),
            @ApiResponse(responseCode = "409", description = "Hoá đơn không còn Unpaid / quá hạn; đơn đã đóng / hết "
                    + "hạn; khoang không còn khả dụng; khoang đang có phiên thanh toán khác")
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
        return ResponseEntity.ok(ApiEnvelope.ok(PAY_MESSAGE, new PayResponse(result.paymentUrl())));
    }

    @Operation(summary = "Kiểm tra kết quả thanh toán",
            description = "Gọi khi khách quay về từ cổng: đối soát giao dịch mới nhất của hoá đơn bằng `querydr` "
                    + "(server-to-server, có chữ ký — nguồn dự phòng khi IPN chưa tới) rồi trả trạng thái hoá đơn + "
                    + "giao dịch. Giao dịch đã chốt thì không gọi cổng.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Trạng thái hoá đơn/giao dịch sau đối soát"),
            @ApiResponse(responseCode = "401", description = "Thiếu / sai / hết hạn token"),
            @ApiResponse(responseCode = "404", description = "Hoá đơn không tồn tại hoặc không thuộc khách")
    })
    @PostMapping("/{invoiceId}/payment-check")
    public ResponseEntity<ApiEnvelope<PaymentCheck>> check(Authentication authentication,
                                                           @PathVariable Long invoiceId,
                                                           HttpServletRequest request) {
        PaymentCheck check = paymentService.check(invoiceId, currentAccountId(authentication), clientIp(request));
        return ResponseEntity.ok(ApiEnvelope.ok(CHECK_MESSAGE, check));
    }

    private Long currentAccountId(Authentication authentication) {
        return accountQueryService.requireIdByEmail(authentication.getName());
    }

    /** VNPay bắt buộc {@code vnp_IpAddr}; loopback IPv6 quy về IPv4. */
    static String clientIp(HttpServletRequest request) {
        String ip = request.getRemoteAddr();
        if (ip == null || ip.isBlank() || "0:0:0:0:0:0:0:1".equals(ip) || "::1".equals(ip)) {
            return "127.0.0.1";
        }
        return ip;
    }

    @Schema(description = "URL cổng VNPay để FE chuyển hướng")
    public record PayResponse(String paymentUrl) {
    }

    @Schema(description = "Từ chối thanh toán (409) — `invoiceCanceled` cho biết hoá đơn đã bị huỷ")
    public record PayRejected(String message, boolean invoiceCanceled, String timestamp) {
    }
}
