package vn.lemar.selfstorage.checkout.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Biên bản trả kho (V25). {@code IN_PROGRESS} → kiểm tra → ({@code PENDING_ITEMS} nếu khoang còn đồ, kiểm lại trên
 * cùng bản ghi) → chốt: {@code COMPLETED} hoặc {@code AWAITING_PAYMENT} khi tổng phí vượt tiền cọc.
 */
@Entity
@Table(name = "checkout_records")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CheckoutRecord {

    public static final String IN_PROGRESS = "IN_PROGRESS";
    public static final String PENDING_ITEMS = "PENDING_ITEMS";
    public static final String AWAITING_PAYMENT = "AWAITING_PAYMENT";
    public static final String COMPLETED = "COMPLETED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "return_request_id", nullable = false, unique = true)
    private Long returnRequestId;

    @Column(name = "contract_id", nullable = false)
    private Long contractId;

    @Column(name = "unit_id", nullable = false)
    private Long unitId;

    @Column(name = "appointment_id")
    private Long appointmentId;

    @Column(name = "is_unit_emptied", nullable = false)
    private boolean unitEmptied;

    @Column(name = "unit_emptied_at")
    private Instant unitEmptiedAt;

    @Column(name = "cleaning_required", nullable = false)
    private boolean cleaningRequired;

    @Column(name = "damage_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal damageAmount;

    private String damages;

    @Column(name = "returned_key_quantity")
    private Integer returnedKeyQuantity;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "photos", columnDefinition = "text[]")
    private List<String> photos;

    private String note;

    @Column(name = "deposit_amount", precision = 12, scale = 2)
    private BigDecimal depositAmount;

    @Column(name = "total_charges", precision = 12, scale = 2)
    private BigDecimal totalCharges;

    @Column(name = "refund_amount", precision = 12, scale = 2)
    private BigDecimal refundAmount;

    @Column(name = "shortfall_invoice_id")
    private Long shortfallInvoiceId;

    @Column(nullable = false, length = 20)
    private String result;

    @Column(name = "inspected_at")
    private Instant inspectedAt;

    @Column(name = "access_revoked_at")
    private Instant accessRevokedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static CheckoutRecord open(Long returnRequestId, Long contractId, Long unitId, Long appointmentId) {
        CheckoutRecord record = new CheckoutRecord();
        record.returnRequestId = Objects.requireNonNull(returnRequestId);
        record.contractId = Objects.requireNonNull(contractId);
        record.unitId = Objects.requireNonNull(unitId);
        record.appointmentId = appointmentId;
        record.damageAmount = BigDecimal.ZERO;
        record.result = IN_PROGRESS;
        Instant now = Instant.now();
        record.createdAt = now;
        record.updatedAt = now;
        return record;
    }

    /** FS ghi kết quả kiểm tra; khoang còn đồ → {@code PENDING_ITEMS} (giữ quyền truy cập để khách lấy đồ). */
    public void inspect(boolean emptied, boolean cleaning, BigDecimal damage, String damageNotes, Integer keys,
                        List<String> photoUrls, String fsNote, Instant now) {
        if (!IN_PROGRESS.equals(result) && !PENDING_ITEMS.equals(result)) {
            throw new IllegalStateException("Biên bản trả kho " + id + " đã chốt: " + result);
        }
        this.unitEmptied = emptied;
        this.unitEmptiedAt = emptied ? now : null;
        this.cleaningRequired = cleaning;
        this.damageAmount = damage == null ? BigDecimal.ZERO : damage;
        this.damages = damageNotes;
        this.returnedKeyQuantity = keys;
        this.photos = photoUrls == null ? List.of() : List.copyOf(photoUrls);
        this.note = fsNote;
        this.inspectedAt = now;
        this.result = emptied ? IN_PROGRESS : PENDING_ITEMS;
        this.updatedAt = now;
    }

    public boolean readyToConfirm() {
        return IN_PROGRESS.equals(result) && unitEmptied && inspectedAt != null;
    }

    /** Chốt biên bản: đã thu hồi quyền truy cập, ghi quyết toán. */
    public void settle(BigDecimal deposit, BigDecimal charges, BigDecimal refund, Long shortfallInvoice,
                       Instant now) {
        if (!readyToConfirm()) {
            throw new IllegalStateException("Biên bản trả kho " + id + " chưa sẵn sàng chốt: " + result);
        }
        this.depositAmount = deposit;
        this.totalCharges = charges;
        this.refundAmount = refund;
        this.shortfallInvoiceId = shortfallInvoice;
        this.accessRevokedAt = now;
        this.result = shortfallInvoice == null ? IN_PROGRESS : AWAITING_PAYMENT;
        this.updatedAt = now;
    }

    public void complete(Instant now) {
        if (COMPLETED.equals(result) || PENDING_ITEMS.equals(result) || accessRevokedAt == null) {
            throw new IllegalStateException("Biên bản trả kho " + id + " không hoàn tất được: " + result);
        }
        this.result = COMPLETED;
        this.completedAt = now;
        this.updatedAt = now;
    }
}
