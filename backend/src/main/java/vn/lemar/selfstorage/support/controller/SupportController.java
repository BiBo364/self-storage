package vn.lemar.selfstorage.support.controller;

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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.lemar.selfstorage.ApiEnvelope;
import vn.lemar.selfstorage.identity.application.CurrentAccountProvider;
import vn.lemar.selfstorage.support.application.SupportService;
import vn.lemar.selfstorage.support.application.SupportService.CreateInput;
import vn.lemar.selfstorage.support.application.SupportService.ResolveInput;
import vn.lemar.selfstorage.support.application.SupportService.SupportView;
import vn.lemar.selfstorage.support.application.UnitTransferService;
import vn.lemar.selfstorage.support.application.UnitTransferService.TransferCandidate;
import vn.lemar.selfstorage.support.application.UnitTransferService.TransferCompletion;

/** Flow 7 — yêu cầu hỗ trợ: khách / FM / FS. */
@RestController
public class SupportController {

    private final SupportService supportService;
    private final UnitTransferService transferService;
    private final CurrentAccountProvider currentAccount;

    public SupportController(SupportService supportService, UnitTransferService transferService,
                             CurrentAccountProvider currentAccount) {
        this.supportService = supportService;
        this.transferService = transferService;
        this.currentAccount = currentAccount;
    }

    @GetMapping("/api/customer/support-requests")
    public ResponseEntity<ApiEnvelope<List<SupportView>>> mine() {
        return ResponseEntity.ok(ApiEnvelope.ok("Yêu cầu hỗ trợ của tôi", supportService.mine(me())));
    }

    @Operation(summary = "Gửi yêu cầu hỗ trợ",
            description = "Gắn `contractId` (hợp đồng đang thuê) hoặc chọn `facilityId`. `LOST_KEY` bắt buộc hợp đồng. "
                    + "`UNIT_ISSUE` bắt buộc hợp đồng đang thuê và ≥ 1 ảnh (URL khách đã upload) — thiếu / sai → 400.")
    @PostMapping("/api/customer/support-requests")
    public ResponseEntity<ApiEnvelope<SupportView>> create(@Valid @RequestBody CreateBody body) {
        return ResponseEntity.ok(ApiEnvelope.ok("Đã gửi yêu cầu hỗ trợ", supportService.create(me(),
                new CreateInput(body.contractId(), body.facilityId(), body.category(), body.title(),
                        body.description(), body.photos()))));
    }

    @PostMapping("/api/customer/support-requests/{id}/cancel")
    public ResponseEntity<ApiEnvelope<SupportView>> cancel(@PathVariable Long id) {
        return ResponseEntity.ok(ApiEnvelope.ok("Đã huỷ yêu cầu hỗ trợ", supportService.cancel(id, me())));
    }

    @Operation(summary = "Yêu cầu hỗ trợ của cơ sở (FM)", description = "Mặc định chỉ yêu cầu đang mở.")
    @GetMapping("/api/fm/support-requests")
    public ResponseEntity<ApiEnvelope<List<SupportView>>> facility(@RequestParam Long facilityId,
            @RequestParam(defaultValue = "false") boolean includeClosed) {
        return ResponseEntity.ok(ApiEnvelope.ok("Yêu cầu hỗ trợ",
                supportService.facilityRequests(facilityId, includeClosed)));
    }

    @Operation(summary = "Giao FS xử lý yêu cầu hỗ trợ (FM)",
            description = "FS đang hoạt động của cơ sở (400). 409 khi yêu cầu đã đóng, FS nghỉ phép hôm nay, hoặc "
                    + "(policy `staff.shift_required` bật) FS không có ca tại cơ sở hôm nay.")
    @PostMapping("/api/fm/support-requests/{id}/assign")
    public ResponseEntity<ApiEnvelope<SupportView>> assign(@PathVariable Long id, @Valid @RequestBody AssignBody body) {
        return ResponseEntity.ok(ApiEnvelope.ok("Đã giao FS", supportService.assign(id, body.staffId())));
    }

    @PostMapping("/api/fm/support-requests/{id}/resolve")
    public ResponseEntity<ApiEnvelope<SupportView>> resolveByManager(@PathVariable Long id,
                                                                     @Valid @RequestBody ResolveBody body) {
        return resolve(id, body);
    }

    @GetMapping("/api/staff/support-requests")
    public ResponseEntity<ApiEnvelope<List<SupportView>>> staff() {
        return ResponseEntity.ok(ApiEnvelope.ok("Yêu cầu hỗ trợ được giao", supportService.staffRequests()));
    }

