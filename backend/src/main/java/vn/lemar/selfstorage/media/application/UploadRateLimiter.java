package vn.lemar.selfstorage.media.application;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import vn.lemar.selfstorage.media.application.exception.MediaRateLimitedException;

/**
 * Giới hạn số lần upload mỗi account: tối đa {@code media.upload-rate-limit.max-per-window} lần /
 * {@code media.upload-rate-limit.window} (fixed window, key {@code media:upload:acc:<id>}, TTL đặt ở lần tăng đầu).
 * Vượt ngưỡng → 429. Lần bị chặn vẫn được đếm.
 *
 * <p>Fail-open: Redis lỗi thì chỉ log và cho upload qua — giữ luồng nghiệp vụ chạy, tạm mất lớp chống spam.
 */
@Service
public class UploadRateLimiter {

    private static final Logger LOG = LoggerFactory.getLogger(UploadRateLimiter.class);
    static final String KEY_PREFIX = "media:upload:acc:";

    private final StringRedisTemplate redis;
    private final int maxPerWindow;
    private final Duration window;

    public UploadRateLimiter(StringRedisTemplate redis,
                             @Value("${media.upload-rate-limit.max-per-window:30}") int maxPerWindow,
                             @Value("${media.upload-rate-limit.window:PT10M}") Duration window) {
        if (maxPerWindow < 1) {
            throw new IllegalArgumentException("Upload rate limit must be positive");
        }
        if (window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("Upload rate limit window must be positive");
        }
        this.redis = redis;
        this.maxPerWindow = maxPerWindow;
        this.window = window;
    }

    /** Gọi trước mỗi lần upload; vượt ngưỡng → {@link MediaRateLimitedException}. */
    public void acquire(long accountId) {
        String key = KEY_PREFIX + accountId;
        Long count;
        try {
            count = redis.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redis.expire(key, window);
            }
        } catch (DataAccessException e) {
            LOG.error("Redis lỗi khi kiểm rate limit upload — tạm cho qua", e);
            return;
        }
        if (count != null && count > maxPerWindow) {
            throw new MediaRateLimitedException("Bạn tải lên quá nhiều file trong thời gian ngắn (tối đa "
                    + maxPerWindow + " file / " + window.toMinutes() + " phút). Vui lòng thử lại sau.");
        }
    }
}
