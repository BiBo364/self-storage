package vn.lemar.selfstorage.payment.application;

import java.time.Instant;
import java.util.Map;
import vn.lemar.selfstorage.payment.application.dto.PaymentRequest;

/**
 * Cổng thanh toán, tách khỏi nghiệp vụ để đổi nhà cung cấp không phải sửa flow nào
 * (issue #15 mục 6).
 */
public interface PaymentGateway {

    /** Dựng URL để redirect khách sang cổng thanh toán, dùng thời điểm hiện tại. */
    String buildPaymentUrl(PaymentRequest request);

    /**
     * Dựng lại URL của một phiên đã mở, giữ nguyên {@code vnp_CreateDate}/{@code vnp_ExpireDate}
     * theo {@code createdAt} — dùng khi cache URL bị mất mà phiên vẫn còn hạn.
     */
    String buildPaymentUrl(PaymentRequest request, Instant createdAt);

    /**
     * Kiểm chữ ký trên dữ liệu cổng trả về, dùng cho cả Return URL lẫn IPN.
     *
     * @param params toàn bộ tham số nhận được, kể cả `vnp_SecureHash`
     */
    boolean verifySignature(Map<String, String> params);
}
