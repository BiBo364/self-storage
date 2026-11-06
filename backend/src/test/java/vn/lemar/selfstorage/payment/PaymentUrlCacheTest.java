package vn.lemar.selfstorage.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import vn.lemar.selfstorage.payment.application.PaymentUrlCache;

class PaymentUrlCacheTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);

    private PaymentUrlCache cache;

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(values);
        cache = new PaymentUrlCache(redis);
    }

    @Test
    void blankOrWhitespaceValueIsTreatedAsMissing() {
        when(values.get("payment:url:1")).thenReturn("", "   ");

        assertThat(cache.get(1L)).isEmpty();
        assertThat(cache.get(1L)).isEmpty();
    }

    @Test
    void storedUrlIsReturned() {
        when(values.get("payment:url:1")).thenReturn("https://pay/session");

        assertThat(cache.get(1L)).contains("https://pay/session");
    }

    @Test
    void putUsesTheGivenTtl() {
        cache.put(1L, "https://pay", Duration.ofMinutes(5));

        verify(values).set("payment:url:1", "https://pay", Duration.ofMinutes(5));
    }

    @Test
    void zeroTtlIsNotCached() {
        cache.put(1L, "https://pay", Duration.ZERO);

        verifyNoInteractions(values);
    }

    @Test
    void redisFailureDoesNotEscape() {
        when(values.get("payment:url:1")).thenThrow(new RuntimeException("redis down"));
        doThrow(new RuntimeException("redis down")).when(values).set(anyString(), anyString(), any(Duration.class));

        assertThat(cache.get(1L)).isEmpty();
        cache.put(1L, "https://pay");
    }

    @Test
    void emptyPutDoesNotWrite() {
        cache.put(1L, "", Duration.ofMinutes(5));

        verify(values, never()).set(anyString(), anyString(), any(Duration.class));
    }
}
