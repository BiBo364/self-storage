package vn.lemar.selfstorage.support.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Yêu cầu hỗ trợ (V21): {@code Open} → {@code Assigned} → {@code Resolved}; khách huỷ khi chưa xử lý xong. */
@Entity
@Table(name = "support_requests")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SupportRequest {

    public static final String OPEN = "Open";
    public static final String ASSIGNED = "Assigned";
    public static final String RESOLVED = "Resolved";
    public static final String CANCELED = "Canceled";
    public static final Set<String> CATEGORIES = Set.of("LOST_KEY", "ACCESS_CODE", "UNIT_ISSUE", "BILLING", "OTHER");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "facility_id", nullable = false)
    private Long facilityId;

    @Column(name = "contract_id")
    private Long contractId;

    @Column(nullable = false, length = 20)
    private String category;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String description;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "photos", columnDefinition = "text[]")
    private List<String> photos;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "assigned_staff_id")
    private Long assignedStaffId;

    private String resolution;

    @Column(name = "fee_invoice_id")
    private Long feeInvoiceId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "assigned_at")
    private Instant assignedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static SupportRequest open(Long customerId, Long facilityId, Long contractId, String category,
                                      String title, String description, List<String> photos) {
        if (!CATEGORIES.contains(category)) {
            throw new IllegalArgumentException("Loại yêu cầu không hợp lệ: " + category);
        }
        SupportRequest request = new SupportRequest();
        request.customerId = Objects.requireNonNull(customerId);
        request.facilityId = Objects.requireNonNull(facilityId);
        request.contractId = contractId;
        request.category = category;
        request.title = Objects.requireNonNull(title);
        request.description = Objects.requireNonNull(description);
        request.photos = photos == null ? List.of() : List.copyOf(photos);
        request.status = OPEN;
        Instant now = Instant.now();
        request.createdAt = now;
        request.updatedAt = now;
        return request;
    }

    public boolean isActive() {
        return OPEN.equals(status) || ASSIGNED.equals(status);
    }

    /** FM giao (hoặc giao lại) cho FS. */
    public void assign(Long staffId) {
        requireActive();
        this.assignedStaffId = Objects.requireNonNull(staffId);
        this.status = ASSIGNED;
        this.assignedAt = Instant.now();
        this.updatedAt = assignedAt;
    }

    public void resolve(String resolutionNote, Long feeInvoice) {
        requireActive();
        this.resolution = Objects.requireNonNull(resolutionNote);
        this.feeInvoiceId = feeInvoice;
        this.status = RESOLVED;
        this.resolvedAt = Instant.now();
        this.updatedAt = resolvedAt;
    }

    public void cancel() {
        requireActive();
        this.status = CANCELED;
        this.updatedAt = Instant.now();
    }

    private void requireActive() {
        if (!isActive()) {
            throw new IllegalStateException("Yêu cầu hỗ trợ " + id + " đã đóng: " + status);
        }
    }
}
