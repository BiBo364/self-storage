package vn.lemar.selfstorage.payment.application.event;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Cổng đã xác nhận thu tiền thành công.
 *
 * <p>Module sở hữu hóa đơn nghe sự kiện này để chuyển `Invoice` sang `Paid`. Module `payment`
 * không tự đụng vào hóa đơn — ranh giới module.
 *
 * <p><strong>Listener bắt buộc AFTER_COMMIT:</strong> event được publish <em>trong</em> transaction
 * xử lý IPN. Listener phải đăng ký {@code @TransactionalEventListener(phase = AFTER_COMMIT)}
 * (hoặc {@code REQUIRES_NEW}) — listener đồng bộ mặc định sẽ thấy state chưa commit, và exception
 * từ listener sẽ rollback giao dịch IPN khiến VNPay retry đủ 10 lần.
 */
public record PaymentSucceeded(Long invoiceId, String vnpTxnRef, BigDecimal amount, Instant paidAt,
                               Long transactionId) {

    public PaymentSucceeded(Long invoiceId, String vnpTxnRef, BigDecimal amount, Instant paidAt) {
        this(invoiceId, vnpTxnRef, amount, paidAt, null);
    }
}
