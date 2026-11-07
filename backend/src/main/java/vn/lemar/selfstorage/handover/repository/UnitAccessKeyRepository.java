package vn.lemar.selfstorage.handover.repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.lemar.selfstorage.handover.domain.UnitAccessKey;

public interface UnitAccessKeyRepository extends JpaRepository<UnitAccessKey, Long> {

    List<UnitAccessKey> findByContractIdOrderByIdAsc(Long contractId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select k from UnitAccessKey k where k.contractId = :contractId and k.status = 'Active' order by k.id")
    List<UnitAccessKey> findActiveByContractForUpdate(@Param("contractId") Long contractId);

    /** Dòng Active của một loại — khoá trước khi thu hồi để cấp mã mới. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select k from UnitAccessKey k where k.contractId = :contractId and k.accessType = :type "
            + "and k.status = 'Active'")
    Optional<UnitAccessKey> findActiveForUpdate(@Param("contractId") Long contractId, @Param("type") String type);
}
