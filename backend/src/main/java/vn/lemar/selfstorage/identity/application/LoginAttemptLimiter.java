package vn.lemar.selfstorage.identity.application;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import vn.lemar.selfstorage.identity.application.exception.TooManyLoginAttemptsException;

/**
 * Đếm số lần đăng nhập sai trên Redis: tối đa
 * {@code security.login-rate-limit.email-max-attempts} lần / {@code window} theo email và
 * {@code security.login-rate-limit.ip-max-attempts} lần / {@code window} theo IP. Vượt ngưỡng
 * → 429. Đăng nhập thành công reset bộ đếm của email (bộ đếm IP không reset).
 *
 * <p>Key: {@code auth:login-fail:email:<email>} và {@code auth:login-fail:ip:<ip>}; TTL =
 * window, đặt ở lần tăng đầu tiên (fixed window). Không dùng sliding window — đủ cho MVP.
 *
 * <p>Fail-open: Redis lỗi thì chỉ log và cho qua, giữ đăng nhập hoạt động — chấp nhận tạm mất
 * lớp chống brute-force (xem authentication.md › Chống brute-force đăng nhập).
 */
@Service
public class LoginAttemptLimiter {

    private static final Logger LOG = LoggerFactory.getLogger(LoginAttemptLimiter.class);
    private static final String EMAIL_KEY_PREFIX = "auth:login-fail:email:";
    private static final String IP_KEY_PREFIX = "auth:login-fail:ip:";

    private final StringRedisTemplate redis;
    private final int emailMaxAttempts;
    private final int ipMaxAttempts;
    private final Duration window;

    public LoginAttemptLimiter(
            StringRedisTemplate redis,
            @Value("${security.login-rate-limit.email-max-attempts:5}") int emailMaxAttempts,
            @Value("${security.login-rate-limit.ip-max-attempts:20}") int ipMaxAttempts,
            @Value("${security.login-rate-limit.window:PT15M}") Duration window) {
        if (emailMaxAttempts < 1 || ipMaxAttempts < 1) {
            throw new IllegalArgumentException("Login rate limit max attempts must be positive");
        }
        if (window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("Login rate limit window must be positive");
        }
        this.redis = redis;
        this.emailMaxAttempts = emailMaxAttempts;
        this.ipMaxAttempts = ipMaxAttempts;
        this.window = window;
    }

    /** Gọi trước khi kiểm mật khẩu; vượt ngưỡng → 429. */
    public void checkOrThrow(String normalizedEmail, String clientIp) {
        try {
            if (count(EMAIL_KEY_PREFIX + normalizedEmail) >= emailMaxAttempts
                    || count(IP_KEY_PREFIX + clientIp) >= ipMaxAttempts) {
                throw new TooManyLoginAttemptsException();
            }
        } catch (DataAccessException e) {
            LOG.error("Redis lỗi khi kiểm rate limit đăng nhập — tạm bỏ qua chặn brute-force", e);
        }
    }

    /** Ghi nhận một lần đăng nhập sai (sai email hoặc sai mật khẩu). */
    public void recordFailure(String normalizedEmail, String clientIp) {
        try {
            increment(EMAIL_KEY_PREFIX + normalizedEmail);
            increment(IP_KEY_PREFIX + clientIp);
        } catch (DataAccessException e) {
            LOG.error("Redis lỗi khi ghi nhận đăng nhập sai — bộ đếm không tăng", e);
        }
    }

    /** Đăng nhập thành công — xoá bộ đếm sai của email. */
    public void reset(String normalizedEmail) {
        try {
            redis.delete(EMAIL_KEY_PREFIX + normalizedEmail);
        } catch (DataAccessException e) {
            LOG.error("Redis lỗi khi reset bộ đếm đăng nhập sai của email {}", normalizedEmail, e);
        }
    }

    private long count(String key) {
        String value = redis.opsForValue().get(key);
        if (value == null) {
            return 0;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void increment(String key) {
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redis.expire(key, window);
        }
    }
}
