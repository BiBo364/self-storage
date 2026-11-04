package vn.lemar.selfstorage.booking.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Lịch hẹn tại cơ sở ({@code specs/db-table-draft.md} › Appointment). Flow 1 dùng cho lịch check-in sau khi khách
 * đặt cọc; {@code customerId}, {@code facilityId}, {@code staffId} để kiểu {@code Long} vì bảng đích thuộc module
 * khác — khoá ngoại do DB giữ.
 */
@Entity
@Table(name = "appointments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Appointment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "facility_id", nullable = false)
    private Long facilityId;

    /** FS phụ trách; {@code null} khi FM chưa phân công. */
    @Column(name = "staff_id")
    private Long staffId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AppointmentType type;

    @Column(nullable = false)
    private LocalDate date;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "end_at")
    private Instant endAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AppointmentStatus status;

    @Column(name = "cancel_reason")
    private String cancelReason;

    @Column(name = "arrived_at")
    private Instant arrivedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Đã có FS phụ trách. */
    public boolean hasStaff() {
        return staffId != null;
    }

    /**
     * Spec 1.5: FM chỉ định FS phụ trách ca tiếp đón. Chỉ lịch {@code Pending} chưa có FS — đổi FS đã gán không
     * cho ở MVP (đã có FS → 409).
     */
    public void assignStaff(Long staffAccountId) {
        if (status != AppointmentStatus.Pending) {
            throw new IllegalStateException("Lịch hẹn " + id + " không còn Pending: " + status);
        }
        if (staffId != null) {
            throw new IllegalStateException("Lịch hẹn " + id + " đã có FS phụ trách");
        }
        this.staffId = staffAccountId;
        this.updatedAt = Instant.now();
    }

    /** Lịch check-in Pending đã quá end_at — guard dùng chung cron no-show và action FS. */
    public boolean isNoShowDue(Instant now) {
        return AppointmentNoShowPolicy.isDue(type, status, endAt, now);
    }

    /** Flow 2.1: FS xác nhận khách đã đến; lịch chỉ đổi từ {@code Pending}. */
    public void arrive() {
        if (status != AppointmentStatus.Pending) {
            throw new IllegalStateException("Lịch hẹn " + id + " không còn Pending: " + status);
        }
        this.status = AppointmentStatus.Done;
        this.arrivedAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    /** Cancellation Cascade / FS chủ động: lịch {@code Pending} chưa diễn ra bị huỷ, ghi lý do. */
    public void cancel(String reason) {
        if (status != AppointmentStatus.Pending) {
            throw new IllegalStateException("Lịch hẹn " + id + " không còn Pending: " + status);
        }
        this.status = AppointmentStatus.Canceled;
        this.cancelReason = reason;
        this.updatedAt = Instant.now();
    }
}
