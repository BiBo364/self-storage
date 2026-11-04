package vn.lemar.selfstorage.audit.domain;

import java.util.Set;
import org.springframework.modulith.NamedInterface;

/**
 * Audit action catalog — nguồn sự thật: {@code specs/db-table-draft.md} › AuditLog ›
 * Audit action catalog. Tên enum khớp nguyên văn giá trị CHECK của cột {@code audit_logs.action}.
 *
 * <p>Mỗi action hợp lệ với một hoặc nhiều loại entity; thao tác đổi nhiều entity
 * (ví dụ {@link #RENTAL_ORDER_CANCELED}) ghi một bản ghi cho mỗi entity.
 */
@NamedInterface("application")
public enum AuditAction {
    RENTAL_REQUEST_APPROVED(AuditEntityType.RENTAL_REQUEST),
    RENTAL_REQUEST_REJECTED(AuditEntityType.RENTAL_REQUEST),
    RENTAL_REQUEST_CLAIMED(AuditEntityType.RENTAL_REQUEST),
    RENTAL_REQUEST_CONVERTED(AuditEntityType.RENTAL_REQUEST),
    RENTAL_REQUEST_EXPIRED(AuditEntityType.RENTAL_REQUEST),
    PROPOSAL_AGREED(AuditEntityType.PROPOSAL_FEEDBACK),
    PROPOSAL_REJECTED(AuditEntityType.PROPOSAL_FEEDBACK),
    PROPOSAL_REPROPOSED(AuditEntityType.PROPOSAL_FEEDBACK),
    PROPOSAL_CREATED(AuditEntityType.PROPOSAL_FEEDBACK),
    /** Khoang hết Available lúc khách đồng ý → proposal Canceled (V8). */
    PROPOSAL_CANCELED(AuditEntityType.PROPOSAL_FEEDBACK),
    INVOICE_CREATED(AuditEntityType.INVOICE),
    INVOICE_CANCELED(AuditEntityType.INVOICE),
    PAYMENT_SUCCEEDED(AuditEntityType.PAYMENT_TRANSACTION),
    PAYMENT_FAILED(AuditEntityType.PAYMENT_TRANSACTION),
    PAYMENT_DUPLICATE(AuditEntityType.PAYMENT_TRANSACTION),
    MANUAL_REFUND_RECORDED(AuditEntityType.PAYMENT_TRANSACTION),
    /** Đổi khoang sau khi cọc dư — FM phải hoàn tiền thủ công (behavior-gap #165). */
    DEPOSIT_REFUND_REQUIRED(AuditEntityType.RENTAL_ORDER),
    /** Khách đồng ý khoang mới sau khi đã cọc — khoang cũ được nhả (behavior-gap #172). */
    UNIT_CHANGE_RELEASED(AuditEntityType.STORAGE_UNIT),
    RENTAL_ORDER_DEPOSITED(AuditEntityType.RENTAL_ORDER),
    RENTAL_ORDER_CREATED(AuditEntityType.RENTAL_ORDER),
    STORAGE_UNIT_RESERVED(AuditEntityType.STORAGE_UNIT),
    APPOINTMENT_CREATED(AuditEntityType.APPOINTMENT),
    /** Gán FS: một bản ghi cho Appointment (staff) và một cho RentalOrder (Scheduled → InProgress). */
    FS_ASSIGNED(AuditEntityType.APPOINTMENT, AuditEntityType.RENTAL_ORDER),
    APPOINTMENT_RESCHEDULED(AuditEntityType.APPOINTMENT),
    APPOINTMENT_CANCELED_NO_SHOW(AuditEntityType.APPOINTMENT),
    APPOINTMENT_CANCELED(AuditEntityType.APPOINTMENT),
    IDENTITY_VERIFIED(AuditEntityType.HANDOVER_RECORD),
    UNIT_INSPECTED(AuditEntityType.HANDOVER_RECORD),
    HANDOVER_REJECTED(AuditEntityType.HANDOVER_RECORD),
    RENTAL_ORDER_CANCELED(AuditEntityType.RENTAL_ORDER, AuditEntityType.STORAGE_UNIT,
            AuditEntityType.PROPOSAL_FEEDBACK, AuditEntityType.RENTAL_CONTRACT),
    HANDOVER_COMPLETED(AuditEntityType.HANDOVER_RECORD),
    START_DATE_OVERRIDE_REQUESTED(AuditEntityType.RENTAL_CONTRACT),
    START_DATE_OVERRIDE_APPROVED(AuditEntityType.RENTAL_CONTRACT),
    START_DATE_OVERRIDE_REJECTED(AuditEntityType.RENTAL_CONTRACT),
    CONTRACT_SIGNED(AuditEntityType.RENTAL_CONTRACT),
    CONTRACT_ACTIVATED(AuditEntityType.RENTAL_CONTRACT),
    CONTRACT_CANCELED(AuditEntityType.RENTAL_CONTRACT),
    CONTRACT_ENDED(AuditEntityType.RENTAL_CONTRACT),
    HANDOVER_CANCELED(AuditEntityType.HANDOVER_RECORD),
    ACCESS_KEY_ISSUED(AuditEntityType.UNIT_ACCESS_KEY),
    ACCESS_KEY_REVOKED(AuditEntityType.UNIT_ACCESS_KEY),
    STORAGE_UNIT_RENTED(AuditEntityType.STORAGE_UNIT),
    STORAGE_UNIT_MAINTENANCE(AuditEntityType.STORAGE_UNIT),
    STORAGE_UNIT_AVAILABLE(AuditEntityType.STORAGE_UNIT),
    RENTAL_ORDER_DONE(AuditEntityType.RENTAL_ORDER),
    HANDOVER_RECORD_CREATED(AuditEntityType.HANDOVER_RECORD),
    CHECKOUT_INSPECTED(AuditEntityType.CHECKOUT_RECORD),
    CHECKOUT_COMPLETED(AuditEntityType.CHECKOUT_RECORD),
    ACCOUNT_REGISTERED(AuditEntityType.ACCOUNT);

    private final Set<AuditEntityType> entityTypes;

    AuditAction(AuditEntityType... entityTypes) {
        this.entityTypes = Set.of(entityTypes);
    }

    /** Spec: "action phải khớp với loại entity trong Audit action catalog". */
    public boolean allowedFor(AuditEntityType entityType) {
        return entityTypes.contains(entityType);
    }
}
