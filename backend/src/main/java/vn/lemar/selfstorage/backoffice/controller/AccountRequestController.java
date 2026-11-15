package vn.lemar.selfstorage.backoffice.controller;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.lemar.selfstorage.ApiEnvelope;
import vn.lemar.selfstorage.backoffice.application.AccountRequestService;
import vn.lemar.selfstorage.backoffice.application.AccountRequestService.AccountRequestRow;

/**
 * Yêu cầu tạo tài khoản nhân sự: BOM gửi / huỷ ({@code /api/bom/**}, role BOM), ADMIN duyệt / từ chối
 * ({@code /api/admin/**}, role ADMIN). Lỗi dữ liệu → 400, xung đột trạng thái → 409
 * ({@link BackofficeExceptionHandler}).
 */
@RestController
public class AccountRequestController {

    private final AccountRequestService requests;

    public AccountRequestController(AccountRequestService requests) {
        this.requests = requests;
    }

    @Operation(summary = "BOM — danh sách yêu cầu tạo tài khoản", description = "Mọi yêu cầu, mới nhất trước.")
    @GetMapping("/api/bom/account-requests")
    public ResponseEntity<ApiEnvelope<List<AccountRequestRow>>> bomList() {
        return ResponseEntity.ok(ApiEnvelope.ok("Yêu cầu tạo tài khoản", requests.list(null)));
    }

    @Operation(summary = "BOM — gửi yêu cầu tạo tài khoản",
            description = "role FM (đúng 1 cơ sở đang trống FM) · FS (≥ 1 cơ sở); BOM do ADMIN tự tạo. Email đã có "
                    + "tài khoản / đang có yêu cầu chờ, cơ sở đã có FM → 409; dữ liệu sai → 400. ADMIN nhận thông báo. "
                    + "replacesAccountId = thay FM / FS nghỉ việc (cùng role, đang ACTIVE): cơ sở lấy theo người cũ, "
                    + "facilityIds bỏ qua; người đó đã có yêu cầu thay đang chờ → 409.")
    @PostMapping("/api/bom/account-requests")
    public ResponseEntity<ApiEnvelope<AccountRequestRow>> submit(@Valid @RequestBody SubmitBody body) {
        return ResponseEntity.ok(ApiEnvelope.ok("Đã gửi yêu cầu tạo tài khoản", requests.submit(body.role(),
                body.email(), body.name(), body.phone(), body.facilityIds(), body.note(), body.replacesAccountId())));
    }

    @Operation(summary = "BOM — huỷ yêu cầu đang chờ", description = "Chỉ người gửi; đã xử lý → 409.")
    @PostMapping("/api/bom/account-requests/{id}/cancel")
    public ResponseEntity<ApiEnvelope<AccountRequestRow>> cancel(@PathVariable Long id) {
        return ResponseEntity.ok(ApiEnvelope.ok("Đã huỷ yêu cầu", requests.cancel(id)));
    }

    @Operation(summary = "ADMIN — danh sách yêu cầu tạo tài khoản",
            description = "status = Pending | Approved | Rejected | Canceled; bỏ trống = tất cả.")
    @GetMapping("/api/admin/account-requests")
    public ResponseEntity<ApiEnvelope<List<AccountRequestRow>>> adminList(
            @RequestParam(required = false) String status) {
        return ResponseEntity.ok(ApiEnvelope.ok("Yêu cầu tạo tài khoản", requests.list(status)));
    }

    @Operation(summary = "ADMIN — duyệt và tạo tài khoản",
            description = "Tạo tài khoản với mật khẩu ban đầu (≥ 8 ký tự, ADMIN tự đưa cho người dùng), gán cơ sở "
                    + "(FS: các cơ sở; FM: cơ sở phải còn trống FM). Người gửi nhận thông báo; người được tạo nhận "
                    + "email báo tài khoản sẵn sàng (không kèm mật khẩu). Yêu cầu thay người: FM mới thế chỗ FM cũ; FS "
                    + "mới nhận cơ sở + việc còn mở của FS cũ; tài khoản cũ → INACTIVE. Lỗi bất kỳ → không tạo gì, "
                    + "yêu cầu vẫn chờ.")
    @PostMapping("/api/admin/account-requests/{id}/approve")
    public ResponseEntity<ApiEnvelope<AccountRequestRow>> approve(@PathVariable Long id,
                                                                  @Valid @RequestBody ApproveBody body) {
        return ResponseEntity.ok(ApiEnvelope.ok("Đã tạo tài khoản", requests.approve(id, body.password())));
    }

    @Operation(summary = "ADMIN — từ chối yêu cầu", description = "Bắt buộc lý do; người gửi nhận thông báo.")
    @PostMapping("/api/admin/account-requests/{id}/reject")
    public ResponseEntity<ApiEnvelope<AccountRequestRow>> reject(@PathVariable Long id,
                                                                 @Valid @RequestBody RejectBody body) {
        return ResponseEntity.ok(ApiEnvelope.ok("Đã từ chối yêu cầu", requests.reject(id, body.reason())));
    }

    public record SubmitBody(@NotBlank String role, @NotBlank String email, @NotBlank String name, String phone,
                              List<Long> facilityIds, String note, Long replacesAccountId) {
    }

    public record ApproveBody(@NotBlank @Size(min = 8, max = 100) String password) {
    }

    public record RejectBody(@NotBlank @Size(max = 500) String reason) {
    }
}
