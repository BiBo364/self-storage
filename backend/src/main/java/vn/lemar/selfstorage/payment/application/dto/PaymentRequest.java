package vn.lemar.selfstorage.payment.application.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Yêu cầu tạo một lượt thanh toán.
 *
 * @param txnRef     mã tham chiếu của phía mình, không được trùng trong ngày (VNPay yêu cầu)
 * @param amount     số tiền VND như người dùng thấy; việc nhân 100 do gateway lo
 * @param orderInfo  mô tả hiển thị trên cổng thanh toán
 * @param ipAddr     IP của khách, VNPay bắt buộc
 */
public record PaymentRequest(String txnRef, BigDecimal amount, String orderInfo, String ipAddr, Instant createdAt) {

    /** Mốc tạo phiên ({@code vnp_CreateDate}) chưa biết — gateway dùng thời điểm hiện tại. */
    public PaymentRequest(String txnRef, BigDecimal amount, String orderInfo, String ipAddr) {
        this(txnRef, amount, orderInfo, ipAddr, null);
    }

    /**
     * Gắn mốc tạo giao dịch để {@code vnp_CreateDate} khớp với {@code PaymentTransaction.created_at} —
     * {@code querydr} cần đúng mốc này khi đối soát.
     */
    public PaymentRequest withCreatedAt(Instant value) {
        return new PaymentRequest(txnRef, amount, orderInfo, ipAddr, value);
    }


    /** Chặn dữ liệu sai ngay tại ranh giới caller — DB cũng chặn, nhưng lỗi ở đây dễ hiểu hơn. */
    public PaymentRequest {
        if (txnRef == null || txnRef.isBlank()) {
            throw new IllegalArgumentException("txnRef bắt buộc");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("amount phải là số dương");
        }
    }
}
