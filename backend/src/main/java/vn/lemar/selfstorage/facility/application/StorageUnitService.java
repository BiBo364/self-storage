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
     * Cancellation Cascade: giải phóng khoang {@code Reserved -> Available}. Khoang ở trạng thái khác (chưa
     * cọc nên vẫn {@code Available}, hoặc {@code Maintenance}) giữ nguyên. Chạy trong transaction của caller.
     *
     * @return {@code true} nếu trạng thái khoang thực sự đổi — caller ghi AuditLog cho khoang
     */
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

    /** Nhả khoang {@code Reserved -> Available} khi đúng đơn {@code orderId} đang giữ nó (V25). */
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
