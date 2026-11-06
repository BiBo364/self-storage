package vn.lemar.selfstorage.handover.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Biên bản bàn giao khoang ({@code specs/db-table-draft.md} › HandoverRecord).
 *
 * <p>Flow 1 tạo record khi lịch check-in được tạo; Flow 2.1 chỉ set {@code due_at} khi khách đến,
 * Flow 2.2 cập nhật xác minh danh tính và hiện trạng khoang. Bảng không có cột {@code arrived_at}:
 * mốc khách đến đọc qua {@code appointments.arrived_at} của {@link #appointmentId}.
 *
 * <p>{@code orderId}, {@code appointmentId}, {@code unitId} để kiểu {@code Long} vì bảng đích
 * thuộc module khác — ranh giới module cấm import {@code domain} của nhau, khoá ngoại vẫn do DB giữ.
 */
@Entity
@Table(name = "handover_records")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class HandoverRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    /** Một lịch hẹn chỉ có tối đa một record (UNIQUE trên V1). */
    @Column(name = "appointment_id", nullable = false, unique = true)
    private Long appointmentId;

    @Column(name = "unit_id", nullable = false)
    private Long unitId;

    /** Pending / Verified / Failed — CHECK V1; enum riêng thêm khi Flow 2.2 có behavior. */
    @Column(name = "identity_status", nullable = false, length = 20)
    private String identityStatus;

    @Column(name = "identity_verified_at")
    private Instant identityVerifiedAt;

    /** Pending / Agreed / Rejected — CHECK V1; enum riêng thêm khi Flow 2.2 có behavior. */
    @Column(name = "inspection_status", nullable = false, length = 20)
    private String inspectionStatus;

    @Column(name = "unit_inspected_at")
    private Instant unitInspectedAt;

    @Column(name = "inspection_notes")
    private String inspectionNotes;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "inspection_photos", columnDefinition = "text[]")
    private List<String> inspectionPhotos;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private HandoverResult result;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "reject_reason")
    private String rejectReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Hạn hoàn tất bàn giao — set một lần khi khách đến; không đổi sau đó. */
    @Column(name = "due_at")
    private Instant dueAt;

    /**
     * Đóng biên bản mà không nhận khoang (khách huỷ, không đến, quá hạn thanh toán/bàn giao).
     * Chỉ record đang {@code IN_PROGRESS} mới huỷ được.
     */
    public void cancel(String reason) {
        if (result != HandoverResult.IN_PROGRESS) {
            throw new IllegalStateException("Biên bản " + id + " không còn IN_PROGRESS: " + result);
        }
        this.result = HandoverResult.CANCELED;
        this.rejectReason = reason;
        this.updatedAt = Instant.now();
    }

    /**
     * Flow 2.1: set hạn bàn giao sau khi khách đến. {@code due_at} chỉ có giá trị một lần —
     * đổi policy sau đó không hồi tố các phiên đã mở.
     */
    public void setDueAt(Instant dueAt) {
        if (result != HandoverResult.IN_PROGRESS) {
            throw new IllegalStateException("Biên bản " + id + " không còn IN_PROGRESS: " + result);
        }
        if (this.dueAt != null) {
            throw new IllegalStateException("Biên bản " + id + " đã có due_at: " + this.dueAt);
        }
        this.dueAt = Objects.requireNonNull(dueAt, "dueAt");
        this.updatedAt = Instant.now();
    }
}
