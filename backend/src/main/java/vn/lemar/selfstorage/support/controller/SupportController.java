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

/** Flow 7 — yêu cầu hỗ trợ: khách / FM / FS. */
@RestController
public class SupportController {

    private final SupportService supportService;
    private final CurrentAccountProvider currentAccount;

    public SupportController(SupportService supportService, CurrentAccountProvider currentAccount) {
        this.supportService = supportService;
        this.currentAccount = currentAccount;
    }

    @GetMapping("/api/customer/support-requests")
    public ResponseEntity<ApiEnvelope<List<SupportView>>> mine() {
        return ResponseEntity.ok(ApiEnvelope.ok("Yêu cầu hỗ trợ của tôi", supportService.mine(me())));
    }

    @Operation(summary = "Gửi yêu cầu hỗ trợ",
            description = "Gắn `contractId` (hợp đồng đang thuê) hoặc chọn `facilityId`. `LOST_KEY` bắt buộc hợp đồng.")
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
}
