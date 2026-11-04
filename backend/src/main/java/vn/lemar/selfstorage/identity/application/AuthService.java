package vn.lemar.selfstorage.identity.application;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.lemar.selfstorage.identity.application.dto.LoginRequest;
import vn.lemar.selfstorage.identity.application.dto.LoginResponse;
import vn.lemar.selfstorage.identity.application.dto.MeResponse;
import vn.lemar.selfstorage.identity.application.exception.AccountNotAllowedException;
import vn.lemar.selfstorage.identity.application.exception.EmailNotVerifiedException;
import vn.lemar.selfstorage.identity.application.exception.InvalidCredentialsException;
import vn.lemar.selfstorage.identity.domain.Account;
import vn.lemar.selfstorage.identity.domain.AccountStatus;
import vn.lemar.selfstorage.identity.repository.AccountRepository;

@Service
public class AuthService {

    private final AccountRepository accountRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final LoginAttemptLimiter loginAttemptLimiter;

    public AuthService(
            AccountRepository accountRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            LoginAttemptLimiter loginAttemptLimiter) {
        this.accountRepository = accountRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.loginAttemptLimiter = loginAttemptLimiter;
    }

    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request, String clientIp) {
        String normalizedEmail = Account.normalize(request.email());

        loginAttemptLimiter.checkOrThrow(normalizedEmail, clientIp);

        Account account = accountRepository.findByEmail(normalizedEmail)
                .orElseThrow(() -> {
                    loginAttemptLimiter.recordFailure(normalizedEmail, clientIp);
                    return new InvalidCredentialsException();
                });

        if (!passwordEncoder.matches(request.password(), account.getPasswordHash())) {
            loginAttemptLimiter.recordFailure(normalizedEmail, clientIp);
            throw new InvalidCredentialsException();
        }

        if (!account.isLoginAllowed()) {
            if (account.getStatus() != AccountStatus.ACTIVE) {
                throw new AccountNotAllowedException(account.getStatus());
            }
            // ACTIVE nhưng chưa xác minh — xác minh là bắt buộc ngay tại đăng ký (Business workflow).
            throw new EmailNotVerifiedException();
        }

        loginAttemptLimiter.reset(normalizedEmail);

        return new LoginResponse(
                account.getId(),
                account.getEmail(),
                jwtService.issueAccessToken(account)
        );
    }

    @Transactional(readOnly = true)
    public MeResponse me(String email) {
        Account account = accountRepository.findWithRoleByEmail(Account.normalize(email))
                .orElseThrow(InvalidCredentialsException::new);

        return new MeResponse(account.getEmail(), account.getRole().getName(), account.getName(),
                account.getPhone());
    }
}
