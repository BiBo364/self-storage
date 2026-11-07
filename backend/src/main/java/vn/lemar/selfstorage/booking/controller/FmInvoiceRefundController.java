package vn.lemar.selfstorage.booking.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import java.math.BigDecimal;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.lemar.selfstorage.ApiEnvelope;
import vn.lemar.selfstorage.booking.application.ManualRefundService;
import vn.lemar.selfstorage.booking.application.ManualRefundService.RefundSummary;

/**
 * FM ghi nhận hoàn tiền thủ công cho hoá đơn của cơ sở. Role-bound {@code /api/fm/**} + permission
 * {@code payment_transaction.refund} theo cơ sở của hoá đơn.
 */
@RestController
@RequestMapping("/api/fm/invoices/{invoiceId}/refunds")
public class FmInvoiceRefundController {

    private final ManualRefundService refundService;

    public FmInvoiceRefundController(ManualRefundService refundService) {
        this.refundService = refundService;
    }

    @Operation(summary = "Lịch sử hoàn tiền của hoá đơn",
            description = "Các lần FM ghi nhận hoàn tiền + số còn hoàn được (đã thu qua cổng − đã hoàn).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Lịch sử hoàn tiền"),
            @ApiResponse(responseCode = "403", description = "Không phải FM của cơ sở"),
            @ApiResponse(responseCode = "404", description = "Hoá đơn không tồn tại")
    })
    @GetMapping
    public ResponseEntity<ApiEnvelope<RefundSummary>> list(@PathVariable Long invoiceId) {
        return ResponseEntity.ok(ApiEnvelope.ok("Lịch sử hoàn tiền", refundService.summary(invoiceId)));
    }

    @Operation(summary = "FM ghi nhận đã hoàn tiền cho khách",
            description = "FM đã chuyển khoản hoàn cho khách ngoài hệ thống; ghi `PaymentTransaction` chiều `REFUND` "
                    + "(`Success`) và audit `MANUAL_REFUND_RECORDED`. Không vượt số đã thu qua cổng của hoá đơn.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Đã ghi nhận"),
            @ApiResponse(responseCode = "400", description = "Số tiền ≤ 0 hoặc thiếu ghi chú"),
            @ApiResponse(responseCode = "403", description = "Không phải FM của cơ sở"),
            @ApiResponse(responseCode = "404", description = "Hoá đơn không tồn tại"),
            @ApiResponse(responseCode = "409", description = "Số tiền hoàn vượt số đã thu")
    })
    @PostMapping
    public ResponseEntity<ApiEnvelope<RefundSummary>> record(@PathVariable Long invoiceId,
                                                             @RequestBody RefundBody body) {
        RefundSummary summary = refundService.record(invoiceId, body == null ? null : body.amount(),
                body == null ? null : body.note());
        return ResponseEntity.ok(ApiEnvelope.ok("Đã ghi nhận hoàn tiền", summary));
    }

    /** Body ghi nhận hoàn tiền. */
    public record RefundBody(BigDecimal amount, String note) {
    }
}
