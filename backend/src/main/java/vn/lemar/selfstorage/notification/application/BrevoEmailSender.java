package vn.lemar.selfstorage.notification.application;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Gửi email qua API giao dịch của Brevo ({@code POST /v3/smtp/email}, HTTPS cổng 443) khi
 * {@code app.mail.provider=brevo} — dùng cho host chặn cổng SMTP 25/465/587 (Render gói free).
 *
 * <p>Người gửi = {@code app.mail.from} (dạng {@code email} hoặc {@code Tên <email>}), phải là sender đã xác minh trên
 * Brevo; tên hiển thị mặc định {@code app.mail.from-name}. Thiếu {@code brevo.api-key} thì app dừng lúc khởi động.
 * Hành vi gửi sau commit / gửi nền / nuốt lỗi giống {@link SmtpEmailSender} (lớp cha {@link DeferredEmailSender}).
 */
@Service
@ConditionalOnProperty(prefix = "app.mail", name = "provider", havingValue = "brevo")
class BrevoEmailSender extends DeferredEmailSender {

    private static final Logger LOG = LoggerFactory.getLogger(BrevoEmailSender.class);

    static final String DEFAULT_API_URL = "https://api.brevo.com/v3/smtp/email";

    private final RestClient restClient;
    private final String apiUrl;
    private final String apiKey;
    private final String senderEmail;
    private final String senderName;

    @Autowired
    BrevoEmailSender(ObjectProvider<RestClient.Builder> builder,
                     @Value("${brevo.api-key:}") String apiKey,
                     @Value("${brevo.api-url:" + DEFAULT_API_URL + "}") String apiUrl,
                     @Value("${app.mail.from:}") String from,
                     @Value("${app.mail.from-name:Kho Mộc}") String fromName,
                     @Value("${app.mail.async:true}") boolean async) {
        this(withTimeouts(builder.getIfAvailable(RestClient::builder)), apiKey, apiUrl, from, fromName, async);
    }

    BrevoEmailSender(RestClient restClient, String apiKey, String apiUrl, String from, String fromName,
                     boolean async) {
        super(async);
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("app.mail.provider=brevo nhưng thiếu BREVO_API_KEY");
        }
        this.restClient = restClient;
        this.apiKey = apiKey.trim();
        this.apiUrl = firstNonBlank(DEFAULT_API_URL, apiUrl);
        String address = firstNonBlank(SmtpEmailSender.DEFAULT_FROM, from).trim();
        int open = address.indexOf('<');
        int close = address.lastIndexOf('>');
        if (open >= 0 && close > open) {
            this.senderEmail = address.substring(open + 1, close).trim();
            String named = address.substring(0, open).trim().replaceAll("^\"|\"$", "");
            this.senderName = firstNonBlank(fromName, named);
        } else {
            this.senderEmail = address;
            this.senderName = fromName;
        }
    }

    /** Brevo chậm / treo không được giữ luồng gửi mãi: nối 5 giây, đọc 15 giây. */
    private static RestClient withTimeouts(RestClient.Builder builder) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(15));
        return builder.requestFactory(factory).build();
    }

    @Override
    protected boolean deliver(String to, String subject, String content, boolean html) {
        Map<String, Object> sender = new LinkedHashMap<>();
        sender.put("email", senderEmail);
        if (senderName != null && !senderName.isBlank()) {
            sender.put("name", senderName);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sender", sender);
        body.put("to", List.of(Map.of("email", to)));
        body.put("subject", subject);
        body.put(html ? "htmlContent" : "textContent", content);
        try {
            restClient.post().uri(apiUrl)
                    .header("api-key", apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            LOG.info("Đã gửi email qua Brevo: to={}, subject={}, html={}", to, subject, html);
            return true;
        } catch (Exception e) {
            LOG.error("Gửi email qua Brevo thất bại (to={}, subject={}): {}", to, subject, e.getMessage());
            return false;
        }
    }
}
