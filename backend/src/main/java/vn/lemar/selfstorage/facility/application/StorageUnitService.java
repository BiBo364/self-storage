package vn.lemar.selfstorage.facility.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.lemar.selfstorage.facility.domain.StorageUnit;
import vn.lemar.selfstorage.facility.repository.StorageUnitRepository;

/** Public API đổi trạng thái khoang cho module nghiệp vụ — luôn khoá row theo {@code unit_id}. */
@Service
public class StorageUnitService {

    private final StorageUnitRepository storageUnitRepository;

    public StorageUnitService(StorageUnitRepository storageUnitRepository) {
        this.storageUnitRepository = storageUnitRepository;
    }

    /**
     * Đặt cọc thành công (Flow 1.4): giữ chỗ {@code Available -> Reserved}. Khoá row trước khi đọc
     * nên hai luồng không thể cùng giữ một khoang; khoang đã bị người khác cọc hoặc chuyển bảo trì
     * thì không đổi gì.
     *
     * @return {@code true} nếu khoang thực sự chuyển sang {@code Reserved} — caller ghi AuditLog
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean reserveIfAvailable(Long unitId) {
        return storageUnitRepository.findByIdForUpdate(unitId)
                .map(StorageUnit::reserveIfAvailable)
                .orElse(false);
    }

    /** Như {@link #reserveIfAvailable(Long)} và ghi đơn giữ khoang (V30) — chỉ đơn này mới nhả được khoang. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean reserveIfAvailable(Long unitId, Long orderId) {
        return storageUnitRepository.findByIdForUpdate(unitId)
                .map(unit -> unit.reserveIfAvailable(orderId))
                .orElse(false);
    }

    /** Spec 2.4: khoang {@code Reserved -> Rented}, khoá theo {@code unit_id}. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean rentIfReserved(Long unitId) {
        return storageUnitRepository.findByIdForUpdate(unitId)
                .map(StorageUnit::rentIfReserved)
                .orElse(false);
    }

    /** Nhả khoang {@code Reserved -> Available} khi đúng đơn {@code orderId} đang giữ nó (V30). */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean releaseIfReserved(Long unitId, Long orderId) {
        return storageUnitRepository.findByIdForUpdate(unitId)
                .map(unit -> unit.releaseIfReservedBy(orderId))
                .orElse(false);
    }

    /** Trả kho hoàn tất: {@code Rented -> Maintenance} + {@code maintenance_started_at}. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean startMaintenanceAfterReturn(Long unitId) {
        return storageUnitRepository.findByIdForUpdate(unitId)
                .map(unit -> unit.startMaintenanceAfterReturn(java.time.Instant.now()))
                .orElse(false);
    }

    /**
     * Trả kho hoàn tất khi khách mất chìa: {@code Rented -> Maintenance} không ghi {@code maintenance_started_at} —
     * bảo trì thủ công tới khi thay khoá, cron không tự mở lại.
     *
     * @return {@code true} nếu trạng thái khoang thực sự đổi — caller ghi AuditLog cho khoang
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean startManualMaintenanceAfterReturn(Long unitId) {
        return storageUnitRepository.findByIdForUpdate(unitId)
                .map(unit -> unit.startManualMaintenanceAfterReturn(java.time.Instant.now()))
                .orElse(false);
    }

    @Transactional(readOnly = true)
    public java.util.List<Long> maintenanceDueIds(java.time.Instant cutoff) {
        return storageUnitRepository.findMaintenanceDueIds(cutoff);
    }

    /** Cron: mở lại một khoang nếu đã đủ ngày bảo trì; {@code true} khi thực sự đổi. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean finishMaintenanceIfDue(Long unitId, java.time.Instant cutoff) {
        return storageUnitRepository.findByIdForUpdate(unitId)
                .map(unit -> unit.finishMaintenanceIfDue(cutoff))
                .orElse(false);
    }
}
