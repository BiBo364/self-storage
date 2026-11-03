package vn.lemar.selfstorage.identity.application;

import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vn.lemar.selfstorage.identity.application.dto.RegisterRequest;
import vn.lemar.selfstorage.identity.application.dto.ResendVerificationRequest;
import vn.lemar.selfstorage.identity.application.dto.VerifyEmailRequest;
import vn.lemar.selfstorage.identity.application.exception.EmailAlreadyExistsException;
import vn.lemar.selfstorage.identity.application.exception.EmailAlreadyVerifiedException;
import vn.lemar.selfstorage.identity.application.exception.EmailNotRegisteredException;
import vn.lemar.selfstorage.identity.application.exception.InvalidVerificationCodeException;
import vn.lemar.selfstorage.identity.domain.Account;
import vn.lemar.selfstorage.identity.repository.AccountRepository;

/**
 * Đăng ký tài khoản khách hàng + vòng đời mã xác minh email (behavior-gap #139).
 *
 * <p>Luồng verify-first (proposal #152): đăng ký chỉ ghi *pending registration* (email + hash mật
 * khẩu trong Redis, cùng TTL với mã) và gửi mã; {@link Account} chỉ được tạo sau khi khách nhập
 * đúng mã — email typo/không kiểm soát không để lại account rác và không chiếm được email của
 * người khác. Gửi email đi qua event {@link VerificationEmailRequested} (module notification lắng
 * nghe) — gửi after-commit nên rollback không gửi.
 */
@Service
public class RegistrationService {

    private static final Logger LOG = LoggerFactory.getLogger(RegistrationService.class);

    private final AccountRepository accountRepository;
    private final VerifiedAccountCreator verifiedAccountCreator;
    private final PasswordEncoder passwordEncoder;
    private final EmailVerificationCodes verificationCodes;
    private final ApplicationEventPublisher events;
    private final Duration codeTtl;

    public RegistrationService(
            AccountRepository accountRepository,
            VerifiedAccountCreator verifiedAccountCreator,
            PasswordEncoder passwordEncoder,
            EmailVerificationCodes verificationCodes,
            ApplicationEventPublisher events,
            @Value("${security.email-verification.ttl:PT10M}") Duration codeTtl) {
        this.accountRepository = accountRepository;
        this.verifiedAccountCreator = verifiedAccountCreator;
        this.passwordEncoder = passwordEncoder;
        this.verificationCodes = verificationCodes;
        this.events = events;
        this.codeTtl = codeTtl;
    }

    /** Ghi pending registration + gửi mã xác minh; **chưa tạo** {@link Account}. */
    @Transactional(readOnly = true)
    public void register(RegisterRequest request) {
        String email = Account.normalize(request.email());
        if (accountRepository.existsByEmail(email)) {
            throw new EmailAlreadyExistsException();
        }
        verificationCodes.markResendCooldown(email);
        String code = verificationCodes.issue(email, passwordEncoder.encode(request.password()),
                request.name(), request.phone());
        events.publishEvent(new VerificationEmailRequested(email, code, codeTtl));
    }

    /**
     * Xác minh email. Yêu cầu đăng ký chưa hoàn tất → tạo {@code Account} CUSTOMER đã xác minh +
     * audit {@code ACCOUNT_REGISTERED}; account legacy chưa xác minh (seed/admin) → chỉ mark verified.
     * Account đã xác minh → no-op (idempotent, không kiểm mã).
     */
    @Transactional
    public EmailVerificationResult verifyEmail(VerifyEmailRequest request) {
        String email = Account.normalize(request.email());
        Optional<Account> existing = accountRepository.findByEmail(email);
        if (existing.isPresent()) {
            Account account = existing.get();
            if (account.isVerified()) {
                return EmailVerificationResult.ALREADY_VERIFIED;
            }
            verificationCodes.verify(email, request.code());
            account.markEmailVerified();
            events.publishEvent(new EmailVerified(account.getId()));
            consumeCodeAfterCommit(email);
            return EmailVerificationResult.VERIFIED;
        }

        PendingRegistration pending = verificationCodes.verify(email, request.code())
                .orElseThrow(InvalidVerificationCodeException::new);
        Account account;
        try {
            account = verifiedAccountCreator.create(email, pending.passwordHash(), pending.name(), pending.phone());
        } catch (DataIntegrityViolationException e) {
            // Verify đồng thời: request còn lại đã tạo account (transaction riêng đã commit) —
            // coi như đã xác minh, không để unique violation làm hỏng transaction ngoài.
            return accountRepository.findByEmail(email)
                    .filter(Account::isVerified)
                    .map(ignored -> EmailVerificationResult.ALREADY_VERIFIED)
                    .orElseThrow(() -> e);
        }
        events.publishEvent(new EmailVerified(account.getId()));
        consumeCodeAfterCommit(email);
        return EmailVerificationResult.VERIFIED;
    }

    /**
     * Chỉ xoá mã xác minh sau khi transaction tạo/mark account commit — Redis không rollback theo
     * DB, xoá sớm thì save lỗi làm pending mất và khách phải đăng ký lại.
     */
    private void consumeCodeAfterCommit(String email) {
        Runnable consume = () -> {
            try {
                verificationCodes.consume(email);
            } catch (RuntimeException e) {
                // Best-effort: mã còn tới TTL cũng không chặn verify; Redis lỗi không được
                // làm hỏng afterCommit của listener claim.
                LOG.warn("Không xoá được mã xác minh sau commit cho {}: {}", email, e.getMessage());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    consume.run();
                }
            });
            return;
        }
        consume.run();
    }

    /** Gửi lại mã: pending registration chưa hoàn tất, hoặc account legacy chưa xác minh. */
    @Transactional(readOnly = true)
    public void resendVerification(ResendVerificationRequest request) {
        String email = Account.normalize(request.email());
        Optional<Account> account = accountRepository.findByEmail(email);
        if (account.isPresent()) {
            if (account.get().isVerified()) {
                throw new EmailAlreadyVerifiedException();
            }
            resendCode(email);
            return;
        }
        if (!verificationCodes.hasPending(email)) {
            throw new EmailNotRegisteredException();
        }
        resendCode(email);
    }

    private void resendCode(String email) {
        verificationCodes.markResendCooldown(email);
        String code = verificationCodes.reissue(email);
        events.publishEvent(new VerificationEmailRequested(email, code, codeTtl));
    }

}
