-- Flow 7 — khoang hư giữa kỳ thuê: FS không sửa ngay được thì đề xuất chuyển khoang, FM điều một khoang trống (ưu tiên
-- cùng loại để cùng giá), FS hoàn tất chuyển. Hợp đồng giữ nguyên (giá, cọc, kỳ hạn) — chỉ đổi khoang; chìa / mã khoang
-- cũ thu hồi, cấp lại trên khoang mới; khoang cũ chuyển bảo trì.

-- Yêu cầu hỗ trợ đang chờ chuyển khoang.
ALTER TABLE support_requests DROP CONSTRAINT support_requests_status_check;
ALTER TABLE support_requests ADD CONSTRAINT support_requests_status_check
    CHECK (status IN ('Open', 'Assigned', 'Transferring', 'Resolved', 'Canceled'));

CREATE TABLE unit_transfers (
    id                 BIGSERIAL PRIMARY KEY,
    support_request_id BIGINT       NOT NULL REFERENCES support_requests (id),
    contract_id        BIGINT       NOT NULL REFERENCES rental_contracts (id),
    from_unit_id       BIGINT       NOT NULL REFERENCES storage_units (id),
    -- NULL tới khi FM điều khoang.
    to_unit_id         BIGINT REFERENCES storage_units (id),
    status             VARCHAR(20)  NOT NULL DEFAULT 'Requested'
                       CHECK (status IN ('Requested', 'Allocated', 'Completed', 'Canceled')),
    reason             VARCHAR(1000) NOT NULL,
    requested_by       BIGINT       NOT NULL REFERENCES accounts (id),
    allocated_by       BIGINT REFERENCES accounts (id),
    allocated_at       TIMESTAMPTZ,
    completed_by       BIGINT REFERENCES accounts (id),
    completed_at       TIMESTAMPTZ,
    -- Số chìa cơ giao cho khoang mới (NULL khi hợp đồng không dùng chìa cơ).
    key_quantity       INT CHECK (key_quantity IS NULL OR key_quantity BETWEEN 1 AND 10),
    canceled_by        BIGINT REFERENCES accounts (id),
    canceled_at        TIMESTAMPTZ,
    cancel_reason      VARCHAR(500),
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Mỗi hợp đồng tối đa một lần chuyển khoang đang mở.
CREATE UNIQUE INDEX uq_unit_transfers_open_per_contract ON unit_transfers (contract_id)
    WHERE status IN ('Requested', 'Allocated');
CREATE INDEX idx_unit_transfers_request ON unit_transfers (support_request_id);

-- Audit: copy CHECK hiện tại (V35) + action / entity type của chuyển khoang.
ALTER TABLE audit_logs DROP CONSTRAINT audit_logs_action_check;

ALTER TABLE audit_logs ADD CONSTRAINT audit_logs_action_check
    CHECK (action IN (
        'RENTAL_REQUEST_APPROVED', 'RENTAL_REQUEST_REJECTED', 'RENTAL_REQUEST_CLAIMED',
        'RENTAL_REQUEST_CONVERTED', 'RENTAL_REQUEST_EXPIRED',
        'PROPOSAL_AGREED', 'PROPOSAL_REJECTED', 'PROPOSAL_REPROPOSED', 'PROPOSAL_CREATED',
        'PROPOSAL_CANCELED',
        'INVOICE_CREATED', 'INVOICE_CANCELED', 'INVOICE_PAID', 'INVOICE_EXPIRED', 'INVOICE_REFUND_REQUIRED',
        'PAYMENT_SUCCEEDED', 'PAYMENT_FAILED', 'PAYMENT_DUPLICATE', 'MANUAL_REFUND_RECORDED',
        'DEPOSIT_REFUND_REQUIRED',
        'UNIT_CHANGE_RELEASED',
        'RENTAL_ORDER_DEPOSITED', 'RENTAL_ORDER_CREATED', 'STORAGE_UNIT_RESERVED',
        'APPOINTMENT_CREATED', 'FS_ASSIGNED', 'APPOINTMENT_RESCHEDULED',
        'APPOINTMENT_CANCELED_NO_SHOW', 'APPOINTMENT_CANCELED', 'APPOINTMENT_ARRIVED',
        'IDENTITY_VERIFIED', 'UNIT_INSPECTED', 'HANDOVER_REJECTED',
        'RENTAL_ORDER_CANCELED', 'HANDOVER_COMPLETED',
        'START_DATE_OVERRIDE_REQUESTED', 'START_DATE_OVERRIDE_APPROVED',
        'START_DATE_OVERRIDE_REJECTED',
        'CONTRACT_SIGNED', 'CONTRACT_ACTIVATED', 'CONTRACT_CANCELED', 'CONTRACT_ENDED',
        'CONTRACT_EXTENDED',
        'HANDOVER_CANCELED', 'ACCESS_KEY_ISSUED', 'ACCESS_KEY_REVOKED',
        'STORAGE_UNIT_RENTED', 'STORAGE_UNIT_MAINTENANCE', 'STORAGE_UNIT_AVAILABLE',
        'RENTAL_ORDER_DONE', 'HANDOVER_RECORD_CREATED',
        'RETURN_REQUESTED', 'RETURN_ASSIGNED', 'RETURN_CANCELED', 'RETURN_COMPLETED',
        'CHECKOUT_INSPECTED', 'CHECKOUT_COMPLETED',
        'OVERDUE_FEE_ACCRUED',
        'SUPPORT_REQUEST_CREATED', 'SUPPORT_REQUEST_ASSIGNED', 'SUPPORT_REQUEST_RESOLVED',
        'SUPPORT_REQUEST_CANCELED',
        'FACILITY_CREATED', 'FACILITY_UPDATED', 'STORAGE_UNIT_CREATED', 'STORAGE_UNIT_UPDATED',
        'POLICY_UPDATED', 'UNIT_TYPE_UPDATED',
        'ACCOUNT_CREATED', 'ACCOUNT_UPDATED', 'ROLE_PERMISSIONS_UPDATED',
        'ACCOUNT_REGISTERED',
        'INSPECTION_CORRECTED', 'CONTRACT_ACCESS_SUSPENDED', 'CONTRACT_ACCESS_RESTORED',
        'RETURN_NO_SHOW',
        'STAFF_SHIFT_CREATED', 'STAFF_SHIFT_UPDATED', 'STAFF_SHIFT_DELETED',
        'STAFF_LEAVE_REQUESTED', 'STAFF_LEAVE_APPROVED', 'STAFF_LEAVE_REJECTED', 'STAFF_LEAVE_CANCELED',
        'STAFF_PROFILE_UPDATED',
        'ACCOUNT_REQUEST_CREATED', 'ACCOUNT_REQUEST_APPROVED', 'ACCOUNT_REQUEST_REJECTED', 'ACCOUNT_REQUEST_CANCELED',
        'UNIT_TRANSFER_REQUESTED', 'UNIT_TRANSFER_ALLOCATED', 'UNIT_TRANSFER_COMPLETED', 'UNIT_TRANSFER_CANCELED',
        'CONTRACT_UNIT_CHANGED'
    ));

ALTER TABLE audit_logs DROP CONSTRAINT audit_logs_entity_type_check;

ALTER TABLE audit_logs ADD CONSTRAINT audit_logs_entity_type_check
    CHECK (entity_type IN (
        'RentalRequest', 'ProposalFeedback', 'Invoice', 'PaymentTransaction',
        'RentalOrder', 'StorageUnit', 'Appointment', 'HandoverRecord',
        'RentalContract', 'UnitAccessKey', 'CheckoutRecord', 'Account', 'ReturnRequest',
        'SupportRequest', 'Facility', 'Policy', 'UnitType', 'Role',
        'StaffShift', 'StaffLeave', 'StaffProfile', 'AccountRequest', 'UnitTransfer'
    ));
