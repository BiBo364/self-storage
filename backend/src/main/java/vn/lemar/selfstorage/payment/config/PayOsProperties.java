package vn.lemar.selfstorage.payment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Cấu hình payOS (VietQR), đọc từ biến môi trường qua {@code application.yml}. Kiểm bắt buộc nằm ở
 * {@code PayOsGateway} — chỉ khi payOS là cổng đang dùng ({@code payment.provider=payos}) thì thiếu key mới làm app
 * chết lúc khởi động.
 *
 * @param clientId    {@code x-client-id} của kênh thanh toán
 * @param apiKey      {@code x-api-key}
 * @param checksumKey khoá ký HMAC-SHA256 (tạo link, kiểm webhook)
 * @param baseUrl     gốc API merchant
 * @param returnUrl   nơi payOS đẩy trình duyệt khách về sau khi trả tiền (trang kết quả của FE)
 * @param cancelUrl   nơi payOS đẩy khách về khi bấm huỷ trên trang thanh toán
 */
@ConfigurationProperties(prefix = "payos")
public record PayOsProperties(
        String clientId,
        String apiKey,
        String checksumKey,
        @DefaultValue("https://api-merchant.payos.vn") String baseUrl,
        @DefaultValue("http://localhost:5173/payments/result") String returnUrl,
        @DefaultValue("http://localhost:5173/payments/result") String cancelUrl) {
}
