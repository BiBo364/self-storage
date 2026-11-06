package vn.lemar.selfstorage.payment.application;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.TreeMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;
import vn.lemar.selfstorage.payment.application.dto.PaymentRequest;
import vn.lemar.selfstorage.payment.config.VnPayProperties;

/**
 * Cài đặt {@link PaymentGateway} cho VNPay.
 *
 * <p>Ba quy định của VNPay được xử lý ở đây, sai một cái là cổng từ chối:
 * số tiền phải nhân 100, mốc thời gian theo múi giờ GMT+7 dạng {@code yyyyMMddHHmmss},
 * và chữ ký HMAC-SHA512 tính trên chuỗi tham số đã sắp xếp theo thứ tự alphabet.
 */
@Component
public class VnPayGateway implements PaymentGateway {

    private static final String VERSION = "2.1.0";
    private static final String COMMAND = "pay";
    private static final String CURRENCY = "VND";
    private static final String LOCALE = "vn";
    private static final String ORDER_TYPE = "other";
    private static final String HASH_FIELD = "vnp_SecureHash";
    private static final String HASH_TYPE_FIELD = "vnp_SecureHashType";

    /** VNPay nhận số tiền không có phần thập phân nên quy ước nhân 100 trước khi gửi. */
    private static final BigDecimal AMOUNT_SCALE = BigDecimal.valueOf(100);

    private static final ZoneId VNPAY_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final Duration PAYMENT_WINDOW = PaymentSession.WINDOW;

    private final VnPayProperties properties;

    public VnPayGateway(VnPayProperties properties) {
        this.properties = properties;
    }

    @Override
    public String buildPaymentUrl(PaymentRequest request) {
        return buildPaymentUrl(request, Instant.now());
    }

    @Override
    public String buildPaymentUrl(PaymentRequest request, Instant createdAt) {
        LocalDateTime now = LocalDateTime.ofInstant(createdAt, VNPAY_ZONE);

        Map<String, String> params = new TreeMap<>();
        params.put("vnp_Version", VERSION);
        params.put("vnp_Command", COMMAND);
        params.put("vnp_TmnCode", properties.tmnCode());
        params.put("vnp_Amount", request.amount().multiply(AMOUNT_SCALE).toBigInteger().toString());
        params.put("vnp_CurrCode", CURRENCY);
        params.put("vnp_TxnRef", request.txnRef());
        params.put("vnp_OrderInfo", asciiFold(request.orderInfo()));
        params.put("vnp_OrderType", ORDER_TYPE);
        params.put("vnp_Locale", LOCALE);
        params.put("vnp_ReturnUrl", properties.returnUrl());
        params.put("vnp_IpAddr", request.ipAddr());
        params.put("vnp_CreateDate", now.format(TIMESTAMP));
        params.put("vnp_ExpireDate", now.plus(PAYMENT_WINDOW).format(TIMESTAMP));

        String query = toSignedQuery(params);
        return properties.payUrl() + "?" + query + "&" + HASH_FIELD + "=" + sign(query);
    }

    @Override
    public boolean verifySignature(Map<String, String> params) {
        String received = params.get(HASH_FIELD);
        if (received == null || received.isBlank()) {
            return false;
        }
        Map<String, String> signed = new TreeMap<>(params);
        signed.remove(HASH_FIELD);
        signed.remove(HASH_TYPE_FIELD);

        return constantTimeEquals(sign(toSignedQuery(signed)), received);
    }

    /**
     * VNPay chỉ hiển thị được ASCII: chuỗi có dấu bị encode US-ASCII thành {@code ?} và khách thấy
     * "??t c?c khoang ch?a A-01". Bỏ dấu trước khi ký để chuỗi ký và chuỗi hiển thị khớp nhau.
     */
    private static String asciiFold(String value) {
        if (value == null) {
            return null;
        }
        String folded = Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return folded.replace('đ', 'd').replace('Đ', 'D');
    }

    /** Ghép tham số đã sắp xếp thành chuỗi url-encoded — đây chính là dữ liệu đem ký. */
    private String toSignedQuery(Map<String, String> params) {
        StringBuilder data = new StringBuilder();
        new TreeMap<>(params).forEach((key, value) -> {
            if (value == null || value.isEmpty()) {
                return;
            }
            if (data.length() > 0) {
                data.append('&');
            }
            data.append(key).append('=').append(URLEncoder.encode(value, StandardCharsets.US_ASCII));
        });
        return data.toString();
    }

    private String sign(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(
                    properties.hashSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            byte[] digest = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));

            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("Không ký được dữ liệu VNPay", e);
        }
    }

    /** So sánh không phụ thuộc vị trí byte lệch, tránh lộ thông tin qua thời gian phản hồi. */
    private boolean constantTimeEquals(String expected, String received) {
        return java.security.MessageDigest.isEqual(
                expected.toLowerCase().getBytes(StandardCharsets.US_ASCII),
                received.toLowerCase().getBytes(StandardCharsets.US_ASCII));
    }
}
