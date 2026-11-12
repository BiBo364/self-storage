package vn.lemar.selfstorage.notification.application;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vn.lemar.selfstorage.notification.EmailSender;

/**
 * Phần chung của mọi kênh gửi email: bỏ qua người nhận trống, đang trong transaction thì xếp lịch gửi <b>sau commit</b>
 * (rollback thì không gửi), rồi gửi nền trên executor riêng ({@code app.mail.async=true}) hoặc đồng bộ (test).
 * Lớp con chỉ cài {@link #deliver} và tự nuốt lỗi (log, trả {@code false}) để không rollback nghiệp vụ của caller.
 */
abstract class DeferredEmailSender implements EmailSender, DisposableBean {

    private static final Logger LOG = LoggerFactory.getLogger(DeferredEmailSender.class);

    /** {@code null} = gửi đồng bộ trên thread gọi. */
    private final ExecutorService executor;

    protected DeferredEmailSender(boolean async) {
        // 2 luồng, hàng đợi có giới hạn: đầy thì luồng gọi tự gửi (chậm lại nhưng không mất mail).
        this.executor = async ? new ThreadPoolExecutor(2, 2, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>(500),
                runnable -> {
                    Thread thread = new Thread(runnable, "mail-delivery");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.CallerRunsPolicy()) : null;
    }

    /** Gửi thật một email; lỗi phải tự bắt, log và trả {@code false}. */
    protected abstract boolean deliver(String to, String subject, String content, boolean html);

    /** Tắt app: đợi email đang xếp hàng gửi xong (tối đa 20 giây) rồi mới dừng. */
    @Override
    public void destroy() throws InterruptedException {
        if (executor != null) {
            executor.shutdown();
            if (!executor.awaitTermination(20, TimeUnit.SECONDS)) {
                LOG.warn("Còn email chưa gửi xong khi tắt ứng dụng");
            }
        }
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

    static String firstNonBlank(String fallback, String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return fallback;
    }
}
