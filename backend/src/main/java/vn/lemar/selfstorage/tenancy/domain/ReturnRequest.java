package vn.lemar.selfstorage.tenancy.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Yêu cầu trả kho (V20): {@code Pending} (khách gửi) → {@code Assigned} (FM phân công FS, có lịch {@code RETURN}) →
 * {@code Completed} (trả kho xong) hoặc {@code Canceled}. Mỗi hợp đồng tối đa một yêu cầu đang mở.
 */
@Entity
@Table(name = "return_requests")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReturnRequest {

    public static final String PENDING = "Pending";
    public static final String ASSIGNED = "Assigned";
    public static final String COMPLETED = "Completed";
    public static final String CANCELED = "Canceled";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "contract_id", nullable = false)
    private Long contractId;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "facility_id", nullable = false)
    private Long facilityId;

    @Column(name = "preferred_date", nullable = false)
    private LocalDate preferredDate;

    private String note;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "assigned_staff_id")
    private Long assignedStaffId;

    @Column(name = "appointment_id")
    private Long appointmentId;

    @Column(name = "cancel_reason")
    private String cancelReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "assigned_at")
    private Instant assignedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Số lần khách không đến buổi trả kho (V23). */
    @Column(name = "no_show_count", nullable = false)
    private int noShowCount;

    public static ReturnRequest open(Long contractId, Long customerId, Long facilityId, LocalDate preferredDate,
                                     String note) {
        ReturnRequest request = new ReturnRequest();
        request.contractId = Objects.requireNonNull(contractId);
        request.customerId = Objects.requireNonNull(customerId);
        request.facilityId = Objects.requireNonNull(facilityId);
        request.preferredDate = Objects.requireNonNull(preferredDate);
        request.note = note;
        request.status = PENDING;
        Instant now = Instant.now();
        request.createdAt = now;
        request.updatedAt = now;
        return request;
    }

    public boolean isOpen() {
        return PENDING.equals(status) || ASSIGNED.equals(status);
    }

    /** FM phân công (hoặc phân công lại khi lịch cũ bị huỷ). */
    public void assign(Long staffId, Long appointmentId) {
        if (!isOpen()) {
            throw new IllegalStateException("Yêu cầu trả kho " + id + " đã đóng: " + status);
        }
        this.assignedStaffId = Objects.requireNonNull(staffId);
        this.appointmentId = Objects.requireNonNull(appointmentId);
        this.status = ASSIGNED;
        this.assignedAt = Instant.now();
        this.updatedAt = assignedAt;
    }

    public void cancel(String reason) {
        if (!isOpen()) {
            throw new IllegalStateException("Yêu cầu trả kho " + id + " đã đóng: " + status);
        }
        this.status = CANCELED;
        this.cancelReason = reason;
        this.updatedAt = Instant.now();
    }

    /**
     * Khách không đến buổi trả kho đã phân công: bỏ lịch + FS. Chạm {@code maxNoShows} lần → {@code Canceled} với
     * {@code reason}; chưa chạm → về {@code Pending} chờ FM phân công lại. {@code true} khi yêu cầu bị huỷ.
     */
    public boolean recordNoShow(int maxNoShows, String reason) {
        if (!ASSIGNED.equals(status)) {
            throw new IllegalStateException("Yêu cầu trả kho " + id + " không ở Assigned: " + status);
        }
        this.noShowCount++;
        this.appointmentId = null;
        this.assignedStaffId = null;
        this.updatedAt = Instant.now();
        if (noShowCount >= maxNoShows) {
            this.status = CANCELED;
            this.cancelReason = reason;
            return true;
        }
        this.status = PENDING;
        return false;
    }

    /** Trả kho hoàn tất (checkout). */
    public void complete(Instant now) {
        if (!ASSIGNED.equals(status)) {
            throw new IllegalStateException("Yêu cầu trả kho " + id + " không ở Assigned: " + status);
        }
        this.status = COMPLETED;
        this.completedAt = now;
        this.updatedAt = now;
    }
}
