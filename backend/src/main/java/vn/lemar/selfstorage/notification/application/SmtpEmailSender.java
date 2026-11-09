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
 * rollback nghiệp vụ của caller.
 *
 * <p>Bước deliver chạy trên executor riêng ({@code app.mail.async=true}, mặc định): SMTP thật (Gmail) mở kết nối TLS
 * mỗi email, 1–3 giây/email — gửi đồng bộ sau commit làm request (duyệt, phân công…) chờ theo. {@code false} (cấu hình
 * test) thì gửi đồng bộ trên thread gọi để test kiểm được nội dung mail ngay.
 */
@Service
class SmtpEmailSender implements EmailSender, org.springframework.beans.factory.DisposableBean {

    private static final Logger LOG = LoggerFactory.getLogger(SmtpEmailSender.class);

    static final String DEFAULT_FROM = "noreply@lemar.vn";

    private final JavaMailSender mailSender;
    private final String from;
    /** {@code null} = gửi đồng bộ trên thread gọi. */
    private final java.util.concurrent.ExecutorService executor;

    @Autowired
    SmtpEmailSender(JavaMailSender mailSender,
                    @Value("${app.mail.from:}") String from,
                    @Value("${spring.mail.username:}") String smtpUsername,
                    @Value("${app.mail.async:true}") boolean async) {
        this.mailSender = mailSender;
        // Gmail SMTP chỉ cho gửi từ chính tài khoản đăng nhập: MAIL_FROM để trống thì dùng username;
        // cả hai trống (chưa cấu hình) thì giữ địa chỉ mặc định để message vẫn hợp lệ.
        this.from = firstNonBlank(from, smtpUsername, DEFAULT_FROM);
        // 2 luồng, hàng đợi có giới hạn: đầy thì luồng gọi tự gửi (chậm lại nhưng không mất mail).
        this.executor = async ? new java.util.concurrent.ThreadPoolExecutor(2, 2, 60,
                java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.LinkedBlockingQueue<>(500),
                runnable -> {
                    Thread thread = new Thread(runnable, "mail-delivery");
                    thread.setDaemon(true);
                    return thread;
                }, new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy()) : null;
    }

    SmtpEmailSender(JavaMailSender mailSender, String from) {
        this(mailSender, from, "", false);
    }

    SmtpEmailSender(JavaMailSender mailSender, String from, String smtpUsername) {
        this(mailSender, from, smtpUsername, false);
    }

    /** Tắt app: đợi email đang xếp hàng gửi xong (tối đa 20 giây) rồi mới dừng. */
    @Override
    public void destroy() throws InterruptedException {
        if (executor != null) {
            executor.shutdown();
            if (!executor.awaitTermination(20, java.util.concurrent.TimeUnit.SECONDS)) {
                LOG.warn("Còn email chưa gửi xong khi tắt ứng dụng");
            }
        }
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
                    dispatch(to, subject, content, html);
                }
            });
            return true;
        }
        return dispatch(to, subject, content, html);
    }

    /** Gửi nền nếu bật executor (trả {@code true} = đã xếp hàng), không thì gửi ngay. */
    private boolean dispatch(String to, String subject, String content, boolean html) {
        if (executor == null) {
            return deliver(to, subject, content, html);
        }
        executor.execute(() -> deliver(to, subject, content, html));
        return true;
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
