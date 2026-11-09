package vn.lemar.selfstorage.identity.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import vn.lemar.selfstorage.facility.application.FacilityAccess;
import vn.lemar.selfstorage.identity.application.exception.FacilityNotFoundException;
import vn.lemar.selfstorage.identity.application.exception.ForbiddenException;
import vn.lemar.selfstorage.identity.domain.Account;
import vn.lemar.selfstorage.identity.domain.PermissionScope;
import vn.lemar.selfstorage.identity.domain.Role;
import vn.lemar.selfstorage.identity.repository.AccountFacilityAssignmentRepository;
import vn.lemar.selfstorage.identity.repository.PermissionRepository;

class AccessTest {

    private static final String PERMISSION = "facility.access";
    private static final long ROLE_ID = 1L;

    private final CurrentAccountProvider accountProvider = mock(CurrentAccountProvider.class);
    private final AccountFacilityAssignmentRepository assignments =
            mock(AccountFacilityAssignmentRepository.class);
    private final PermissionRepository permissions = mock(PermissionRepository.class);
    private final FacilityAccess facilityAccess = mock(FacilityAccess.class);

    private final Access access = new Access(accountProvider, assignments, permissions, facilityAccess);

    @Test
    void missingMappingDeniedOnGlobalForm() {
        stubAccount(7L, "FM");
        stubScope(null);

        assertThatThrownBy(() -> access.can(PERMISSION)).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void missingMappingDeniedOnFacilityForm() {
        stubAccount(7L, "FM");
        stubScope(null);

        assertThatThrownBy(() -> access.can(PERMISSION, 1L)).isInstanceOf(ForbiddenException.class);
        verifyNoInteractions(facilityAccess);
    }

    @Test
    void globalMappingRejectsFacilityForm() {
        stubAccount(1L, "ADMIN");
        stubScope(PermissionScope.GLOBAL);

        assertThatThrownBy(() -> access.can(PERMISSION, 1L)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void facilityMappingRejectsGlobalForm() {
        stubAccount(7L, "FM");
        stubScope(PermissionScope.FACILITY);

        assertThatThrownBy(() -> access.can(PERMISSION)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void globalFormSkipsFacilityScope() {
        stubAccount(1L, "ADMIN");
        stubScope(PermissionScope.GLOBAL);

        assertThatCode(() -> access.can(PERMISSION)).doesNotThrowAnyException();
        verifyNoInteractions(facilityAccess, assignments);
    }

    @Test
    void nullFacilityIdRejected() {
        assertThatThrownBy(() -> access.can(PERMISSION, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void nonexistentFacilityNotFound() {
        stubAccount(7L, "FM");
        stubScope(PermissionScope.FACILITY);
        when(facilityAccess.exists(3L)).thenReturn(false);

        assertThatThrownBy(() -> access.can(PERMISSION, 3L))
                .isInstanceOf(FacilityNotFoundException.class);
        verify(facilityAccess, never()).isManagedBy(7L, 3L);
    }

    @Test
    void fmAllowedOnManagedFacility() {
        stubAccount(7L, "FM");
        stubScope(PermissionScope.FACILITY);
        when(facilityAccess.exists(1L)).thenReturn(true);
        when(facilityAccess.isManagedBy(7L, 1L)).thenReturn(true);

        assertThatCode(() -> access.can(PERMISSION, 1L)).doesNotThrowAnyException();
    }

    @Test
    void fmDeniedOnOtherFacility() {
        stubAccount(7L, "FM");
        stubScope(PermissionScope.FACILITY);
        when(facilityAccess.exists(2L)).thenReturn(true);
        when(facilityAccess.isManagedBy(7L, 2L)).thenReturn(false);

        assertThatThrownBy(() -> access.can(PERMISSION, 2L)).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void fsAllowedOnAssignedFacility() {
        stubAccount(9L, "FS");
        stubScope(PermissionScope.FACILITY);
        when(facilityAccess.exists(1L)).thenReturn(true);
        when(assignments.existsByAccountIdAndFacilityId(9L, 1L)).thenReturn(true);

        assertThatCode(() -> access.can(PERMISSION, 1L)).doesNotThrowAnyException();
    }

    @Test
    void fsDeniedOnUnassignedFacility() {
        stubAccount(9L, "FS");
        stubScope(PermissionScope.FACILITY);
        when(facilityAccess.exists(2L)).thenReturn(true);
        when(assignments.existsByAccountIdAndFacilityId(9L, 2L)).thenReturn(false);

        assertThatThrownBy(() -> access.can(PERMISSION, 2L)).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void adminBypassesFacilityScope() {
        stubAccount(1L, "ADMIN");
        stubScope(PermissionScope.FACILITY);
        when(facilityAccess.exists(2L)).thenReturn(true);

        assertThatCode(() -> access.can(PERMISSION, 2L)).doesNotThrowAnyException();
        verify(facilityAccess, never()).isManagedBy(1L, 2L);
    }

    @Test
    void customerWithoutPermissionDenied() {
        stubAccount(5L, "CUSTOMER");
        stubScope(null);

        assertThatThrownBy(() -> access.can(PERMISSION, 1L)).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void globalOrAtPassesGlobalMappingWithoutFacilityCheck() {
        stubAccount(1L, "BOM");
        stubScope(PermissionScope.GLOBAL);

        assertThatCode(() -> access.canGlobalOrAt(PERMISSION, 2L)).doesNotThrowAnyException();
        verifyNoInteractions(facilityAccess, assignments);
    }

    @Test
    void globalOrAtChecksFacilityForFacilityMapping() {
        stubAccount(7L, "FM");
        stubScope(PermissionScope.FACILITY);
        when(facilityAccess.exists(2L)).thenReturn(true);
        when(facilityAccess.isManagedBy(7L, 2L)).thenReturn(false);

        assertThatThrownBy(() -> access.canGlobalOrAt(PERMISSION, 2L)).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void globalOrAtDeniesRoleWithoutMapping() {
        stubAccount(5L, "CUSTOMER");
        stubScope(null);

        assertThatThrownBy(() -> access.canGlobalOrAt(PERMISSION, 1L)).isInstanceOf(ForbiddenException.class);
    }

    private void stubScope(PermissionScope scope) {
        when(permissions.findScope(ROLE_ID, PERMISSION)).thenReturn(Optional.ofNullable(scope));
    }

    private void stubAccount(Long id, String roleName) {
        Account account = account(id, roleName);
        when(accountProvider.getCurrentAccount()).thenReturn(account);
    }

    private Account account(Long id, String roleName) {
        Role role = mock(Role.class);
        when(role.getId()).thenReturn(ROLE_ID);
        when(role.getName()).thenReturn(roleName);

        Account account = mock(Account.class);
        when(account.getId()).thenReturn(id);
        when(account.getRole()).thenReturn(role);
        return account;
    }
}
