package vn.lemar.selfstorage.support.repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.lemar.selfstorage.support.domain.UnitTransfer;

public interface UnitTransferRepository extends JpaRepository<UnitTransfer, Long> {

    /** Lần chuyển khoang đang mở ({@code Requested} / {@code Allocated}) của yêu cầu hỗ trợ, khoá row. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from UnitTransfer t where t.supportRequestId = :requestId "
            + "and t.status in ('Requested', 'Allocated')")
    Optional<UnitTransfer> findOpenForUpdate(@Param("requestId") Long supportRequestId);

    /** Lần chuyển khoang gần nhất của yêu cầu (mọi trạng thái) — để hiển thị. */
    Optional<UnitTransfer> findFirstBySupportRequestIdOrderByIdDesc(Long supportRequestId);
}
