package vn.lemar.selfstorage.identity.application;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import vn.lemar.selfstorage.identity.application.dto.LoginRequest;
import vn.lemar.selfstorage.identity.application.dto.LoginResponse;
import vn.lemar.selfstorage.identity.application.dto.MeResponse;
import vn.lemar.selfstorage.identity.application.exception.InvalidCredentialsException;
import vn.lemar.selfstorage.identity.application.exception.AccountNotAllowedException;
import vn.lemar.selfstorage.identity.application.exception.EmailNotVerifiedException;
import vn.lemar.selfstorage.identity.application.exception.TooManyLoginAttemptsException;
import vn.lemar.selfstorage.identity.domain.Account;
import vn.lemar.selfstorage.identity.domain.AccountStatus;
import vn.lemar.selfstorage.identity.domain.Role;
import vn.lemar.selfstorage.identity.repository.AccountRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final String IP = "203.0.113.7";

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtService jwtService;

    @Mock
    private LoginAttemptLimiter loginAttemptLimiter;

    @InjectMocks
    private AuthService authService;

    @Test
    void loginNormalizesEmailAndReturnsIssuedToken() {
        Account account = mock(Account.class);
        when(account.getId()).thenReturn(42L);
        when(account.getEmail()).thenReturn("customer@example.com");
        when(account.getPasswordHash()).thenReturn("password-hash");
        when(account.isLoginAllowed()).thenReturn(true);
        when(accountRepository.findByEmail("customer@example.com")).thenReturn(Optional.of(account));
        when(passwordEncoder.matches("password", "password-hash")).thenReturn(true);
        when(jwtService.issueAccessToken(account)).thenReturn("signed.jwt.token");

        LoginResponse response = authService.login(new LoginRequest(" Customer@Example.com ", "password"), IP);

        assertThat(response.accountId()).isEqualTo(42L);
        assertThat(response.email()).isEqualTo("customer@example.com");
        assertThat(response.token()).isEqualTo("signed.jwt.token");
        verify(accountRepository).findByEmail("customer@example.com");
        verify(jwtService).issueAccessToken(account);
        verify(loginAttemptLimiter).reset("customer@example.com");
        verify(loginAttemptLimiter, never()).recordFailure("customer@example.com", IP);
    }

    @Test
    void loginIsBlockedBeforeCheckingCredentialsWhenLimitExceeded() {
        doThrow(new TooManyLoginAttemptsException())
                .when(loginAttemptLimiter).checkOrThrow("customer@example.com", IP);

        assertThatThrownBy(() -> authService.login(
                new LoginRequest("customer@example.com", "password"), IP))
                .isInstanceOf(TooManyLoginAttemptsException.class);
        verifyNoInteractions(accountRepository, passwordEncoder, jwtService);
    }

    @Test
    void loginRejectsIncorrectPasswordWithoutIssuingToken() {
        Account account = mock(Account.class);
        when(account.getPasswordHash()).thenReturn("password-hash");
        when(accountRepository.findByEmail("customer@example.com")).thenReturn(Optional.of(account));
        when(passwordEncoder.matches("wrong", "password-hash")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(new LoginRequest("customer@example.com", "wrong"), IP))
                .isInstanceOf(InvalidCredentialsException.class);
        verify(loginAttemptLimiter).recordFailure("customer@example.com", IP);
        verify(loginAttemptLimiter, never()).reset("customer@example.com");
    }

    @Test
    void loginRejectsBannedAccount() {
        Account account = mock(Account.class);
        when(account.getPasswordHash()).thenReturn("password-hash");
        when(account.isLoginAllowed()).thenReturn(false);
        when(account.getStatus()).thenReturn(AccountStatus.BANNED);
        when(accountRepository.findByEmail("user@example.com")).thenReturn(Optional.of(account));
        when(passwordEncoder.matches("password", "password-hash")).thenReturn(true);

        assertThatThrownBy(() -> authService.login(new LoginRequest("user@example.com", "password"), IP))
                .isInstanceOfSatisfying(AccountNotAllowedException.class,
                        ex -> assertThat(ex.getStatus()).isEqualTo(AccountStatus.BANNED))
                .hasMessageNotContaining("Email hoặc mật khẩu");
        verify(loginAttemptLimiter, never()).recordFailure("user@example.com", IP);
    }

    @Test
    void loginRejectsUnknownEmail() {
        when(accountRepository.findByEmail("ghost@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(new LoginRequest("ghost@example.com", "password"), IP))
                .isInstanceOf(InvalidCredentialsException.class);
        verify(loginAttemptLimiter).recordFailure("ghost@example.com", IP);
    }

    @Test
    void loginRejectsActiveAccountWithUnverifiedEmail() {
        Account account = new Account("customer@example.com", "hash", new Role("CUSTOMER"));
        when(accountRepository.findByEmail("customer@example.com")).thenReturn(Optional.of(account));
        when(passwordEncoder.matches("password", "hash")).thenReturn(true);

        assertThatThrownBy(() -> authService.login(new LoginRequest("customer@example.com", "password"), IP))
                .isInstanceOf(EmailNotVerifiedException.class)
                .hasMessage("Tài khoản chưa xác minh email");

        assertThat(account.isVerified()).isFalse();
        verify(loginAttemptLimiter, never()).reset("customer@example.com");
    }

    @Test
    void meReturnsEmailAndRoleForCurrentAccount() {
        Account account = new Account("Customer@Example.com ", "hash", "Nguyễn Văn A", "0900000000",
                new Role("CUSTOMER"));
        when(accountRepository.findWithRoleByEmail("customer@example.com")).thenReturn(Optional.of(account));

        MeResponse response = authService.me("customer@example.com");

        assertThat(response.email()).isEqualTo("customer@example.com");
        assertThat(response.role()).isEqualTo("CUSTOMER");
        assertThat(response.name()).isEqualTo("Nguyễn Văn A");
        assertThat(response.phone()).isEqualTo("0900000000");
    }

    @Test
    void meRejectsUnknownAccount() {
        when(accountRepository.findWithRoleByEmail("ghost@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.me("ghost@example.com"))
                .isInstanceOf(InvalidCredentialsException.class);
    }
}
