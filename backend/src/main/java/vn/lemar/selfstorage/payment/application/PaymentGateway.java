package vn.lemar.selfstorage.payment.application;

import java.time.Instant;
import java.util.Map;
import vn.lemar.selfstorage.payment.application.dto.GatewayQueryResult;
import vn.lemar.selfstorage.payment.application.dto.PaymentRequest;

/**
 * Cổng thanh toán, tách khỏi nghiệp vụ để đổi nhà cung cấp không phải sửa flow nào.
 */
public interface PaymentGateway {

    /** Dựng URL để redirect khách sang cổng thanh toán. */
    String buildPaymentUrl(PaymentRequest request);

    /**
     * Kiểm chữ ký trên dữ liệu cổng trả về, dùng cho cả Return URL lẫn IPN.
     *
     * @param params toàn bộ tham số nhận được, kể cả `vnp_SecureHash`
     */
    boolean verifySignature(Map<String, String> params);

    /**
     * Hỏi cổng trạng thái thật của giao dịch ({@code querydr}). Nguồn đối soát dự phòng khi IPN không về —
     * kênh server-to-server có chữ ký, không phải dữ liệu trình duyệt gửi lên.
     *
     * @param createdAt mốc {@code vnp_CreateDate} lúc tạo phiên thanh toán
     */
    GatewayQueryResult query(String txnRef, Instant createdAt, String orderInfo, String ipAddr);

    /**
     * Mã giao dịch phía mình cho một lượt thanh toán hoá đơn. Mặc định {@code INV{id}T{yyMMddHHmmss}{4 ký tự}};
     * cổng đòi mã số (payOS {@code orderCode}) thì override.
     */
    default String newTxnRef(Long invoiceId) {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        java.util.concurrent.ThreadLocalRandom r = java.util.concurrent.ThreadLocalRandom.current();
        StringBuilder suffix = new StringBuilder(4);
        for (int i = 0; i < 4; i++) {
            suffix.append(chars.charAt(r.nextInt(chars.length())));
        }
        return "INV" + invoiceId + "T" + java.time.format.DateTimeFormatter.ofPattern("yyMMddHHmmss")
                .withZone(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).format(Instant.now()) + suffix;
    }

    /**
     * Huỷ phiên thanh toán ở cổng để khách không trả được nữa. {@code true} = cổng xác nhận đã huỷ. Cổng không hỗ
     * trợ huỷ (VNPay — phiên tự hết hạn) trả {@code false}.
     */
    default boolean cancel(String txnRef, String reason) {
        return false;
    }

    /** Đọc + kiểm chữ ký thông báo thanh toán dạng body (webhook). Rỗng = sai chữ ký / không đọc được. */
    default java.util.Optional<vn.lemar.selfstorage.payment.application.dto.GatewayNotification> parseNotification(
            String rawBody) {
        return java.util.Optional.empty();
    }
}
