package vn.lemar.selfstorage.notification.application;

import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vn.lemar.selfstorage.notification.EmailSender;

/**
 * Cài đặt {@link EmailSender} gửi qua SMTP. Mọi exception khi gửi bị nuốt (chỉ log) để không
 * rollback nghiệp vụ của caller. Bước deliver chạy đồng bộ trên thread gọi, kể cả nhánh
 * afterCommit — xem "Known limitation" trong {@link EmailSender}.
 */
@Service
class SmtpEmailSender implements EmailSender {

    private static final Logger LOG = LoggerFactory.getLogger(SmtpEmailSender.class);

    static final String DEFAULT_FROM = "noreply@lemar.vn";

    private final JavaMailSender mailSender;
    private final String from;

    @Autowired
    SmtpEmailSender(JavaMailSender mailSender,
                    @Value("${app.mail.from:}") String from,
                    @Value("${spring.mail.username:}") String smtpUsername) {
        this.mailSender = mailSender;
        // Gmail SMTP chỉ cho gửi từ chính tài khoản đăng nhập: MAIL_FROM để trống thì dùng username;
        // cả hai trống (chưa cấu hình) thì giữ địa chỉ mặc định để message vẫn hợp lệ.
        this.from = firstNonBlank(from, smtpUsername, DEFAULT_FROM);
    }

    SmtpEmailSender(JavaMailSender mailSender, String from) {
        this(mailSender, from, "");
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return DEFAULT_FROM;
    }

    @Override
    public boolean send(String to, String subject, String body) {
        return send(to, subject, body, false);
    }

    @Override
    public boolean sendHtml(String to, String subject, String htmlBody) {
        return send(to, subject, htmlBody, true);
    }

    private boolean send(String to, String subject, String content, boolean html) {
        if (to == null || to.isBlank()) {
            LOG.warn("Bỏ qua gửi email: thiếu địa chỉ người nhận (subject={})", subject);
            return false;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            LOG.debug("Xếp lịch gửi email sau commit: to={}, subject={}, html={}", to, subject, html);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    deliver(to, subject, content, html);
                }
            });
            return true;
        }
        return deliver(to, subject, content, html);
    }

    private boolean deliver(String to, String subject, String content, boolean html) {
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
