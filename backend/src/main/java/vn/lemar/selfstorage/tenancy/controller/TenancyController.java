package vn.lemar.selfstorage.tenancy.controller;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.lemar.selfstorage.ApiEnvelope;
import vn.lemar.selfstorage.booking.application.ContractInvoiceService.ContractBill;
import vn.lemar.selfstorage.identity.application.CurrentAccountProvider;
import vn.lemar.selfstorage.tenancy.application.TenancyService;
import vn.lemar.selfstorage.tenancy.application.TenancyService.RentalView;
import vn.lemar.selfstorage.tenancy.application.TenancyService.ReturnRequestView;

/** Flow 3 — kho đang thuê (khách), yêu cầu trả kho (khách / FM / FS). */
@RestController
public class TenancyController {

    private final TenancyService tenancyService;
    private final CurrentAccountProvider currentAccount;

    public TenancyController(TenancyService tenancyService, CurrentAccountProvider currentAccount) {
        this.tenancyService = tenancyService;
        this.currentAccount = currentAccount;
    }

    @Operation(summary = "Kho đang thuê của tôi", description = "Hợp đồng `Active` + hoá đơn + khoá + yêu cầu trả kho.")
    @GetMapping("/api/customer/rentals")
    public ResponseEntity<ApiEnvelope<List<RentalView>>> rentals() {
        return ResponseEntity.ok(ApiEnvelope.ok("Kho đang thuê", tenancyService.rentals(me())));
    }

    @GetMapping("/api/customer/rentals/{contractId}")
    public ResponseEntity<ApiEnvelope<RentalView>> rental(@PathVariable Long contractId) {
        return ResponseEntity.ok(ApiEnvelope.ok("Chi tiết kho đang thuê", tenancyService.rental(contractId, me())));
    }

    @Operation(summary = "Gia hạn hợp đồng",
            description = "Tạo hoá đơn `Extension` (giá thuê × số tháng); thanh toán xong mới cộng kỳ thuê.")
    @PostMapping("/api/customer/rentals/{contractId}/extensions")
    public ResponseEntity<ApiEnvelope<ContractBill>> extend(@PathVariable Long contractId,
                                                            @Valid @RequestBody ExtendRequest body) {
        return ResponseEntity.ok(ApiEnvelope.ok("Đã tạo hoá đơn gia hạn",
                tenancyService.requestExtension(contractId, me(), body.months())));
    }

    @Operation(summary = "Yêu cầu trả kho", description = "Ngày mong muốn trong 30 ngày tới; FM sẽ phân công FS. "
            + "409 khi đã có yêu cầu đang xử lý hoặc còn hoá đơn gia hạn `Unpaid`.")
    @PostMapping("/api/customer/rentals/{contractId}/return-request")
    public ResponseEntity<ApiEnvelope<ReturnRequestView>> requestReturn(@PathVariable Long contractId,
                                                                        @Valid @RequestBody ReturnBody body) {
        return ResponseEntity.ok(ApiEnvelope.ok("Đã gửi yêu cầu trả kho",
                tenancyService.requestReturn(contractId, me(), body.preferredDate(), body.note())));
    }

    @GetMapping("/api/customer/return-requests")
    public ResponseEntity<ApiEnvelope<List<ReturnRequestView>>> myReturns() {
        return ResponseEntity.ok(ApiEnvelope.ok("Yêu cầu trả kho của tôi", tenancyService.myReturns(me())));
    }

    @PostMapping("/api/customer/return-requests/{id}/cancel")
    public ResponseEntity<ApiEnvelope<ReturnRequestView>> cancelReturn(@PathVariable Long id) {
        return ResponseEntity.ok(ApiEnvelope.ok("Đã huỷ yêu cầu trả kho", tenancyService.cancelReturn(id, me())));
    }

    @Operation(summary = "Yêu cầu trả kho đang mở của cơ sở (FM)")
    @GetMapping("/api/fm/return-requests")
    public ResponseEntity<ApiEnvelope<List<ReturnRequestView>>> facilityReturns(@RequestParam Long facilityId) {
        return ResponseEntity.ok(ApiEnvelope.ok("Yêu cầu trả kho", tenancyService.facilityReturns(facilityId)));
    }

    @Operation(summary = "Phân công FS cho buổi trả kho (FM)",
            description = "Tạo lịch `RETURN` ở khung trống đầu tiên từ ngày khách chọn (dùng chung khung check-in).")
    @PostMapping("/api/fm/return-requests/{id}/assign")
    public ResponseEntity<ApiEnvelope<ReturnRequestView>> assign(@PathVariable Long id,
                                                                 @Valid @RequestBody AssignBody body) {
        return ResponseEntity.ok(ApiEnvelope.ok("Đã phân công FS", tenancyService.assign(id, body.staffId())));
    }

    @Operation(summary = "Buổi trả kho được phân cho tôi (FS)")
    @GetMapping("/api/staff/return-requests")
    public ResponseEntity<ApiEnvelope<List<ReturnRequestView>>> staffReturns() {
        return ResponseEntity.ok(ApiEnvelope.ok("Buổi trả kho", tenancyService.staffReturns()));
    }

    private Long me() {
        return currentAccount.getCurrentAccountId();
    }

    public record ExtendRequest(@NotNull Integer months) {
    }

    public record ReturnBody(@NotNull LocalDate preferredDate, @Size(max = 1000) String note) {
    }

    public record AssignBody(@NotNull Long staffId) {
    }
}