    @Operation(summary = "Xử lý yêu cầu hỗ trợ",
            description = "`newKeyQuantity` > 0 → chìa cũ `Lost`, cấp chìa mới; `feeAmount` > 0 → hoá đơn `Service`. "
                    + "Yêu cầu `LOST_KEY` có thay chìa mà bỏ trống `feeAmount` → phí mặc định "
                    + "`fee.lost_key × số chìa của bộ đang dùng`; gửi `feeAmount` (kể cả 0) thì dùng đúng số đó.")
    @PostMapping("/api/staff/support-requests/{id}/resolve")
    public ResponseEntity<ApiEnvelope<SupportView>> resolve(@PathVariable Long id,
                                                            @Valid @RequestBody ResolveBody body) {
        return ResponseEntity.ok(ApiEnvelope.ok("Đã xử lý yêu cầu hỗ trợ", supportService.resolve(id,
                new ResolveInput(body.resolution(), body.newKeyQuantity(), body.feeAmount()))));
    }

    @Operation(summary = "FS đề xuất chuyển khoang (khoang hư không sửa ngay được)",
            description = "Chỉ FS được giao, yêu cầu `Assigned` gắn hợp đồng `Active` → `Transferring`; FM nhận thông "
                    + "báo. Hợp đồng đang có yêu cầu trả kho / chuyển khoang chưa xong → 409.")
    @PostMapping("/api/staff/support-requests/{id}/transfer")
    public ResponseEntity<ApiEnvelope<SupportView>> requestTransfer(@PathVariable Long id,
                                                                    @Valid @RequestBody TransferRequestBody body) {
        return ResponseEntity.ok(ApiEnvelope.ok("Đã đề xuất chuyển khoang",
                transferService.request(id, body.reason())));
    }

    @Operation(summary = "Khoang trống để chuyển (FM)",
            description = "Khoang `Available` cùng cơ sở; `sameType` = cùng loại (cùng giá), `allowed` = được chọn — "
                    + "còn khoang cùng loại thì chỉ cùng loại, hết mới cho loại diện tích ≥ khoang cũ.")
    @GetMapping("/api/fm/support-requests/{id}/transfer/candidates")
    public ResponseEntity<ApiEnvelope<List<TransferCandidate>>> transferCandidates(@PathVariable Long id) {
        return ResponseEntity.ok(ApiEnvelope.ok("Khoang trống", transferService.candidates(id)));
    }

    @Operation(summary = "FM điều khoang cho chuyển khoang",
            description = "Khoang mới `Reserved`; điều lại thì nhả khoang trước. Khoang không hợp lệ → 400 / 409.")
    @PostMapping("/api/fm/support-requests/{id}/transfer/allocate")
    public ResponseEntity<ApiEnvelope<SupportView>> allocateTransfer(@PathVariable Long id,
                                                                     @Valid @RequestBody AllocateBody body) {
        return ResponseEntity.ok(ApiEnvelope.ok("Đã điều khoang", transferService.allocate(id, body.unitId())));
    }

    @Operation(summary = "FM huỷ chuyển khoang", description = "Nhả khoang đang giữ, yêu cầu về `Assigned`.")
    @PostMapping("/api/fm/support-requests/{id}/transfer/cancel")
    public ResponseEntity<ApiEnvelope<SupportView>> cancelTransfer(@PathVariable Long id,
                                                                   @Valid @RequestBody CancelTransferBody body) {
        return ResponseEntity.ok(ApiEnvelope.ok("Đã huỷ chuyển khoang", transferService.cancel(id, body.reason())));
    }

    @Operation(summary = "Hoàn tất chuyển khoang (FS được giao / FM)",
            description = "Khoang mới `Rented`, khoang cũ `Maintenance`, hợp đồng đổi khoang (giá giữ nguyên), "
                    + "chìa / mã cũ thu hồi và cấp lại; `keyQuantity` bắt buộc khi hợp đồng đang dùng chìa cơ. "
                    + "`accessCode` = mã khoá số mới (chỉ trả một lần).")
    @PostMapping("/api/staff/support-requests/{id}/transfer/complete")
    public ResponseEntity<ApiEnvelope<TransferCompletion>> completeTransfer(@PathVariable Long id,
                                                                            @Valid @RequestBody CompleteBody body) {
        return ResponseEntity.ok(ApiEnvelope.ok("Đã chuyển khoang", transferService.complete(id,
                body.keyQuantity())));
    }

    private Long me() {
        return currentAccount.getCurrentAccountId();
    }

    public record CreateBody(Long contractId, Long facilityId, @NotBlank String category,
                             @NotBlank @Size(max = 255) String title, @NotBlank @Size(max = 4000) String description,
                             @Size(max = 10) List<@NotBlank String> photos) {
    }

    public record AssignBody(@NotNull Long staffId) {
    }

    public record ResolveBody(@NotBlank @Size(max = 2000) String resolution, Integer newKeyQuantity,
                              BigDecimal feeAmount) {
    }

    public record TransferRequestBody(@NotBlank @Size(max = 1000) String reason) {
    }

    public record AllocateBody(@NotNull Long unitId) {
    }

    public record CancelTransferBody(@NotBlank @Size(max = 500) String reason) {
    }

    public record CompleteBody(Integer keyQuantity) {
    }
}
