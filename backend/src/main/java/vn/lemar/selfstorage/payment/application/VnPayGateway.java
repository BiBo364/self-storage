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
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import vn.lemar.selfstorage.payment.application.dto.GatewayQueryResult;
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
@ConditionalOnProperty(prefix = "payment", name = "provider", havingValue = "vnpay", matchIfMissing = true)
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

    private static final String QUERY_COMMAND = "querydr";
    private static final Logger LOG = LoggerFactory.getLogger(VnPayGateway.class);

    private final VnPayProperties properties;
    private final RestClient restClient;
    private final SecureRandom random = new SecureRandom();

    @Autowired
    public VnPayGateway(VnPayProperties properties, ObjectProvider<RestClient.Builder> restClientBuilder) {
        requireConfigured(properties);
        this.properties = properties;
        this.restClient = restClientBuilder.getIfAvailable(RestClient::builder)
                .requestFactory(GatewayHttp.requestFactory()).build();
    }

    public VnPayGateway(VnPayProperties properties) {
        requireConfigured(properties);
        this.properties = properties;
        this.restClient = RestClient.builder().requestFactory(GatewayHttp.requestFactory()).build();
    }

    /** Fail-fast lúc khởi động khi VNPay là cổng đang dùng mà thiếu một trong bốn biến bắt buộc. */
    private static void requireConfigured(VnPayProperties p) {
        if (blank(p.tmnCode()) || blank(p.hashSecret()) || blank(p.payUrl()) || blank(p.returnUrl())) {
            throw new IllegalStateException("payment.provider=vnpay nhưng thiếu VNPAY_TMN_CODE / VNPAY_HASH_SECRET / "
                    + "VNPAY_PAY_URL / VNPAY_RETURN_URL");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    @Override
    public String buildPaymentUrl(PaymentRequest request) {
        LocalDateTime now = request.createdAt() == null
                ? LocalDateTime.now(VNPAY_ZONE)
                : LocalDateTime.ofInstant(request.createdAt(), VNPAY_ZONE);

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

    @Override
    public GatewayQueryResult query(String txnRef, Instant createdAt, String orderInfo, String ipAddr) {
        String requestId = Long.toString(Math.abs(random.nextLong()), 36);
        String createDate = LocalDateTime.now(VNPAY_ZONE).format(TIMESTAMP);
        String transactionDate = LocalDateTime.ofInstant(createdAt, VNPAY_ZONE).format(TIMESTAMP);
        String info = orderInfo == null || orderInfo.isBlank() ? "Truy van giao dich " + txnRef : orderInfo;
        String ip = ipAddr == null || ipAddr.isBlank() ? "127.0.0.1" : ipAddr;

        Map<String, String> body = new LinkedHashMap<>();
        body.put("vnp_RequestId", requestId);
        body.put("vnp_Version", VERSION);
        body.put("vnp_Command", QUERY_COMMAND);
        body.put("vnp_TmnCode", properties.tmnCode());
        body.put("vnp_TxnRef", txnRef);
        body.put("vnp_OrderInfo", info);
        body.put("vnp_TransactionDate", transactionDate);
        body.put("vnp_CreateDate", createDate);
        body.put("vnp_IpAddr", ip);
        // querydr ký chuỗi nối bằng '|' theo thứ tự cố định (khác lệnh pay).
        body.put(HASH_FIELD, sign(String.join("|", requestId, VERSION, QUERY_COMMAND, properties.tmnCode(),
                txnRef, transactionDate, createDate, ip, info)));

        Map<?, ?> raw = restClient.post().uri(properties.apiUrl())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Map.class);
        return toQueryResult(raw);
    }

    /** Đọc response querydr; tách riêng để test chữ ký không cần gọi mạng. */
    GatewayQueryResult toQueryResult(Map<?, ?> raw) {
        Map<String, String> response = new LinkedHashMap<>();
        if (raw != null) {
            raw.forEach((k, v) -> response.put(String.valueOf(k), v == null ? "" : String.valueOf(v)));
        }
        boolean signatureValid = verifyQuerySignature(response);
        if (!signatureValid) {
            LOG.warn("querydr {}: chữ ký response không hợp lệ (vnp_ResponseCode={})", response.get("vnp_TxnRef"),
                    response.get("vnp_ResponseCode"));
        }
        return new GatewayQueryResult(response.get("vnp_ResponseCode"), response.get("vnp_TransactionStatus"),
                response.get("vnp_TransactionNo"), parseAmount(response.get("vnp_Amount")),
                response.get("vnp_PayDate"), signatureValid, response);
    }

    /** Chuỗi ký của response querydr theo thứ tự cố định của VNPay. */
    String querySignatureData(Map<String, String> r) {
        return String.join("|", v(r, "vnp_ResponseId"), v(r, "vnp_Command"), v(r, "vnp_ResponseCode"),
                v(r, "vnp_Message"), v(r, "vnp_TmnCode"), v(r, "vnp_TxnRef"), v(r, "vnp_Amount"),
                v(r, "vnp_BankCode"), v(r, "vnp_PayDate"), v(r, "vnp_TransactionNo"),
                v(r, "vnp_TransactionType"), v(r, "vnp_TransactionStatus"), v(r, "vnp_OrderInfo"),
                v(r, "vnp_PromotionCode"), v(r, "vnp_PromotionAmount"));
    }

    /** Ký chuỗi bằng HashSecret của merchant (HMAC-SHA512, hex thường). */
    String signData(String data) {
        return sign(data);
    }

    /** Response lỗi (không có hash) coi là không hợp lệ. */
    private boolean verifyQuerySignature(Map<String, String> r) {
        String received = r.get(HASH_FIELD);
        if (received == null || received.isBlank()) {
            return false;
        }
        return constantTimeEquals(sign(querySignatureData(r)), received);
    }

    private static String v(Map<String, String> map, String key) {
        String value = map.get(key);
        return value == null ? "" : value;
    }

    private static BigDecimal parseAmount(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(raw).divide(AMOUNT_SCALE);
        } catch (NumberFormatException e) {
            return null;
        }
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
