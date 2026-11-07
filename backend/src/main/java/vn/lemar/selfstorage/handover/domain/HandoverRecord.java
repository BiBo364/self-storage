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

    public static final String PENDING = "Pending";
    public static final String VERIFIED = "Verified";
    public static final String FAILED = "Failed";
    public static final String AGREED = "Agreed";
    public static final String REJECTED = "Rejected";

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

    /** Spec 2.4: hình thức nhận quyền truy cập khách chọn ({@link AccessChoice}); null = chưa xác nhận. */
    @Column(name = "requested_access", length = 20)
    private String requestedAccess;

    @Column(name = "customer_confirmed_at")
    private Instant customerConfirmedAt;

    /**
     * Spec 1.5: mở biên bản khi khách đặt lịch check-in — {@code result = IN_PROGRESS}, hai cờ {@code Pending},
     * {@code due_at} chưa có (set khi khách đến).
     */
    public static HandoverRecord open(Long orderId, Long appointmentId, Long unitId) {
        HandoverRecord record = new HandoverRecord();
        record.orderId = Objects.requireNonNull(orderId, "orderId");
        record.appointmentId = Objects.requireNonNull(appointmentId, "appointmentId");
        record.unitId = Objects.requireNonNull(unitId, "unitId");
        record.identityStatus = "Pending";
        record.inspectionStatus = "Pending";
        record.result = HandoverResult.IN_PROGRESS;
        Instant now = Instant.now();
        record.createdAt = now;
        record.updatedAt = now;
        return record;
    }

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

    public boolean isOpen() {
        return result == HandoverResult.IN_PROGRESS;
    }

    public boolean identityVerified() {
        return VERIFIED.equals(identityStatus);
    }

    public boolean inspectionAgreed() {
        return AGREED.equals(inspectionStatus);
    }

    /**
     * Spec 2.2: FS đối chiếu giấy tờ. Đạt → {@code Verified} (chốt, không đổi lại); không đạt → {@code Failed}, phiên
     * vẫn mở để khách quay lại với giấy tờ đúng trong hạn {@code due_at}.
     */
    public void verifyIdentity(boolean passed, Instant now) {
        requireOpen();
        if (identityVerified()) {
            throw new IllegalStateException("Biên bản " + id + " đã xác minh danh tính");
        }
        this.identityStatus = passed ? VERIFIED : FAILED;
        this.identityVerifiedAt = passed ? now : null;
        this.updatedAt = now;
    }

    /** Spec 2.2: FS ghi hiện trạng khoang — sửa được tới khi khách đồng ý/từ chối. */
    public void recordInspection(String notes, List<String> photos, Instant now) {
        requireOpen();
        requireIdentityVerified();
        requireInspectionPending();
        this.inspectionNotes = notes;
        this.inspectionPhotos = photos == null ? List.of() : List.copyOf(photos);
        this.updatedAt = now;
    }

    /** Spec 2.2 — khách đồng ý hiện trạng; ghi chú của khách (không bắt buộc) nối vào {@code inspection_notes}. */
    public void agreeInspection(String customerNote, Instant now) {
        requireOpen();
        requireIdentityVerified();
        requireInspectionPending();
        this.inspectionStatus = AGREED;
        this.unitInspectedAt = now;
        if (customerNote != null && !customerNote.isBlank()) {
            String note = "Khách lưu ý: " + customerNote.trim();
            this.inspectionNotes = inspectionNotes == null || inspectionNotes.isBlank() ? note
                    : inspectionNotes + "\n" + note;
        }
        this.updatedAt = now;
    }

    /** Spec 2.5 — khách từ chối khoang: {@code inspection_status = Rejected}, {@code result = REJECTED}. */
    public void reject(String reason, Instant now) {
        requireOpen();
        this.inspectionStatus = REJECTED;
        this.unitInspectedAt = now;
        this.rejectReason = Objects.requireNonNull(reason, "reason");
        this.result = HandoverResult.REJECTED;
        this.updatedAt = now;
    }

    /** Spec 2.4 — khách xác nhận biên bản và chọn hình thức nhận quyền truy cập. */
    public void confirmByCustomer(AccessChoice choice, Instant now) {
        requireOpen();
        if (!identityVerified() || !inspectionAgreed()) {
            throw new IllegalStateException("Biên bản " + id + " chưa đủ xác minh danh tính + hiện trạng");
        }
        this.requestedAccess = Objects.requireNonNull(choice, "choice").dbValue();
        this.customerConfirmedAt = now;
        this.updatedAt = now;
    }

    public AccessChoice accessChoice() {
        return requestedAccess == null ? null : AccessChoice.fromDbValue(requestedAccess);
    }

    /** Spec 2.4 — chốt bàn giao chỉ một lần: {@code IN_PROGRESS -> COMPLETED}. */
    public void complete(Instant now) {
        requireOpen();
        this.result = HandoverResult.COMPLETED;
        this.completedAt = now;
        this.updatedAt = now;
    }

    private void requireOpen() {
        if (result != HandoverResult.IN_PROGRESS) {
            throw new IllegalStateException("Biên bản " + id + " không còn IN_PROGRESS: " + result);
        }
    }

    private void requireIdentityVerified() {
        if (!identityVerified()) {
            throw new IllegalStateException("Biên bản " + id + " chưa xác minh danh tính");
        }
    }

    private void requireInspectionPending() {
        if (!PENDING.equals(inspectionStatus)) {
            throw new IllegalStateException("Biên bản " + id + " đã chốt hiện trạng: " + inspectionStatus);
        }
    }
}
