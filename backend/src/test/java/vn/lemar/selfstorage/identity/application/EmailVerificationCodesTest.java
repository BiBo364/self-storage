package vn.lemar.selfstorage.identity.application;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.password.PasswordEncoder;
import vn.lemar.selfstorage.identity.application.exception.InvalidVerificationCodeException;
import vn.lemar.selfstorage.identity.application.exception.TooManyVerificationAttemptsException;
import vn.lemar.selfstorage.identity.application.exception.VerificationResendTooEarlyException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmailVerificationCodesTest {

    private static final String EMAIL = "customer@example.com";
    private static final String CODE_KEY = "auth:verify-code:" + EMAIL;
    private static final String COOLDOWN_KEY = "auth:verify-cooldown:" + EMAIL;
    private static final Duration TTL = Duration.ofMinutes(10);
    private static final Duration COOLDOWN = Duration.ofSeconds(60);

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private PasswordEncoder passwordEncoder;

    private HashOperations<String, Object, Object> hashOps;
    private ValueOperations<String, String> valueOps;
    private EmailVerificationCodes codes;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        hashOps = mock(HashOperations.class);
        valueOps = mock(ValueOperations.class);
        codes = new EmailVerificationCodes(redis, passwordEncoder, TTL, 5, COOLDOWN);
    }

    @Test
    void rejectsNonPositiveTtlAndAttempts() {
        assertThatThrownBy(() -> new EmailVerificationCodes(redis, passwordEncoder,
                Duration.ZERO, 5, COOLDOWN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EmailVerificationCodes(redis, passwordEncoder,
                TTL, 0, COOLDOWN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EmailVerificationCodes(redis, passwordEncoder,
                TTL, 5, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void issueStoresHashedCodeAndPendingPasswordHashWithTtl() {
        when(redis.opsForHash()).thenReturn(hashOps);
        when(passwordEncoder.encode(anyString())).thenReturn("hashed");

        String code = codes.issue(EMAIL, "password-hash");

        assertThat(code).matches("\\d{6}");
        verify(passwordEncoder).encode(code);
        verify(hashOps).put(CODE_KEY, "hash", "hashed");
        verify(hashOps).put(CODE_KEY, "attempts", "0");
        verify(hashOps).put(CODE_KEY, "passwordHash", "password-hash");
        verify(redis).expire(CODE_KEY, TTL);
    }

    @Test
    void reissueKeepsStoredPendingPasswordHash() {
        when(redis.opsForHash()).thenReturn(hashOps);
        when(hashOps.get(CODE_KEY, "passwordHash")).thenReturn("password-hash");
        when(passwordEncoder.encode(anyString())).thenReturn("hashed");

        String code = codes.reissue(EMAIL);

        assertThat(code).matches("\\d{6}");
        verify(hashOps).put(CODE_KEY, "passwordHash", "password-hash");
        verify(redis).expire(CODE_KEY, TTL);
    }

    @Test
    void reissueWithoutStoredPasswordHashSkipsTheField() {
        when(redis.opsForHash()).thenReturn(hashOps);
        when(hashOps.get(CODE_KEY, "passwordHash")).thenReturn(null);
        when(passwordEncoder.encode(anyString())).thenReturn("hashed");

        codes.reissue(EMAIL);

        verify(hashOps, never()).put(CODE_KEY, "passwordHash", "password-hash");
        verify(hashOps).put(CODE_KEY, "hash", "hashed");
    }

    @Test
    void hasPendingReflectsRedisKey() {
        when(redis.hasKey(CODE_KEY)).thenReturn(true);
        assertThat(codes.hasPending(EMAIL)).isTrue();

        when(redis.hasKey(CODE_KEY)).thenReturn(false);
        assertThat(codes.hasPending(EMAIL)).isFalse();
    }

    @Test
    void cooldownAllowsFirstResendAndRejectsTheNext() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(COOLDOWN_KEY, "1", COOLDOWN)).thenReturn(true);
        assertThatCode(() -> codes.markResendCooldown(EMAIL)).doesNotThrowAnyException();

        when(valueOps.setIfAbsent(COOLDOWN_KEY, "1", COOLDOWN)).thenReturn(false);
        assertThatThrownBy(() -> codes.markResendCooldown(EMAIL))
                .isInstanceOf(VerificationResendTooEarlyException.class);
    }

    @Test
    void verifyReturnsPendingRegistrationWithoutConsumingCode() {
        when(redis.opsForHash()).thenReturn(hashOps);
        when(hashOps.entries(CODE_KEY)).thenReturn(
                Map.of("hash", "hashed", "attempts", "0", "passwordHash", "password-hash"));
        when(passwordEncoder.matches("123456", "hashed")).thenReturn(true);

        Optional<PendingRegistration> pending = codes.verify(EMAIL, "123456");

        assertThat(pending).contains(new PendingRegistration(EMAIL, "password-hash"));
        verify(redis, never()).delete(CODE_KEY);
    }

    @Test
    void verifyReturnsPendingRegistrationWithProfile() {
        when(redis.opsForHash()).thenReturn(hashOps);
        when(hashOps.entries(CODE_KEY)).thenReturn(Map.of("hash", "hashed", "attempts", "0",
                "passwordHash", "password-hash", "name", "Nguyễn Văn A", "phone", "0900000000"));
        when(passwordEncoder.matches("123456", "hashed")).thenReturn(true);

        Optional<PendingRegistration> pending = codes.verify(EMAIL, "123456");

        assertThat(pending).contains(new PendingRegistration(EMAIL, "password-hash", "Nguyễn Văn A",
                "0900000000"));
    }

    @Test
    void verifyReturnsEmptyForLegacyCodeWithoutPendingData() {
        when(redis.opsForHash()).thenReturn(hashOps);
        when(hashOps.entries(CODE_KEY)).thenReturn(Map.of("hash", "hashed", "attempts", "0"));
        when(passwordEncoder.matches("123456", "hashed")).thenReturn(true);

        assertThat(codes.verify(EMAIL, "123456")).isEmpty();
        verify(redis, never()).delete(CODE_KEY);
    }

    @Test
    void consumeDeletesCode() {
        codes.consume(EMAIL);

        verify(redis).delete(CODE_KEY);
    }

    @Test
    void verifyRejectsWhenNoCodeWasIssued() {
        when(redis.opsForHash()).thenReturn(hashOps);
        when(hashOps.entries(CODE_KEY)).thenReturn(Map.of());

        assertThatThrownBy(() -> codes.verify(EMAIL, "123456"))
                .isInstanceOf(InvalidVerificationCodeException.class);
    }

    @Test
    void verifyRejectsWhenAttemptsExhausted() {
        when(redis.opsForHash()).thenReturn(hashOps);
        when(hashOps.entries(CODE_KEY)).thenReturn(Map.of("hash", "hashed", "attempts", "5"));

        assertThatThrownBy(() -> codes.verify(EMAIL, "123456"))
                .isInstanceOf(TooManyVerificationAttemptsException.class);
    }

    @Test
    void verifyRejectsWrongCodeAndIncrementsAttempts() {
        when(redis.opsForHash()).thenReturn(hashOps);
        when(hashOps.entries(CODE_KEY)).thenReturn(Map.of("hash", "hashed", "attempts", "1"));
        when(passwordEncoder.matches("000000", "hashed")).thenReturn(false);
        when(hashOps.increment(CODE_KEY, "attempts", 1)).thenReturn(2L);

        assertThatThrownBy(() -> codes.verify(EMAIL, "000000"))
                .isInstanceOf(InvalidVerificationCodeException.class);
        verify(hashOps).increment(CODE_KEY, "attempts", 1);
    }
}
