package vn.lemar.selfstorage.handover.domain;

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
 * Quyền truy cập khoang đã giao cho hợp đồng ({@code specs/db-table-draft.md} › UnitAccessKey). Khóa cơ ghi số chìa;
 * mã số chỉ lưu hash (CHECK V1: {@code AccessCode} ⇔ có {@code code_hash}). Mỗi hợp đồng tối đa một dòng
 * {@code Active} mỗi loại (partial unique index V1).
 */
@Entity
@Table(name = "unit_access_keys")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UnitAccessKey {

    public static final String PHYSICAL_KEY = "PhysicalKey";
    public static final String ACCESS_CODE = "AccessCode";
    public static final String ACTIVE = "Active";
    public static final String REVOKED = "Revoked";
    public static final String LOST = "Lost";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "unit_id", nullable = false)
    private Long unitId;

    @Column(name = "contract_id", nullable = false)
    private Long contractId;

    @Column(name = "access_type", nullable = false, length = 20)
    private String accessType;

    private Integer quantity;

    @Column(name = "code_hash")
    private String codeHash;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(nullable = false, length = 20)
    private String status;

    public static UnitAccessKey physicalKey(Long unitId, Long contractId, int quantity, Instant now) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Số chìa phải > 0");
        }
        UnitAccessKey key = base(unitId, contractId, PHYSICAL_KEY, now);
        key.quantity = quantity;
        return key;
    }

    public static UnitAccessKey accessCode(Long unitId, Long contractId, String codeHash, Instant now) {
        UnitAccessKey key = base(unitId, contractId, ACCESS_CODE, now);
        key.codeHash = Objects.requireNonNull(codeHash, "codeHash");
        return key;
    }

    /** Thu hồi (cấp lại mã mới, trả kho…). */
    public void revoke(Instant now) {
        if (!ACTIVE.equals(status)) {
            throw new IllegalStateException("UnitAccessKey " + id + " không còn Active: " + status);
        }
        this.status = REVOKED;
        this.revokedAt = now;
    }

    /** Trả kho thiếu chìa: {@code Active -> Lost} (CHECK V1: chỉ khóa cơ). */
    public void markLost(Instant now) {
        if (!ACTIVE.equals(status) || !PHYSICAL_KEY.equals(accessType)) {
            throw new IllegalStateException("UnitAccessKey " + id + " không chuyển Lost được");
        }
        this.status = LOST;
        this.revokedAt = now;
    }

    private static UnitAccessKey base(Long unitId, Long contractId, String type, Instant now) {
        UnitAccessKey key = new UnitAccessKey();
        key.unitId = Objects.requireNonNull(unitId, "unitId");
        key.contractId = Objects.requireNonNull(contractId, "contractId");
        key.accessType = type;
        key.issuedAt = now;
        key.status = ACTIVE;
        return key;
    }
}
