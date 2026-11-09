package vn.lemar.selfstorage.facility.repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.lemar.selfstorage.facility.domain.StorageUnit;

public interface StorageUnitRepository extends JpaRepository<StorageUnit, Long> {

    /**
     * Khoá row khoang ({@code SELECT ... FOR UPDATE}) — quy tắc backend: mọi thao tác phụ thuộc
     * {@code StorageUnit.status} phải khoá theo {@code unit_id} để không đọc trạng thái cũ trong
     * lúc luồng khác (đặt cọc) đang đổi. Phải gọi trong transaction.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from StorageUnit u where u.id = :id")
    Optional<StorageUnit> findByIdForUpdate(@Param("id") Long id);

    /** Khoang bảo trì sau trả kho đã đủ ngày — cron mở lại. */
    @Query("select u.id from StorageUnit u where u.maintenanceStartedAt is not null "
            + "and u.maintenanceStartedAt <= :cutoff order by u.id")
    java.util.List<Long> findMaintenanceDueIds(@Param("cutoff") java.time.Instant cutoff);
}
