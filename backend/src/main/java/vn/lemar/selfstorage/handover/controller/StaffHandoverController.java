package vn.lemar.selfstorage.handover.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.lemar.selfstorage.ApiEnvelope;
import vn.lemar.selfstorage.booking.application.HandoverOrderService.ContractInvoice;
import vn.lemar.selfstorage.handover.application.HandoverQueryService;
import vn.lemar.selfstorage.handover.application.HandoverWorkflowService;
import vn.lemar.selfstorage.handover.application.dto.HandoverView;

/** FS — "Đơn đang bàn giao" (Flow 2.2–2.5), chỉ thao tác trên lịch được phân cho mình. */
@RestController
@RequestMapping("/api/staff")
public class StaffHandoverController {

    private final HandoverQueryService queryService;
    private final HandoverWorkflowService workflow;

    public StaffHandoverController(HandoverQueryService queryService, HandoverWorkflowService workflow) {
        this.queryService = queryService;
        this.workflow = workflow;
    }

    @Operation(summary = "Đơn đang bàn giao của FS",
            description = "Phiên `IN_PROGRESS`, khách đã đến, lịch phân cho FS.")
    @GetMapping("/handovers")
    public ResponseEntity<ApiEnvelope<List<HandoverView>>> queue() {
        return ResponseEntity.ok(ApiEnvelope.ok("Danh sách phiên bàn giao", queryService.staffQueue()));
    }

    @Operation(summary = "Chi tiết phiên bàn giao")
    @GetMapping("/handovers/{id}")
    public ResponseEntity<ApiEnvelope<HandoverView>> detail(@PathVariable Long id) {
        return ResponseEntity.ok(ApiEnvelope.ok("Chi tiết phiên bàn giao", queryService.forStaff(id)));
    }

    @Operation(summary = "Xác minh danh tính (2.2)",
            description = "`verified=false` → `identity_status = Failed`, phiên vẫn mở (không huỷ lịch/đơn).")
    @PostMapping("/handovers/{id}/identity")
    public ResponseEntity<ApiEnvelope<HandoverView>> identity(@PathVariable Long id,
                                                              @Valid @RequestBody IdentityRequest body) {
        workflow.verifyIdentity(id, body.verified(), body.note());
        return ok("Đã ghi nhận xác minh danh tính", id);
    }

    @Operation(summary = "Ghi hiện trạng khoang (2.2)", description = "Ảnh upload trước qua `/api/media`.")
    @PutMapping("/handovers/{id}/inspection")
    public ResponseEntity<ApiEnvelope<HandoverView>> inspection(@PathVariable Long id,
                                                                @Valid @RequestBody InspectionRequest body) {
        workflow.recordInspection(id, body.notes(), body.photos());
        return ok("Đã lưu hiện trạng khoang", id);
    }

    @Operation(summary = "Khách không ký hợp đồng (2.5)",
            description = "Hợp đồng còn `Draft` → Cancellation Cascade \"Khách không ký hợp đồng\".")
    @PostMapping("/handovers/{id}/not-signed")
    public ResponseEntity<ApiEnvelope<HandoverView>> notSigned(@PathVariable Long id) {
        workflow.customerDidNotSign(id);
        return ok("Đã huỷ đơn do khách không ký hợp đồng", id);
    }

    @Operation(summary = "Giao chìa khóa và chốt bàn giao (2.4)",
            description = "Chỉ khi khách đã xác nhận biên bản và chọn khóa cơ / cả hai.")
    @PostMapping("/handovers/{id}/keys")
    public ResponseEntity<ApiEnvelope<HandoverView>> keys(@PathVariable Long id, @Valid @RequestBody KeyRequest body) {
        workflow.handOverKeys(id, body.quantity());
        return ok("Bàn giao hoàn tất", id);
    }

    @Operation(summary = "Đề nghị đổi mốc tính tiền (2.3)", description = "Hợp đồng `Draft`; FM duyệt mới ký được.")
    @PostMapping("/contracts/{contractId}/start-date-override")
    public ResponseEntity<ApiEnvelope<Void>> override(@PathVariable Long contractId,
                                                      @Valid @RequestBody OverrideRequest body) {
        workflow.requestStartDateOverride(contractId, body.requestedDate(), body.reason());
        return ResponseEntity.ok(ApiEnvelope.ok("Đã gửi đề nghị đổi mốc tính tiền cho FM", null));
    }

    @Operation(summary = "Upload hợp đồng đã ký (2.3)",
            description = "Hợp đồng `Draft → Signed`, tạo hoá đơn tháng đầu `INV-RNT-…` trong cùng transaction.")
    @PostMapping("/contracts/{contractId}/sign")
    public ResponseEntity<ApiEnvelope<ContractInvoice>> sign(@PathVariable Long contractId,
                                                             @Valid @RequestBody SignRequest body) {
        ContractInvoice invoice = workflow.signContract(contractId, body.pdfUrl());
        return ResponseEntity.ok(ApiEnvelope.ok("Đã ký hợp đồng, tạo hoá đơn tháng đầu", invoice));
    }

    private ResponseEntity<ApiEnvelope<HandoverView>> ok(String message, Long id) {
        return ResponseEntity.ok(ApiEnvelope.ok(message, queryService.view(id)));
    }

    @Schema(description = "Kết quả xác minh danh tính")
    public record IdentityRequest(@NotNull Boolean verified, @Size(max = 500) String note) {
    }

    @Schema(description = "Hiện trạng khoang")
    public record InspectionRequest(@Size(max = 2000) String notes, @Size(max = 20) List<@NotBlank String> photos) {
    }

    @Schema(description = "Số chìa khóa giao")
    public record KeyRequest(@NotNull Integer quantity) {
    }

    @Schema(description = "Đề nghị đổi mốc tính tiền")
    public record OverrideRequest(@NotNull LocalDate requestedDate, @NotBlank @Size(max = 500) String reason) {
    }

    @Schema(description = "URL bản scan hợp đồng đã ký (upload qua /api/media)")
    public record SignRequest(@NotBlank @Size(max = 1000) String pdfUrl) {
    }
}
