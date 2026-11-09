package vn.lemar.selfstorage.support.repository;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.lemar.selfstorage.support.domain.SupportRequest;

public interface SupportRequestRepository extends JpaRepository<SupportRequest, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from SupportRequest s where s.id = :id")
    Optional<SupportRequest> findByIdForUpdate(@Param("id") Long id);

    List<SupportRequest> findByCustomerIdOrderByIdDesc(Long customerId);

    List<SupportRequest> findByFacilityIdAndStatusInOrderByIdDesc(Long facilityId, Collection<String> statuses);

    List<SupportRequest> findByAssignedStaffIdAndStatusOrderByIdAsc(Long staffId, String status);
}
