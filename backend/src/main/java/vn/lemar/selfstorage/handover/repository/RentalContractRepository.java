package vn.lemar.selfstorage.handover.repository;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
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
}
