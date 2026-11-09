package vn.lemar.selfstorage.payment.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import vn.lemar.selfstorage.payment.application.dto.GatewayNotification;
import vn.lemar.selfstorage.payment.application.dto.GatewayQueryResult;
import vn.lemar.selfstorage.payment.application.dto.PaymentRequest;
import vn.lemar.selfstorage.payment.config.PayOsProperties;

/**
 * Cổng payOS (chuyển khoản VietQR) — bật bằng {@code payment.provider=payos}; mặc định là VNPay.
 *
 * <ul>
 *   <li>Tạo link: {@code POST /v2/payment-requests}, ký HMAC-SHA256 chuỗi
 *       {@code amount=…&cancelUrl=…&description=…&orderCode=…&returnUrl=…}; link hết hạn sau
 *       {@link PaymentService#PAYMENT_WINDOW} ({@code expiredAt}).</li>
 *   <li>Tra cứu: {@code GET /v2/payment-requests/{orderCode}} → quy về {@link GatewayQueryResult} để dùng chung
 *       luồng đối soát ({@code PAID} = đã trả; {@code CANCELLED}/{@code EXPIRED}/{@code FAILED} = đóng không thu
 *       tiền; còn lại = đang chờ; không tìm thấy = {@code 91}).</li>
 *   <li>Huỷ: {@code POST /v2/payment-requests/{orderCode}/cancel}.</li>
 *   <li>Webhook: kiểm chữ ký trên object {@code data} (key sắp alphabet, {@code key=value} nối {@code &}).</li>
 * </ul>
 *
 * <p>payOS không có sandbox và không có API hoàn tiền — hoàn tiền luôn thủ công. {@code orderCode} phải là số,
 * nên mã giao dịch của cổng này là chuỗi số ({@link #newTxnRef}).
 */
@Component
@ConditionalOnProperty(prefix = "payment", name = "provider", havingValue = "payos")
public class PayOsGateway implements PaymentGateway {

    private static final Logger LOG = LoggerFactory.getLogger(PayOsGateway.class);
    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter PAYOS_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter VNPAY_STYLE = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final int DESCRIPTION_MAX = 25;
    private static final String OK = "00";

    private final PayOsProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SecureRandom random = new SecureRandom();

    @Autowired
    public PayOsGateway(PayOsProperties properties, ObjectProvider<RestClient.Builder> restClientBuilder) {
        this(properties, restClientBuilder.getIfAvailable(RestClient::builder)
                .requestFactory(GatewayHttp.requestFactory()));
    }

    PayOsGateway(PayOsProperties properties, RestClient.Builder builder) {
        requireConfigured(properties);
        this.properties = properties;
        this.restClient = builder.baseUrl(properties.baseUrl())
                .defaultHeader("x-client-id", properties.clientId())
                .defaultHeader("x-api-key", properties.apiKey())
                .build();
    }

    /** Fail-fast lúc khởi động khi payOS là cổng đang dùng mà thiếu key. */
    private static void requireConfigured(PayOsProperties p) {
        if (blank(p.clientId()) || blank(p.apiKey()) || blank(p.checksumKey())) {
            throw new IllegalStateException(
                    "payment.provider=payos nhưng thiếu PAYOS_CLIENT_ID / PAYOS_API_KEY / PAYOS_CHECKSUM_KEY");
        }
    }

    /** {@code orderCode} của payOS là số nguyên dương ≤ 2^53: mili-giây hiện tại × 100 + 2 chữ số ngẫu nhiên. */
    @Override
    public String newTxnRef(Long invoiceId) {
        return Long.toString(System.currentTimeMillis() * 100 + random.nextInt(100));
    }

