package vn.lemar.selfstorage.identity.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.lemar.selfstorage.audit.application.AuditEntry;
import vn.lemar.selfstorage.audit.application.AuditRecorder;
import vn.lemar.selfstorage.audit.domain.AuditAction;
import vn.lemar.selfstorage.audit.domain.AuditEntityType;
import vn.lemar.selfstorage.identity.domain.Account;
import vn.lemar.selfstorage.identity.domain.Role;
import vn.lemar.selfstorage.identity.domain.RoleName;
import vn.lemar.selfstorage.identity.repository.AccountRepository;
import vn.lemar.selfstorage.identity.repository.RoleRepository;

/**
 * Tạo account CUSTOMER đã xác minh trong transaction riêng ({@code REQUIRES_NEW}), để unique
 * violation khi hai request verify chạy đồng thời không làm rollback-only transaction của caller.
 */
@Service
public class VerifiedAccountCreator {

    private final AccountRepository accountRepository;
    private final RoleRepository roleRepository;
    private final AuditRecorder auditRecorder;

    public VerifiedAccountCreator(AccountRepository accountRepository,
                                  RoleRepository roleRepository,
                                  AuditRecorder auditRecorder) {
        this.accountRepository = accountRepository;
        this.roleRepository = roleRepository;
        this.auditRecorder = auditRecorder;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Account create(String email, String passwordHash) {
        return create(email, passwordHash, null, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Account create(String email, String passwordHash, String name, String phone) {
        Role customerRole = roleRepository.findByName(RoleName.CUSTOMER.name())
                .orElseThrow(() -> new IllegalStateException("Role CUSTOMER chưa được seed"));
        Account account = new Account(email, passwordHash, name, phone, customerRole);
        account.markEmailVerified();
        Account saved = accountRepository.saveAndFlush(account);
        auditRecorder.record(new AuditEntry(AuditAction.ACCOUNT_REGISTERED, AuditEntityType.ACCOUNT,
                String.valueOf(saved.getId()), null, null, null, saved.getId()));
        return saved;
    }
}
