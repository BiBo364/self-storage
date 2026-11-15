package vn.lemar.selfstorage.support.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Chuyển khoang giữa kỳ thuê (V37), gắn một yêu cầu hỗ trợ: FS đề xuất ({@code Requested}) → FM điều khoang trống
 * ({@code Allocated}, điều lại được) → FS hoàn tất ({@code Completed}); FM huỷ khi chưa hoàn tất ({@code Canceled}).
 */
@Entity
@Table(name = "unit_transfers")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UnitTransfer {

    public static final String REQUESTED = "Requested";
    public static final String ALLOCATED = "Allocated";
    public static final String COMPLETED = "Completed";
    public static final String CANCELED = "Canceled";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "support_request_id", nullable = false)
    private Long supportRequestId;

    @Column(name = "contract_id", nullable = false)
    private Long contractId;

    @Column(name = "from_unit_id", nullable = false)
    private Long fromUnitId;

    @Column(name = "to_unit_id")
    private Long toUnitId;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(nullable = false, length = 1000)
    private String reason;

    @Column(name = "requested_by", nullable = false)
    private Long requestedBy;

    @Column(name = "allocated_by")
    private Long allocatedBy;

    @Column(name = "allocated_at")
    private Instant allocatedAt;

    @Column(name = "completed_by")
    private Long completedBy;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "key_quantity")
    private Integer keyQuantity;

    @Column(name = "canceled_by")
    private Long canceledBy;

    @Column(name = "canceled_at")
    private Instant canceledAt;

    @Column(name = "cancel_reason", length = 500)
    private String cancelReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static UnitTransfer request(Long supportRequestId, Long contractId, Long fromUnitId, String reason,
                                       Long requestedBy) {
        UnitTransfer transfer = new UnitTransfer();
        transfer.supportRequestId = Objects.requireNonNull(supportRequestId);
        transfer.contractId = Objects.requireNonNull(contractId);
        transfer.fromUnitId = Objects.requireNonNull(fromUnitId);
        transfer.reason = Objects.requireNonNull(reason);
        transfer.requestedBy = Objects.requireNonNull(requestedBy);
        transfer.status = REQUESTED;
        Instant now = Instant.now();
        transfer.createdAt = now;
        transfer.updatedAt = now;
        return transfer;
    }

    public boolean isOpen() {
        return REQUESTED.equals(status) || ALLOCATED.equals(status);
    }

    /** FM điều (hoặc điều lại) khoang; trả khoang đã điều trước đó (caller nhả giữ chỗ), null nếu chưa có. */
    public Long allocate(Long unitId, Long actorId) {
        requireOpen();
        Long previous = toUnitId;
        this.toUnitId = Objects.requireNonNull(unitId);
        this.status = ALLOCATED;
        this.allocatedBy = actorId;
        this.allocatedAt = Instant.now();
        this.updatedAt = allocatedAt;
        return previous;
    }

    public void complete(Long actorId, Integer keys) {
        if (!ALLOCATED.equals(status)) {
            throw new IllegalStateException("Chuyển khoang " + id + " chưa được điều khoang: " + status);
        }
        this.status = COMPLETED;
        this.completedBy = actorId;
        this.keyQuantity = keys;
        this.completedAt = Instant.now();
        this.updatedAt = completedAt;
    }

    public void cancel(Long actorId, String why) {
        requireOpen();
        this.status = CANCELED;
        this.canceledBy = actorId;
        this.cancelReason = Objects.requireNonNull(why);
        this.canceledAt = Instant.now();
        this.updatedAt = canceledAt;
    }

    private void requireOpen() {
        if (!isOpen()) {
            throw new IllegalStateException("Chuyển khoang " + id + " đã đóng: " + status);
        }
    }
}
