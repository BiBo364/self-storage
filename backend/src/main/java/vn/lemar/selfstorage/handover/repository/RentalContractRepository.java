package vn.lemar.selfstorage.handover.repository;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.lemar.selfstorage.handover.domain.RentalContract;
import vn.lemar.selfstorage.handover.domain.RentalContractStatus;

public interface RentalContractRepository extends JpaRepository<RentalContract, Long> {

    List<RentalContract> findByOrderIdAndStatusIn(Long orderId, Collection<RentalContractStatus> statuses);

    /**
     * Khoá các hợp đồng còn huỷ được của đơn ({@code Draft}/{@code Signed}); partial unique index V1
     * bảo đảm tối đa một dòng. Sắp theo id để hai instance khoá cùng thứ tự. Phải gọi trong transaction.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from RentalContract c where c.orderId = :orderId "
            + "and c.status in :statuses order by c.id")
    List<RentalContract> findByOrderIdAndStatusInForUpdate(
            @Param("orderId") Long orderId,
            @Param("statuses") Collection<RentalContractStatus> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from RentalContract c where c.id = :id")
    Optional<RentalContract> findByIdForUpdate(@Param("id") Long id);

    /** Hợp đồng đang xử lý của đơn (Draft/Signed/Active) — partial unique index V1: tối đa một. */
    default Optional<RentalContract> findCurrentByOrderId(Long orderId) {
        return findByOrderIdAndStatusIn(orderId, List.of(RentalContractStatus.DRAFT, RentalContractStatus.SIGNED,
                RentalContractStatus.ACTIVE)).stream().findFirst();
    }

    List<RentalContract> findByCustomerIdAndStatusOrderByEndDateAsc(Long customerId, RentalContractStatus status);

    List<RentalContract> findByStatusAndEndDateBefore(RentalContractStatus status, java.time.LocalDate date);

    List<RentalContract> findByStatusAndEndDateAfter(RentalContractStatus status, java.time.LocalDate date);

    List<RentalContract> findByStatusAndEndDateBetween(RentalContractStatus status, java.time.LocalDate from,
                                                       java.time.LocalDate to);
}
