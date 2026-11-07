package vn.lemar.selfstorage.handover.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.lemar.selfstorage.ApiEnvelope;
import vn.lemar.selfstorage.handover.application.HandoverQueryService;
import vn.lemar.selfstorage.handover.application.HandoverWorkflowService;
import vn.lemar.selfstorage.handover.application.HandoverWorkflowService.ConfirmOutcome;
import vn.lemar.selfstorage.handover.application.HandoverWorkflowService.RejectOutcome;
import vn.lemar.selfstorage.handover.application.dto.HandoverView;
import vn.lemar.selfstorage.handover.application.exception.HandoverValidationException;
import vn.lemar.selfstorage.handover.domain.AccessChoice;
import vn.lemar.selfstorage.identity.application.CurrentAccountProvider;

/** Khách — "Kho của tôi" › Bàn giao (Flow 2.2, 2.4, 2.5). Huỷ đơn dùng lại API huỷ đơn của booking. */
@RestController
@RequestMapping("/api/customer")
public class CustomerHandoverController {

    private final HandoverQueryService queryService;
    private final HandoverWorkflowService workflow;
    private final CurrentAccountProvider currentAccount;

    public CustomerHandoverController(HandoverQueryService queryService, HandoverWorkflowService workflow,
                                      CurrentAccountProvider currentAccount) {
        this.queryService = queryService;
        this.workflow = workflow;
        this.currentAccount = currentAccount;
    }

    @Operation(summary = "Phiên bàn giao của tôi", description = "Phiên đang mở trước, sau đó các phiên đã đóng.")
    @GetMapping("/handovers")
    public ResponseEntity<ApiEnvelope<List<HandoverView>>> list() {
        return ResponseEntity.ok(ApiEnvelope.ok("Danh sách phiên bàn giao", queryService.forCustomer(me())));
    }

    @GetMapping("/handovers/{id}")
    public ResponseEntity<ApiEnvelope<HandoverView>> detail(@PathVariable Long id) {
        return ResponseEntity.ok(ApiEnvelope.ok("Chi tiết phiên bàn giao", queryService.forCustomer(id, me())));
    }

    @Operation(summary = "Đồng ý hiện trạng khoang (2.2)", description = "Sinh hợp đồng `Draft` (2.3).")
    @PostMapping("/handovers/{id}/agree")
    public ResponseEntity<ApiEnvelope<HandoverView>> agree(@PathVariable Long id,
                                                           @Valid @RequestBody(required = false) AgreeRequest body) {
        Long me = me();
        workflow.agreeInspection(id, me, body == null ? null : body.note());
        return ResponseEntity.ok(ApiEnvelope.ok("Đã xác nhận hiện trạng khoang", queryService.forCustomer(id, me)));
    }

    @Operation(summary = "Từ chối khoang (2.5)",
            description = "Chạm `handover.max_rejection_count` mà `confirmCancel=false` → `requiresConfirmation=true`, "
                    + "không ghi gì. `confirmCancel=true` → huỷ đơn \"Từ chối quá số khoang cho phép\", "
                    + "không hoàn cọc.")
    @PostMapping("/handovers/{id}/reject")
    public ResponseEntity<ApiEnvelope<RejectOutcome>> reject(@PathVariable Long id,
                                                             @Valid @RequestBody RejectRequest body) {
        RejectOutcome outcome = workflow.reject(id, me(), body.reason(), Boolean.TRUE.equals(body.confirmCancel()));
        return ResponseEntity.ok(ApiEnvelope.ok(outcome.message(), outcome));
    }

    @Operation(summary = "Xác nhận biên bản + chọn hình thức nhận quyền truy cập (2.4)",
            description = "`AccessCode` → chốt bàn giao ngay, trả mã một lần; `PhysicalKey`/`Both` → chờ FS giao chìa.")
    @PostMapping("/handovers/{id}/confirm")
    public ResponseEntity<ApiEnvelope<ConfirmOutcome>> confirm(@PathVariable Long id,
                                                               @Valid @RequestBody ConfirmRequest body) {
        ConfirmOutcome outcome = workflow.confirm(id, me(), choice(body.accessType()));
        return ResponseEntity.ok(ApiEnvelope.ok(outcome.message(), outcome));
    }

    @Operation(summary = "Lấy mã khóa số mới",
            description = "Thu hồi mã đang dùng, cấp mã mới — mã chỉ hiển thị trong response này.")
    @PostMapping("/contracts/{contractId}/access-code")
    public ResponseEntity<ApiEnvelope<AccessCodeResponse>> accessCode(@PathVariable Long contractId) {
        String code = workflow.rotateAccessCode(contractId, me());
        return ResponseEntity.ok(ApiEnvelope.ok("Đã cấp mã truy cập mới", new AccessCodeResponse(code)));
    }

    private Long me() {
        return currentAccount.getCurrentAccountId();
    }

    private static AccessChoice choice(String value) {
        try {
            return AccessChoice.fromDbValue(value);
        } catch (IllegalArgumentException ex) {
            throw new HandoverValidationException("accessType phải là AccessCode, PhysicalKey hoặc Both");
        }
    }

    public record AgreeRequest(@Size(max = 1000) String note) {
    }

    public record RejectRequest(@NotBlank @Size(max = 500) String reason, Boolean confirmCancel) {
    }

    @Schema(description = "AccessCode | PhysicalKey | Both")
    public record ConfirmRequest(@NotNull String accessType) {
    }

    public record AccessCodeResponse(String accessCode) {
    }
}
