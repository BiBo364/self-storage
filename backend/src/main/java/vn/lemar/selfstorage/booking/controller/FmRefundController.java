package vn.lemar.selfstorage.booking.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.lemar.selfstorage.ApiEnvelope;
import vn.lemar.selfstorage.identity.application.Access;
import vn.lemar.selfstorage.payment.application.PaymentReconciliationCaseService;
import vn.lemar.selfstorage.payment.application.dto.RefundCasePageView;

/** Queue refund Required theo facility cho FM; read-only. */
@RestController
@RequestMapping("/api/fm/invoices/refunds")
public class FmRefundController {

    private static final String PERMISSION = "payment_transaction.refund";

    private final PaymentReconciliationCaseService cases;
    private final Access access;

    public FmRefundController(PaymentReconciliationCaseService cases, Access access) {
        this.cases = cases;
        this.access = access;
    }

    @Operation(summary = "Khoản cần hoàn tiền của cơ sở",
            description = "Invoice có refund_status=Required thuộc facility FM phụ trách; read-only.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Lấy danh sách thành công"),
            @ApiResponse(responseCode = "400", description = "page/size không hợp lệ"),
            @ApiResponse(responseCode = "403", description = "Ngoài facility hoặc không đủ quyền")
    })
    @GetMapping
    public ResponseEntity<ApiEnvelope<RefundCasePageView>> list(
            @Parameter(description = "Id cơ sở") @RequestParam Long facilityId,
            @Parameter(description = "Trang, bắt đầu từ 0") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Số dòng mỗi trang, tối đa 100")
            @RequestParam(defaultValue = "20") int size) {
        access.can(PERMISSION, facilityId);
        return ResponseEntity.ok(ApiEnvelope.ok("Lấy danh sách khoản cần hoàn tiền thành công",
                cases.listRefundRequiredForFacility(facilityId, page, size)));
    }
}
