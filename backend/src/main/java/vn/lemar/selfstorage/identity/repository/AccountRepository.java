package vn.lemar.selfstorage.identity.repository;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.lemar.selfstorage.identity.domain.Account;
import vn.lemar.selfstorage.identity.domain.AccountStatus;

import java.util.List;
import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Long> {

    Optional<Account> findByEmail(String normalizedEmail);

    @EntityGraph(attributePaths = "role")
    Optional<Account> findWithRoleByEmail(String normalizedEmail);

    boolean existsByEmail(String normalizedEmail);

    @Query("select a from Account a join a.role r where r.name = :roleName and a.status = :status")
    List<Account> findByRoleNameAndStatus(@Param("roleName") String roleName,
                                          @Param("status") AccountStatus status);
}