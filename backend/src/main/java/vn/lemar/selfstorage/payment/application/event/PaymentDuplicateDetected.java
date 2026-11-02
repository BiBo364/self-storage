package vn.lemar.selfstorage.payment.application.event;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Một giao dịch thành công thứ hai cho invoice đã có {@code PAY/Success}. Không phát
 * {@link PaymentSucceeded}; booking chỉ notify FM/ops để hoàn tiền thủ công.
 */
public record PaymentDuplicateDetected(Long invoiceId, String vnpTxnRef, BigDecimal amount,
                                       Instant paidAt) {
}
