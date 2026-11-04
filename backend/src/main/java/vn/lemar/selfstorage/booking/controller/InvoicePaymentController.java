package vn.lemar.selfstorage.booking.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.lemar.selfstorage.ApiEnvelope;
import vn.lemar.selfstorage.ApiError;
import vn.lemar.selfstorage.booking.application.DepositPaymentService;
import vn.lemar.selfstorage.booking.application.InvoiceQueryService;
import vn.lemar.selfstorage.booking.application.InvoiceListView;
import vn.lemar.selfstorage.booking.application.dto.StartDepositResponse;

/** Khách xem và tự mở phiên thanh toán cho hóa đơn của mình. */
@RestController
@RequestMapping("/api/invoices")
public class InvoicePaymentController {

    static final String DETAIL_MESSAGE = "Lấy hóa đơn thành công";

    private final DepositPaymentService depositPayments;
    private final InvoiceQueryService invoiceQuery;

    public InvoicePaymentController(DepositPaymentService depositPayments,
            InvoiceQueryService invoiceQuery) {
        this.depositPayments = depositPayments;
        this.invoiceQuery = invoiceQuery;
    }

    @Operation(summary = "Hóa đơn của tôi",
            description = "Hóa đơn theo id, chỉ chủ hóa đơn đọc được. FE gọi lại đường này sau khi "
                    + "cổng thanh toán redirect về để lấy trạng thái thật — return URL chỉ nói giao "
                    + "dịch thành công hay không, trạng thái hóa đơn do IPN chốt ở server.")
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

    @Operation(summary = "Mở phiên thanh toán hóa đơn cọc",
            description = "Kiểm năm nhánh theo spec Flow 1.4 rồi tạo `PaymentTransaction(Pending)` "
                    + "và trả URL VNPay. **Không** ghi nhận đã thu tiền — việc đó do IPN làm. "
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
                            + "chờ thanh toán cọc · hoá đơn đã có giao dịch thành công nhưng chưa chốt",
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
    public ResponseEntity<ApiEnvelope<StartDepositResponse>> pay(
            @PathVariable Long invoiceId, HttpServletRequest request) {
        String paymentUrl = depositPayments.startDeposit(invoiceId, clientIp(request));
        return ResponseEntity.ok(ApiEnvelope.ok("Đã tạo phiên thanh toán",
                new StartDepositResponse(paymentUrl)));
    }

    /** VNPay bắt buộc `vnp_IpAddr`; sau proxy thì lấy IP đầu tiên trong `X-Forwarded-For`. */
    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
