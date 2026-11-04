package vn.lemar.selfstorage.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import vn.lemar.selfstorage.identity.domain.Account;
import vn.lemar.selfstorage.identity.domain.AccountStatus;
import vn.lemar.selfstorage.identity.domain.Role;
import vn.lemar.selfstorage.identity.domain.RoleName;
import vn.lemar.selfstorage.identity.repository.AccountRepository;

class AdminRecipientQueryTest {

    private final AccountRepository accounts = mock(AccountRepository.class);
    private final AdminRecipientQuery query = new AdminRecipientQuery(accounts);

    @Test
    void returnsActiveAdminRecipients() {
        Account admin = new Account("admin@lemar.vn", "hash", new Role(RoleName.ADMIN.name()));
        when(accounts.findByRoleNameAndStatus(RoleName.ADMIN.name(), AccountStatus.ACTIVE))
                .thenReturn(List.of(admin));

        List<AdminRecipient> result = query.activeAdmins();

        assertThat(result).singleElement()
                .satisfies(recipient -> assertThat(recipient.email()).isEqualTo("admin@lemar.vn"));
    }
}
