package vn.lemar.selfstorage.notification.application;

import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

/**
 * Gửi email qua SMTP (mặc định, {@code app.mail.provider=smtp}). Mọi exception khi gửi bị nuốt (chỉ log) để không
 * rollback nghiệp vụ của caller.
 *
 * <p>Bước deliver chạy trên executor riêng ({@code app.mail.async=true}, mặc định): SMTP thật (Gmail) mở kết nối TLS
 * mỗi email, 1–3 giây/email — gửi đồng bộ sau commit làm request (duyệt, phân công…) chờ theo. {@code false} (cấu hình
 * test) thì gửi đồng bộ trên thread gọi để test kiểm được nội dung mail ngay. Host chặn cổng SMTP (Render gói free)
 * thì dùng {@link BrevoEmailSender}.
 */
@Service
@ConditionalOnProperty(prefix = "app.mail", name = "provider", havingValue = "smtp", matchIfMissing = true)
class SmtpEmailSender extends DeferredEmailSender {

    private static final Logger LOG = LoggerFactory.getLogger(SmtpEmailSender.class);

    static final String DEFAULT_FROM = "noreply@lemar.vn";

    private final JavaMailSender mailSender;
    private final String from;

    @Autowired
    SmtpEmailSender(JavaMailSender mailSender,
                    @Value("${app.mail.from:}") String from,
                    @Value("${spring.mail.username:}") String smtpUsername,
                    @Value("${app.mail.async:true}") boolean async) {
        super(async);
        this.mailSender = mailSender;
        // Gmail SMTP chỉ cho gửi từ chính tài khoản đăng nhập: MAIL_FROM để trống thì dùng username;
        // cả hai trống (chưa cấu hình) thì giữ địa chỉ mặc định để message vẫn hợp lệ.
        this.from = firstNonBlank(DEFAULT_FROM, from, smtpUsername);
    }

    SmtpEmailSender(JavaMailSender mailSender, String from) {
        this(mailSender, from, "", false);
    }

    SmtpEmailSender(JavaMailSender mailSender, String from, String smtpUsername) {
        this(mailSender, from, smtpUsername, false);
    }

    @Override
    protected boolean deliver(String to, String subject, String content, boolean html) {
        try {
            if (html) {
                MimeMessage message = mailSender.createMimeMessage();
                MimeMessageHelper helper = new MimeMessageHelper(message, StandardCharsets.UTF_8.name());
                helper.setFrom(from);
                helper.setTo(to);
                helper.setSubject(subject);
                helper.setText(content, true);
                mailSender.send(message);
            } else {
                SimpleMailMessage message = new SimpleMailMessage();
                message.setFrom(from);
                message.setTo(to);
                message.setSubject(subject);
                message.setText(content);
                mailSender.send(message);
            }
            LOG.info("Đã gửi email: to={}, subject={}, html={}", to, subject, html);
            return true;
        } catch (Exception e) {
            LOG.error("Gửi email thất bại (to={}, subject={})", to, subject, e);
            return false;
        }
    }
}
