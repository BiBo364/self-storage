package vn.lemar.selfstorage.payment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Cấu hình VNPay, đọc từ biến môi trường qua `application.yml`. Chỉ bắt buộc khi VNPay là cổng đang dùng
 * ({@code payment.provider=vnpay}) — {@code VnPayGateway} kiểm lúc khởi động.
 *
 * @param tmnCode    mã website đăng ký trên hệ thống VNPay
 * @param hashSecret chuỗi bí mật dùng ký và kiểm checksum
 * @param payUrl     endpoint cổng thanh toán
 * @param returnUrl  nơi VNPay đẩy trình duyệt khách về sau khi thanh toán
 * @param apiUrl     API merchant ({@code querydr}) — mặc định sandbox
 */
@ConfigurationProperties(prefix = "vnpay")
public record VnPayProperties(
        String tmnCode,
        String hashSecret,
        String payUrl,
        String returnUrl,
        @DefaultValue(VnPayProperties.SANDBOX_API_URL) String apiUrl) {

    public static final String SANDBOX_API_URL = "https://sandbox.vnpayment.vn/merchant_webapi/api/transaction";

    @ConstructorBinding
    public VnPayProperties {
    }

    /** Không khai {@code apiUrl} → dùng API sandbox. */
    public VnPayProperties(String tmnCode, String hashSecret, String payUrl, String returnUrl) {
        this(tmnCode, hashSecret, payUrl, returnUrl, SANDBOX_API_URL);
    }
}
