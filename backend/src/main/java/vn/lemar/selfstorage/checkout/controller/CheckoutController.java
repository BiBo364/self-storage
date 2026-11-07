package vn.lemar.selfstorage.checkout.controller;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import vn.lemar.selfstorage.ApiEnvelope;
import vn.lemar.selfstorage.checkout.application.CheckoutService;
import vn.lemar.selfstorage.checkout.application.CheckoutService.CheckoutView;
import vn.lemar.selfstorage.checkout.application.CheckoutService.Inspection;
import vn.lemar.selfstorage.identity.application.CurrentAccountProvider;

/** Trả kho: FS kiểm tra + chốt; khách xem biên bản trả kho. */
@RestController
public class CheckoutController {

    private final CheckoutService checkoutService;
    private final CurrentAccountProvider currentAccount;

    public CheckoutController(CheckoutService checkoutService, CurrentAccountProvider currentAccount) {
        this.checkoutService = checkoutService;
        this.currentAccount = currentAccount;
    }

    @Operation(summary = "Biên bản trả kho (FS)", description = "Kèm phí dự kiến: vệ sinh, mất chìa, hoá đơn còn nợ.")
    @GetMapping("/api/staff/return-requests/{id}/checkout")
    public ResponseEntity<ApiEnvelope<CheckoutView>> staffView(@PathVariable Long id) {
        return ResponseEntity.ok(ApiEnvelope.ok("Biên bản trả kho", checkoutService.forStaff(id)));
    }

    @Operation(summary = "Ghi kết quả kiểm tra khoang (FS)",
            description = "`unitEmptied=false` → `PENDING_ITEMS` (giữ quyền truy cập, kiểm lại sau).")
    @PutMapping("/api/staff/return-requests/{id}/checkout")
    public ResponseEntity<ApiEnvelope<CheckoutView>> inspect(@PathVariable Long id,
                                                             @Valid @RequestBody InspectionBody body) {
        return ResponseEntity.ok(ApiEnvelope.ok("Đã lưu kết quả kiểm tra", checkoutService.inspect(id,
                new Inspection(body.unitEmptied(), Boolean.TRUE.equals(body.cleaningRequired()),
                        body.damageAmount(), body.damages(), body.returnedKeys(), body.photos(), body.note()))));
    }

    @Operation(summary = "Chốt trả kho (FS)",
            description = "Thu hồi khoá, gộp phí + hoá đơn còn nợ, đối trừ cọc. Dư → FM hoàn tay, hoàn tất ngay; "
                    + "thiếu → hoá đơn phần còn thiếu (`AWAITING_PAYMENT`), trả xong mới hoàn tất.")
    @PostMapping("/api/staff/return-requests/{id}/checkout/confirm")
    public ResponseEntity<ApiEnvelope<CheckoutView>> confirm(@PathVariable Long id) {
        return ResponseEntity.ok(ApiEnvelope.ok("Đã chốt biên bản trả kho", checkoutService.confirm(id)));
    }

    @GetMapping("/api/customer/return-requests/{id}/checkout")
    public ResponseEntity<ApiEnvelope<CheckoutView>> customerView(@PathVariable Long id) {
        return ResponseEntity.ok(ApiEnvelope.ok("Biên bản trả kho",
                checkoutService.forCustomer(id, currentAccount.getCurrentAccountId())));
    }

    public record InspectionBody(@NotNull Boolean unitEmptied, Boolean cleaningRequired, BigDecimal damageAmount,
                                 @Size(max = 2000) String damages, Integer returnedKeys,
                                 @Size(max = 20) List<@NotBlank String> photos, @Size(max = 2000) String note) {
    }
}
