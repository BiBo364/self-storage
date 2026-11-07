package vn.lemar.selfstorage.audit.domain;

import org.springframework.modulith.NamedInterface;

/**
 * Loại entity bị tác động — cột {@code entity_type} lưu {@link #dbValue()} (PascalCase)
 * theo CHECK constraint của bảng {@code audit_logs} (V1).
 *
 * <p>Enum viết HOA theo quy ước Java; giá trị DB giữ đúng tên trong Audit action catalog
 * ({@code specs/db-table-draft.md} › AuditLog), quy đổi qua {@link AuditEntityTypeConverter}.
 */
@NamedInterface("application")
public enum AuditEntityType {
    RENTAL_REQUEST("RentalRequest"),
    PROPOSAL_FEEDBACK("ProposalFeedback"),
    INVOICE("Invoice"),
    PAYMENT_TRANSACTION("PaymentTransaction"),
    RENTAL_ORDER("RentalOrder"),
    STORAGE_UNIT("StorageUnit"),
    APPOINTMENT("Appointment"),
    HANDOVER_RECORD("HandoverRecord"),
    RENTAL_CONTRACT("RentalContract"),
    UNIT_ACCESS_KEY("UnitAccessKey"),
    CHECKOUT_RECORD("CheckoutRecord"),
    ACCOUNT("Account"),
    RETURN_REQUEST("ReturnRequest"),
    SUPPORT_REQUEST("SupportRequest"),
    FACILITY("Facility"),
    POLICY("Policy"),
    UNIT_TYPE("UnitType"),
    ROLE("Role");

    private final String dbValue;

    AuditEntityType(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    public static AuditEntityType fromDbValue(String value) {
        for (AuditEntityType type : values()) {
            if (type.dbValue.equals(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Giá trị entity_type không hợp lệ: " + value);
    }
}
