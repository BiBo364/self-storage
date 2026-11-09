package vn.lemar.selfstorage.identity.application;

import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vn.lemar.selfstorage.identity.application.dto.RegisterRequest;
import vn.lemar.selfstorage.identity.application.dto.ResendVerificationRequest;
import vn.lemar.selfstorage.identity.application.dto.VerifyEmailRequest;
import vn.lemar.selfstorage.identity.application.exception.EmailAlreadyExistsException;
import vn.lemar.selfstorage.identity.application.exception.EmailAlreadyVerifiedException;
import vn.lemar.selfstorage.identity.application.exception.EmailNotRegisteredException;
import vn.lemar.selfstorage.identity.application.exception.InvalidVerificationCodeException;
import vn.lemar.selfstorage.identity.application.exception.VerificationResendTooEarlyException;
import vn.lemar.selfstorage.identity.domain.Account;
import vn.lemar.selfstorage.identity.domain.Role;
import vn.lemar.selfstorage.identity.repository.AccountRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RegistrationServiceTest {

    private static final Duration TTL = Duration.ofMinutes(10);
    private static final String EMAIL = "customer@example.com";

    @Mock
    private AccountRepository accountRepository;
    @Mock
    private VerifiedAccountCreator verifiedAccountCreator;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private EmailVerificationCodes verificationCodes;
    @Mock
    private ApplicationEventPublisher events;

    private RegistrationService registrationService;

    @BeforeEach
    void setUp() {
        registrationService = new RegistrationService(accountRepository, verifiedAccountCreator, passwordEncoder,
                verificationCodes, events, TTL);
    }

    // ------------------------------------------------------------ register: chỉ ghi pending

    @Test
    void registerStoresPendingAndPublishesVerificationEmail() {
        when(accountRepository.existsByEmail(EMAIL)).thenReturn(false);
        when(passwordEncoder.encode("password123")).thenReturn("password-hash");
        when(verificationCodes.issue(EMAIL, "password-hash", null, null)).thenReturn("123456");

        registrationService.register(new RegisterRequest(" Customer@Example.com ", "password123"));

        verify(verificationCodes).markResendCooldown(EMAIL);
        verify(verificationCodes).issue(EMAIL, "password-hash", null, null);
        verify(events).publishEvent(new VerificationEmailRequested(EMAIL, "123456", TTL));
        verify(accountRepository, never()).save(any());
        verifyNoInteractions(verifiedAccountCreator);
    }

    @Test
    void registerWhilePendingDoesNotOverwriteStoredPassword() {
        when(accountRepository.existsByEmail(EMAIL)).thenReturn(false);
        when(verificationCodes.hasPending(EMAIL)).thenReturn(true);

        assertThatThrownBy(() -> registrationService.register(new RegisterRequest(EMAIL, "attacker-pass")))
                .isInstanceOf(vn.lemar.selfstorage.identity.application.exception.RegistrationPendingException.class);

        verify(verificationCodes, never()).issue(any(), any(), any(), any());
        verifyNoInteractions(events);
    }

    @Test
    void registerAndVerifyPassProfileToAccount() {
        when(accountRepository.existsByEmail(EMAIL)).thenReturn(false);
        when(passwordEncoder.encode("password123")).thenReturn("password-hash");
        when(verificationCodes.issue(EMAIL, "password-hash", "Nguyễn Văn A", "0900000000"))
                .thenReturn("123456");

        registrationService.register(new RegisterRequest(EMAIL, "password123", "Nguyễn Văn A", "0900000000"));

        verify(verificationCodes).issue(EMAIL, "password-hash", "Nguyễn Văn A", "0900000000");

        Account created = new Account(EMAIL, "password-hash", "Nguyễn Văn A", "0900000000",
                new Role("CUSTOMER"));
        created.markEmailVerified();
        ReflectionTestUtils.setField(created, "id", 42L);
        when(accountRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());
        when(verificationCodes.verify(EMAIL, "123456")).thenReturn(Optional.of(
                new PendingRegistration(EMAIL, "password-hash", "Nguyễn Văn A", "0900000000")));
        when(verifiedAccountCreator.create(EMAIL, "password-hash", "Nguyễn Văn A", "0900000000"))
                .thenReturn(created);

        registrationService.verifyEmail(new VerifyEmailRequest(EMAIL, "123456"));

        verify(verifiedAccountCreator).create(EMAIL, "password-hash", "Nguyễn Văn A", "0900000000");
    }

    @Test
    void registerRejectsExistingEmail() {
        when(accountRepository.existsByEmail(EMAIL)).thenReturn(true);

        assertThatThrownBy(() -> registrationService.register(new RegisterRequest(EMAIL, "password123")))
                .isInstanceOf(EmailAlreadyExistsException.class);
        verifyNoInteractions(verificationCodes, events, verifiedAccountCreator);
    }

    @Test
    void registerStopsAtCooldownViolation() {
        when(accountRepository.existsByEmail(EMAIL)).thenReturn(false);
        doThrow(new VerificationResendTooEarlyException())
                .when(verificationCodes).markResendCooldown(EMAIL);

        assertThatThrownBy(() -> registrationService.register(new RegisterRequest(EMAIL, "password123")))
                .isInstanceOf(VerificationResendTooEarlyException.class);
        verifyNoInteractions(events, verifiedAccountCreator);
    }

    // ------------------------------------------------------------ verify: tạo account từ pending

    @Test
    void verifyCreatesVerifiedCustomerAccountFromPending() {
        Account created = new Account(EMAIL, "password-hash", new Role("CUSTOMER"));
        created.markEmailVerified();
        ReflectionTestUtils.setField(created, "id", 42L);
        when(accountRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());
        when(verificationCodes.verify(EMAIL, "123456"))
                .thenReturn(Optional.of(new PendingRegistration(EMAIL, "password-hash")));
        when(verifiedAccountCreator.create(EMAIL, "password-hash", null, null)).thenReturn(created);

        EmailVerificationResult result =
                registrationService.verifyEmail(new VerifyEmailRequest(" Customer@Example.com ", "123456"));

        assertThat(result).isEqualTo(EmailVerificationResult.VERIFIED);
        verify(verifiedAccountCreator).create(EMAIL, "password-hash", null, null);
        verify(events).publishEvent(new EmailVerified(42L));
    }

    @Test
    void verifyIsIdempotentForVerifiedAccount() {
        Account account = new Account(EMAIL, "hash", new Role("CUSTOMER"));
        account.markEmailVerified();
        when(accountRepository.findByEmail(EMAIL)).thenReturn(Optional.of(account));

        EmailVerificationResult result =
                registrationService.verifyEmail(new VerifyEmailRequest(EMAIL, "000000"));

        assertThat(result).isEqualTo(EmailVerificationResult.ALREADY_VERIFIED);
        verifyNoInteractions(verificationCodes, verifiedAccountCreator, events);
    }

    @Test
    void verifyLegacyUnverifiedAccountJustMarksVerified() {
        Account account = new Account(EMAIL, "hash", new Role("CUSTOMER"));
        ReflectionTestUtils.setField(account, "id", 7L);
        when(accountRepository.findByEmail(EMAIL)).thenReturn(Optional.of(account));
        when(verificationCodes.verify(EMAIL, "123456")).thenReturn(Optional.empty());

        EmailVerificationResult result =
                registrationService.verifyEmail(new VerifyEmailRequest(EMAIL, "123456"));

        assertThat(result).isEqualTo(EmailVerificationResult.VERIFIED);
        assertThat(account.isVerified()).isTrue();
        verify(accountRepository, never()).save(any());
        verifyNoInteractions(verifiedAccountCreator);
    }

    @Test
    void verifyRejectsUnknownEmailWithoutPending() {
        when(accountRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());
        when(verificationCodes.verify(EMAIL, "123456")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> registrationService.verifyEmail(new VerifyEmailRequest(EMAIL, "123456")))
                .isInstanceOf(InvalidVerificationCodeException.class);
        verify(accountRepository, never()).save(any());
    }

    @Test
    void verifyPropagatesAccountCreationFailure() {
        when(accountRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());
        when(verificationCodes.verify(EMAIL, "123456"))
                .thenReturn(Optional.of(new PendingRegistration(EMAIL, "password-hash")));
        when(verifiedAccountCreator.create(EMAIL, "password-hash", null, null))
                .thenThrow(new IllegalStateException("Role CUSTOMER chưa được seed"));

        assertThatThrownBy(() -> registrationService.verifyEmail(new VerifyEmailRequest(EMAIL, "123456")))
                .isInstanceOf(IllegalStateException.class);
        verify(verificationCodes, never()).consume(EMAIL);
        verifyNoInteractions(events);
    }

    @Test
    void verifyConsumesCodeOnlyAfterCommit() {
        Account created = new Account(EMAIL, "password-hash", new Role("CUSTOMER"));
        created.markEmailVerified();
        ReflectionTestUtils.setField(created, "id", 42L);
        when(accountRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());
        when(verificationCodes.verify(EMAIL, "123456"))
                .thenReturn(Optional.of(new PendingRegistration(EMAIL, "password-hash")));
        when(verifiedAccountCreator.create(EMAIL, "password-hash", null, null)).thenReturn(created);

        TransactionSynchronizationManager.initSynchronization();
        try {
            registrationService.verifyEmail(new VerifyEmailRequest(EMAIL, "123456"));
            verify(verificationCodes, never()).consume(EMAIL);

            for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCommit();
            }
            verify(verificationCodes).consume(EMAIL);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void verifyMapsConcurrentAccountCreationToAlreadyVerified() {
        Account verified = new Account(EMAIL, "hash", new Role("CUSTOMER"));
        verified.markEmailVerified();
        when(accountRepository.findByEmail(EMAIL))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(verified));
        when(verificationCodes.verify(EMAIL, "123456"))
                .thenReturn(Optional.of(new PendingRegistration(EMAIL, "password-hash")));
        when(verifiedAccountCreator.create(EMAIL, "password-hash", null, null))
                .thenThrow(new DataIntegrityViolationException("duplicate"));

        EmailVerificationResult result =
                registrationService.verifyEmail(new VerifyEmailRequest(EMAIL, "123456"));

        assertThat(result).isEqualTo(EmailVerificationResult.ALREADY_VERIFIED);
        verify(verificationCodes, never()).consume(EMAIL);
    }

    @Test
    void verifyPublishesEmailVerifiedOnlyOnFirstVerification() {
        Account created = new Account(EMAIL, "password-hash", new Role("CUSTOMER"));
        created.markEmailVerified();
        ReflectionTestUtils.setField(created, "id", 42L);
        when(accountRepository.findByEmail(EMAIL))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(created));
        when(verificationCodes.verify(EMAIL, "123456"))
                .thenReturn(Optional.of(new PendingRegistration(EMAIL, "password-hash")));
        when(verifiedAccountCreator.create(EMAIL, "password-hash", null, null)).thenReturn(created);

        registrationService.verifyEmail(new VerifyEmailRequest(EMAIL, "123456"));
        registrationService.verifyEmail(new VerifyEmailRequest(EMAIL, "123456"));

        verify(events, times(1)).publishEvent(new EmailVerified(42L));
    }

    // ------------------------------------------------------------ resend

    @Test
    void resendReissuesCodeForPendingRegistration() {
        when(accountRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());
        when(verificationCodes.hasPending(EMAIL)).thenReturn(true);
        when(verificationCodes.reissue(EMAIL)).thenReturn("654321");

        registrationService.resendVerification(new ResendVerificationRequest(EMAIL));

        verify(verificationCodes).markResendCooldown(EMAIL);
        verify(events).publishEvent(new VerificationEmailRequested(EMAIL, "654321", TTL));
    }

    @Test
    void resendStopsAtCooldownViolation() {
        when(accountRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());
        when(verificationCodes.hasPending(EMAIL)).thenReturn(true);
        doThrow(new VerificationResendTooEarlyException())
                .when(verificationCodes).markResendCooldown(EMAIL);

        assertThatThrownBy(() -> registrationService.resendVerification(new ResendVerificationRequest(EMAIL)))
                .isInstanceOf(VerificationResendTooEarlyException.class);
        verify(verificationCodes, never()).reissue(EMAIL);
        verifyNoInteractions(events);
    }

    @Test
    void resendRejectsVerifiedAccount() {
        Account account = new Account(EMAIL, "hash", new Role("CUSTOMER"));
        account.markEmailVerified();
        when(accountRepository.findByEmail(EMAIL)).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> registrationService.resendVerification(new ResendVerificationRequest(EMAIL)))
                .isInstanceOf(EmailAlreadyVerifiedException.class);
        verifyNoInteractions(verificationCodes);
    }

    @Test
    void resendRejectsUnknownEmailWithoutPending() {
        when(accountRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());
        when(verificationCodes.hasPending(EMAIL)).thenReturn(false);

        assertThatThrownBy(() -> registrationService.resendVerification(new ResendVerificationRequest(EMAIL)))
                .isInstanceOf(EmailNotRegisteredException.class);
        verify(verificationCodes, never()).markResendCooldown(EMAIL);
        verifyNoInteractions(events);
    }

    @Test
    void resendLegacyUnverifiedAccountReissuesCode() {
        Account account = new Account(EMAIL, "hash", new Role("CUSTOMER"));
        when(accountRepository.findByEmail(EMAIL)).thenReturn(Optional.of(account));
        when(verificationCodes.reissue(EMAIL)).thenReturn("654321");

        registrationService.resendVerification(new ResendVerificationRequest(EMAIL));

        verify(verificationCodes).markResendCooldown(EMAIL);
        verify(events).publishEvent(new VerificationEmailRequested(EMAIL, "654321", TTL));
    }
}