    @Override
    public String buildPaymentUrl(PaymentRequest request) {
        long orderCode = Long.parseLong(request.txnRef());
        long amount = request.amount().setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
        String description = description(request.orderInfo());
        Instant created = request.createdAt() == null ? Instant.now() : request.createdAt();
        long expiredAt = created.plus(PaymentService.PAYMENT_WINDOW).getEpochSecond();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orderCode", orderCode);
        body.put("amount", amount);
        body.put("description", description);
        body.put("returnUrl", properties.returnUrl());
        body.put("cancelUrl", properties.cancelUrl());
        body.put("expiredAt", expiredAt);
        body.put("signature", sign("amount=" + amount + "&cancelUrl=" + properties.cancelUrl()
                + "&description=" + description + "&orderCode=" + orderCode + "&returnUrl=" + properties.returnUrl()));

        JsonNode response = restClient.post().uri("/v2/payment-requests")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class);
        if (response == null || !OK.equals(response.path("code").asText())) {
            throw new IllegalStateException("payOS từ chối tạo link: "
                    + (response == null ? "không có phản hồi" : response.path("desc").asText()));
        }
        return response.path("data").path("checkoutUrl").asText();
    }

    /** Webhook payOS: chữ ký nằm trong body, không theo dạng tham số query như VNPay. */
    @Override
    public boolean verifySignature(Map<String, String> params) {
        String received = params.get("signature");
        if (received == null || received.isBlank()) {
            return false;
        }
        Map<String, String> data = new TreeMap<>(params);
        data.remove("signature");
        return constantTimeEquals(sign(joinSorted(data)), received);
    }

    @Override
    public Optional<GatewayNotification> parseNotification(String rawBody) {
        try {
            JsonNode root = objectMapper.readTree(rawBody);
            JsonNode data = root.path("data");
            String received = root.path("signature").asText("");
            if (!data.isObject() || received.isBlank() || !constantTimeEquals(sign(dataSignatureString(data)),
                    received)) {
                return Optional.empty();
            }
            boolean success = OK.equals(root.path("code").asText()) && OK.equals(data.path("code").asText(OK));
            return Optional.of(new GatewayNotification(data.path("orderCode").asText(),
                    new BigDecimal(data.path("amount").asText("0")), data.path("reference").asText(null),
                    parseTime(data.path("transactionDateTime").asText(null)), success, rawBody));
        } catch (java.io.IOException | NumberFormatException e) {
            LOG.warn("Webhook payOS không đọc được: {}", e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public GatewayQueryResult query(String txnRef, Instant createdAt, String orderInfo, String ipAddr) {
        JsonNode response;
        try {
            response = restClient.get().uri("/v2/payment-requests/{id}", txnRef).retrieve().body(JsonNode.class);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                return notFound();
            }
            throw e;
        }
        if (response == null || !OK.equals(response.path("code").asText())) {
            return notFound();
        }
        JsonNode data = response.path("data");
        String status = data.path("status").asText("");
        String txnStatus = switch (status) {
            case "PAID" -> OK;
            case "CANCELLED", "EXPIRED", "FAILED" -> "02";
            default -> "01";
        };
        JsonNode firstTxn = data.path("transactions").isArray() && data.path("transactions").size() > 0
                ? data.path("transactions").get(0) : null;
        String transactionNo = firstTxn == null ? data.path("id").asText(null)
                : firstTxn.path("reference").asText(data.path("id").asText(null));
        String paidRaw = firstTxn == null ? null : firstTxn.path("transactionDateTime").asText(null);
        Instant paidAt = parseTime(paidRaw);
        BigDecimal amount = "PAID".equals(status) ? new BigDecimal(data.path("amountPaid").asText("0"))
                : new BigDecimal(data.path("amount").asText("0"));
        Map<String, String> payload = new HashMap<>();
        payload.put("status", status);
        payload.put("orderCode", data.path("orderCode").asText());
        payload.put("id", data.path("id").asText());
        // Kênh server-to-server có x-api-key qua HTTPS — tin kết quả khi API trả code 00.
        return new GatewayQueryResult(OK, txnStatus, transactionNo, amount,
                paidAt == null ? null : LocalDateTime.ofInstant(paidAt, VN).format(VNPAY_STYLE), true, payload);
    }

    @Override
    public boolean cancel(String txnRef, String reason) {
        try {
            JsonNode response = restClient.post().uri("/v2/payment-requests/{id}/cancel", txnRef)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("cancellationReason", reason == null ? "Huy thanh toan" : reason))
                    .retrieve()
                    .body(JsonNode.class);
            boolean ok = response != null && OK.equals(response.path("code").asText())
                    && "CANCELLED".equals(response.path("data").path("status").asText());
            if (!ok) {
                LOG.warn("payOS không huỷ được link {}: {}", txnRef, response == null ? null : response.path("desc"));
            }
            return ok;
        } catch (RuntimeException e) {
            LOG.warn("Gọi huỷ link payOS {} lỗi: {}", txnRef, e.getMessage());
            return false;
        }
    }

    private static GatewayQueryResult notFound() {
        return new GatewayQueryResult("91", null, null, null, null, true, Map.of());
    }

    /** Nội dung chuyển khoản: chỉ ASCII, tối đa 25 ký tự (giới hạn của payOS với tài khoản ngân hàng thường). */
    static String description(String orderInfo) {
        String ascii = java.text.Normalizer.normalize(orderInfo == null ? "" : orderInfo,
                java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "").replace('đ', 'd').replace('Đ', 'D')
                .replaceAll("[^A-Za-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
        if (ascii.isEmpty()) {
            ascii = "Thanh toan";
        }
        return ascii.length() > DESCRIPTION_MAX ? ascii.substring(0, DESCRIPTION_MAX).trim() : ascii;
    }

    /** Chuỗi ký của {@code data} webhook: key sắp alphabet, {@code null} → rỗng, object/mảng → JSON. */
    String dataSignatureString(JsonNode data) {
        Map<String, String> sorted = new TreeMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = data.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            JsonNode value = field.getValue();
            String text;
            if (value == null || value.isNull() || value.isMissingNode()) {
                text = "";
            } else if (value.isValueNode()) {
                text = value.asText();
            } else {
                text = value.toString();
            }
            sorted.put(field.getKey(), text);
        }
        return joinSorted(sorted);
    }

    private static String joinSorted(Map<String, String> sorted) {
        List<String> parts = new ArrayList<>();
        new TreeMap<>(sorted).forEach((k, v) -> parts.add(k + "=" + (v == null || "null".equals(v) ? "" : v)));
        return String.join("&", parts);
    }

    String sign(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.checksumKey().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Không ký được dữ liệu payOS", e);
        }
    }

    private static boolean constantTimeEquals(String expected, String received) {
        return MessageDigest.isEqual(expected.toLowerCase().getBytes(StandardCharsets.US_ASCII),
                received.toLowerCase().getBytes(StandardCharsets.US_ASCII));
    }

    private static Instant parseTime(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            // Webhook gửi "yyyy-MM-dd HH:mm:ss" (giờ VN); API tra cứu trả ISO-8601 có offset.
            return raw.contains("T") ? java.time.OffsetDateTime.parse(raw).toInstant()
                    : LocalDateTime.parse(raw, PAYOS_TIME).atZone(VN).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
