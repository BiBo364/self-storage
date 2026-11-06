package vn.lemar.selfstorage.identity.application;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.lemar.selfstorage.identity.domain.AccountStatus;
import vn.lemar.selfstorage.identity.domain.RoleName;
import vn.lemar.selfstorage.identity.repository.AccountRepository;

/** Public query cho alert vận hành gửi ADMIN; không lộ repository. */
@Service
public class AdminRecipientQuery {

    private final AccountRepository accounts;

    public AdminRecipientQuery(AccountRepository accounts) {
        this.accounts = accounts;
    }

    @Transactional(readOnly = true)
    public List<AdminRecipient> activeAdmins() {
        return accounts.findByRoleNameAndStatus(RoleName.ADMIN.name(), AccountStatus.ACTIVE)
                .stream()
                .map(account -> new AdminRecipient(account.getId(), account.getEmail()))
                .toList();
    }
}
