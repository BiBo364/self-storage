package vn.lemar.selfstorage.handover.controller;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import vn.lemar.selfstorage.ApiEnvelope;
import vn.lemar.selfstorage.handover.application.ContractDocumentService;
import vn.lemar.selfstorage.handover.application.HandoverWorkflowService;
import vn.lemar.selfstorage.handover.repository.HandoverReadRepository;
import vn.lemar.selfstorage.handover.repository.HandoverReadRepository.OverrideRequestRow;
import vn.lemar.selfstorage.identity.application.CurrentAccountProvider;

/** Tài liệu hợp đồng (khách / FS / FM) và duyệt đổi mốc tính tiền (FM, Flow 2.3). */
@RestController
public class ContractController {

    private final ContractDocumentService documentService;
    private final HandoverWorkflowService workflow;
    private final HandoverReadRepository readRepository;
    private final CurrentAccountProvider currentAccount;

    public ContractController(ContractDocumentService documentService, HandoverWorkflowService workflow,
                              HandoverReadRepository readRepository, CurrentAccountProvider currentAccount) {
        this.documentService = documentService;
        this.workflow = workflow;
        this.readRepository = readRepository;
        this.currentAccount = currentAccount;
    }

    @Operation(summary = "Bản hợp đồng (HTML để in)",
            description = "Đã ký: trả bản chốt lúc ký. Chưa ký: render từ mẫu `builtin-v1` + dữ liệu hiện tại; "
                    + "còn đề nghị đổi mốc `Pending` → 409.")
    @GetMapping(value = "/api/contracts/{contractId}/document", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> document(@PathVariable Long contractId) {
        return ResponseEntity.ok()
                .contentType(new MediaType(MediaType.TEXT_HTML, java.nio.charset.StandardCharsets.UTF_8))
                .body(documentService.render(contractId));
    }

    @Operation(summary = "Đề nghị đổi mốc tính tiền đang chờ (FM)")
    @GetMapping("/api/fm/contracts/start-date-overrides")
    public ResponseEntity<ApiEnvelope<List<OverrideRequestRow>>> pendingOverrides() {
        return ResponseEntity.ok(ApiEnvelope.ok("Đề nghị đổi mốc tính tiền đang chờ",
                readRepository.findPendingOverridesForManager(currentAccount.getCurrentAccountId())));
    }

    @PostMapping("/api/fm/contracts/{contractId}/start-date-override/approve")
    public ResponseEntity<ApiEnvelope<Void>> approve(@PathVariable Long contractId,
                                                     @Valid @RequestBody(required = false) ReviewRequest body) {
        workflow.reviewStartDateOverride(contractId, true, body == null ? null : body.reason());
        return ResponseEntity.ok(ApiEnvelope.ok("Đã duyệt mốc tính tiền mới", null));
    }

    @PostMapping("/api/fm/contracts/{contractId}/start-date-override/reject")
    public ResponseEntity<ApiEnvelope<Void>> reject(@PathVariable Long contractId,
                                                    @Valid @RequestBody(required = false) ReviewRequest body) {
        workflow.reviewStartDateOverride(contractId, false, body == null ? null : body.reason());
        return ResponseEntity.ok(ApiEnvelope.ok("Đã từ chối đổi mốc tính tiền", null));
    }

    public record ReviewRequest(@Size(max = 500) String reason) {
    }
}
