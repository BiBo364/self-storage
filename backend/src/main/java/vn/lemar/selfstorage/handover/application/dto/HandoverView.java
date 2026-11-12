package vn.lemar.selfstorage.handover.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Phiên bàn giao cho FS và khách: biên bản + hợp đồng + hoá đơn tháng đầu + quyền truy cập đã cấp.
 *
 * @param step bước kế tiếp: {@code WAITING_ARRIVAL}, {@code VERIFY_IDENTITY}, {@code INSPECTION},
 *             {@code OVERRIDE_PENDING}, {@code SIGN_CONTRACT}, {@code PAY_FIRST_MONTH}, {@code CUSTOMER_CONFIRM},
 *             {@code HAND_OVER_KEYS}; phiên đã đóng thì là {@code result}
 */
@Schema(description = "Phiên bàn giao kho (Flow 2.2–2.5)")
public record HandoverView(
        Long id,
        Long orderId,
        Long appointmentId,
        String result,
        String step,
        String unitCode,
        String unitType,
        Long facilityId,
        String facilityName,
        String facilityAddress,
        Long customerId,
        String customerName,
        String customerEmail,
        String customerPhone,
        LocalDate appointmentDate,
        Instant appointmentStart,
        Instant appointmentEnd,
        Instant arrivedAt,
        String identityStatus,
        Instant identityVerifiedAt,
        @Schema(description = "Ảnh CCCD/hộ chiếu FS chụp ở lần xác minh gần nhất") List<String> identityPhotos,
        String inspectionStatus,
        Instant unitInspectedAt,
        String inspectionNotes,
        List<String> inspectionPhotos,
        String rejectReason,
        Instant dueAt,
        Instant completedAt,
        String requestedAccess,
        Instant customerConfirmedAt,
        Contract contract,
        Invoice firstMonthInvoice,
        List<AccessKey> accessKeys,
        boolean keyAccessEnabled,
        boolean codeAccessEnabled,
        long rejectionCount,
        int maxRejectionCount) {

    @Schema(description = "Hợp đồng thuê đang xử lý của đơn")
    public record Contract(Long id, String code, String status, String termsVersion, BigDecimal monthlyPrice,
                           BigDecimal depositAmount, int period, LocalDate startDate, LocalDate endDate,
                           LocalDate startDateOverrideRequested, String startDateOverrideReason,
                           String startDateOverrideStatus, Instant signedAt, String documentUrl, String pdfUrl) {
    }

    @Schema(description = "Hoá đơn tiền thuê tháng đầu (RNT)")
    public record Invoice(Long id, String code, String status, BigDecimal amount, Instant dueDate) {
    }

    @Schema(description = "Quyền truy cập đã cấp — mã số không bao giờ trả lại sau khi cấp")
    public record AccessKey(Long id, String accessType, Integer quantity, String status, Instant issuedAt) {
    }
}
