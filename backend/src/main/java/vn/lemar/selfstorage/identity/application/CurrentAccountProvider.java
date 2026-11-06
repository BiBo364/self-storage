package vn.lemar.selfstorage.identity.application;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import vn.lemar.selfstorage.identity.application.exception.UnauthenticatedException;
import vn.lemar.selfstorage.identity.domain.Account;
import vn.lemar.selfstorage.identity.repository.AccountRepository;

/** Lấy account của request hiện tại từ SecurityContext (principal là email). */
@Component
public class CurrentAccountProvider {

    private final AccountRepository accountRepository;

    public CurrentAccountProvider(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    public Account getCurrentAccount() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication instanceof AnonymousAuthenticationToken
                || !authentication.isAuthenticated()) {
            throw new UnauthenticatedException();
        }
        return accountRepository.findWithRoleByEmail(Account.normalize(authentication.getName()))
                .orElseThrow(UnauthenticatedException::new);
    }

    /** Id account của request hiện tại — cho module khác cần actor (ví dụ AuditLog) không phải chạm domain. */
    public Long getCurrentAccountId() {
        return getCurrentAccount().getId();
    }

    /**
     * Khách đã xác minh email chưa.
     *
     * <p>Từ #151, account chưa xác minh không được cấp {@code ACCOUNT_ACTIVE} nên đã bị chặn ở
     * tầng filter — method này còn lại như một lớp phòng thủ cho service, không phải cổng chính.
     */
    public boolean isCurrentAccountVerified() {
        return getCurrentAccount().isVerified();
    }
}