package vn.lemar.selfstorage.payment.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/**
 * Ops nhập kết quả tra cứu trên portal VNPay cho một giao dịch còn {@code Pending}.
 *
 * @param outcome            {@code SUCCESS} hoặc {@code FAILED}
 * @param note               bằng chứng / ghi chú của người đối soát, bắt buộc
 * @param paidAt             thời điểm trả theo portal; bắt buộc khi {@code outcome=SUCCESS}
 * @param gatewayTransactionNo mã giao dịch VNPay ({@code vnp_TransactionNo}); bắt buộc khi
 *                             {@code outcome=SUCCESS}, không được trùng giao dịch khác
 */
public record ManualReconciliationRequest(
        @NotNull ManualReconciliationOutcome outcome,
        @NotBlank @Size(max = 500) String note,
        Instant paidAt,
        @Size(max = 100) String gatewayTransactionNo) {
}
