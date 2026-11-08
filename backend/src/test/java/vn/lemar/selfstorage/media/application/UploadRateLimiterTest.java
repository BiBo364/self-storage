package vn.lemar.selfstorage.media.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import vn.lemar.selfstorage.media.application.exception.MediaRateLimitedException;

@ExtendWith(MockitoExtension.class)
class UploadRateLimiterTest {

    private static final String KEY = "media:upload:acc:7";
    private static final Duration WINDOW = Duration.ofMinutes(10);

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> valueOps;

    private UploadRateLimiter limiter;

    @BeforeEach
    void setUp() {
        limiter = new UploadRateLimiter(redis, 3, WINDOW);
    }

    @Test
    void rejectsNonPositiveLimitAndWindow() {
        assertThatThrownBy(() -> new UploadRateLimiter(redis, 0, WINDOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UploadRateLimiter(redis, 3, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void firstUploadStartsTheWindow() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(KEY)).thenReturn(1L);

        assertThatCode(() -> limiter.acquire(7)).doesNotThrowAnyException();
        verify(redis).expire(KEY, WINDOW);
    }

    @Test
    void allowsUpToTheLimitWithoutResettingTtl() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(KEY)).thenReturn(3L);

        assertThatCode(() -> limiter.acquire(7)).doesNotThrowAnyException();
        verify(redis, never()).expire(anyString(), any(Duration.class));
    }

    @Test
    void blocksAboveTheLimit() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(KEY)).thenReturn(4L);

        assertThatThrownBy(() -> limiter.acquire(7)).isInstanceOf(MediaRateLimitedException.class)
                .hasMessageContaining("tối đa 3 file / 10 phút");
    }

    @Test
    void failsOpenWhenRedisIsDown() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(KEY)).thenThrow(new QueryTimeoutException("down"));

        assertThatCode(() -> limiter.acquire(7)).doesNotThrowAnyException();
    }
}
