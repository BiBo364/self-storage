package vn.lemar.selfstorage.tenancy.repository;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.lemar.selfstorage.tenancy.domain.ReturnRequest;

public interface ReturnRequestRepository extends JpaRepository<ReturnRequest, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ReturnRequest r where r.id = :id")
    Optional<ReturnRequest> findByIdForUpdate(@Param("id") Long id);

    /** Yêu cầu đang mở của hợp đồng — partial unique index V20 bảo đảm tối đa một. */
    @Query("select r from ReturnRequest r where r.contractId = :contractId and r.status in ('Pending', 'Assigned')")
    Optional<ReturnRequest> findOpenByContractId(@Param("contractId") Long contractId);

    List<ReturnRequest> findByCustomerIdOrderByIdDesc(Long customerId);

    List<ReturnRequest> findByFacilityIdAndStatusInOrderByIdAsc(Long facilityId, Collection<String> statuses);

    List<ReturnRequest> findByAssignedStaffIdAndStatusOrderByIdAsc(Long staffId, String status);

    /** Yêu cầu đang giữ lịch trả kho này — cron no-show trả kho. */
    Optional<ReturnRequest> findByAppointmentIdAndStatus(Long appointmentId, String status);
}
