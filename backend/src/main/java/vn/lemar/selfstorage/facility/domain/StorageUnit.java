package vn.lemar.selfstorage.facility.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Khoang chứa — chỉ map các cột cần để đọc trạng thái, phạm vi cơ sở và trả khoang khi huỷ đơn.
 *
 * <p>Chuyển trạng thái đi qua service, khoá theo {@code unit_id}. Các chuyển khác (đặt cọc, bàn giao,
 * bảo trì) thuộc luồng sau, chưa có method ở đây.
 */
@Entity
@Table(name = "storage_units")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StorageUnit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String code;

    @Column(name = "facility_id", nullable = false)
    private Long facilityId;

    @Column(name = "unit_type_id", nullable = false)
    private Long unitTypeId;

    @Convert(converter = StorageUnitStatusConverter.class)
    @Column(nullable = false, length = 20)
    private StorageUnitStatus status;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Bảo trì sau trả kho (V20); bảo trì FM bật tay để null — cron không tự mở lại. */
    @Column(name = "maintenance_started_at")
    private Instant maintenanceStartedAt;

    /** Đơn đang giữ khoang khi {@code Reserved} (V25); {@code null} ở trạng thái khác hoặc khoang Reserved cũ. */
    @Column(name = "reserved_order_id")
    private Long reservedOrderId;

    /** Cancellation Cascade: khoang đang giữ cho đơn bị huỷ được giải phóng {@code Reserved -> Available}. */
    /** Spec 1.4: cọc thành công → {@code Available -> Reserved}; khoang ở trạng thái khác giữ nguyên. */
    public boolean reserveIfAvailable() {
        return reserveIfAvailable(null);
    }

    /** Như {@link #reserveIfAvailable()} và ghi đơn giữ khoang — chỉ đơn này mới nhả được khoang. */
    public boolean reserveIfAvailable(Long orderId) {
        if (status != StorageUnitStatus.AVAILABLE) {
            return false;
        }
        this.reservedOrderId = orderId;
        this.status = StorageUnitStatus.RESERVED;
        this.updatedAt = Instant.now();
        return true;
    }

    /** Spec 2.4: bàn giao hoàn tất → {@code Reserved -> Rented}. */
    public boolean rentIfReserved() {
        if (status != StorageUnitStatus.RESERVED) {
            return false;
        }
        this.status = StorageUnitStatus.RENTED;
        this.reservedOrderId = null;
        this.updatedAt = Instant.now();
        return true;
    }

    public boolean releaseIfReserved() {
        if (status != StorageUnitStatus.RESERVED) {
            return false;
        }
        this.reservedOrderId = null;
        this.status = StorageUnitStatus.AVAILABLE;
        this.updatedAt = Instant.now();
        return true;
    }

    /**
     * Nhả khoang chỉ khi {@code orderId} là đơn đang giữ nó. Khoang Reserved không ghi đơn giữ (trước V25) nhả theo
     * luật cũ. Đơn trỏ {@code unit_id} tới khoang mà không giữ (khoang đã bị đơn khác cọc trước) → giữ nguyên.
     */
    public boolean releaseIfReservedBy(Long orderId) {
        if (status != StorageUnitStatus.RESERVED
                || (reservedOrderId != null && !reservedOrderId.equals(orderId))) {
            return false;
        }
        return releaseIfReserved();
    }

    /** Trả kho hoàn tất → {@code Rented -> Maintenance}, ghi mốc để cron mở lại sau {@code unit.maintenance_days}. */
    public boolean startMaintenanceAfterReturn(Instant now) {
        if (status != StorageUnitStatus.RENTED) {
            return false;
        }
        this.status = StorageUnitStatus.MAINTENANCE;
        this.maintenanceStartedAt = now;
        this.updatedAt = now;
        return true;
    }

    /**
     * Trả kho hoàn tất nhưng khách mất chìa → {@code Rented -> Maintenance} không ghi {@code maintenance_started_at}
     * (bảo trì thủ công): khoang chờ thay khoá, cron không tự mở lại — FM mở lại tay.
     */
    public boolean startManualMaintenanceAfterReturn(Instant now) {
        if (status != StorageUnitStatus.RENTED) {
            return false;
        }
        this.status = StorageUnitStatus.MAINTENANCE;
        this.maintenanceStartedAt = null;
        this.updatedAt = now;
        return true;
    }

    /** Hết thời gian bảo trì sau trả kho → {@code Maintenance -> Available}. Bảo trì bật tay không đổi. */
    public boolean finishMaintenanceIfDue(Instant cutoff) {
        if (status != StorageUnitStatus.MAINTENANCE || maintenanceStartedAt == null
                || maintenanceStartedAt.isAfter(cutoff)) {
            return false;
        }
        this.status = StorageUnitStatus.AVAILABLE;
        this.maintenanceStartedAt = null;
        this.updatedAt = Instant.now();
        return true;
    }
}
