package vn.lemar.selfstorage.payment.application.event;

import java.math.BigDecimal;

/**
 * Cổng báo giao dịch thất bại. Hóa đơn giữ nguyên chưa thanh toán.
 *
 * <p>Quy ước listener giống {@link PaymentSucceeded}: event được publish trong transaction IPN,
 * listener phải {@code AFTER_COMMIT} (hoặc {@code REQUIRES_NEW}).
 */
public record PaymentFailed(Long invoiceId, String vnpTxnRef, BigDecimal amount, String reason, Long transactionId) {

    public PaymentFailed(Long invoiceId, String vnpTxnRef, BigDecimal amount, String reason) {
        this(invoiceId, vnpTxnRef, amount, reason, null);
    }
}
