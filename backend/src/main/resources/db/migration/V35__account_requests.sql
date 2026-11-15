-- Yêu cầu tạo tài khoản nhân sự: BOM gửi yêu cầu tạo tài khoản FM / FS, ADMIN duyệt (đặt mật khẩu ban đầu, tài
-- khoản được tạo và gán cơ sở ngay) hoặc từ chối kèm lý do. Tài khoản BOM do ADMIN tự tạo, không qua yêu cầu.
-- Yêu cầu có thể thay một FM / FS nghỉ việc (replaces_account_id): duyệt xong việc cũ chuyển sang tài khoản mới và tài
-- khoản cũ chuyển trạng thái INACTIVE (đã nghỉ việc — không đăng nhập được, giữ lịch sử).
-- Audit action / entity type mới.

ALTER TABLE accounts DROP CONSTRAINT accounts_status_check;
ALTER TABLE accounts ADD CONSTRAINT accounts_status_check CHECK (status IN ('ACTIVE', 'BANNED', 'INACTIVE'));

CREATE TABLE account_requests (
    id                 BIGSERIAL PRIMARY KEY,
    requested_by       BIGINT       NOT NULL REFERENCES accounts (id),
    role               VARCHAR(20)  NOT NULL CHECK (role IN ('FM', 'FS')),
    email              VARCHAR(255) NOT NULL,
    name               VARCHAR(255) NOT NULL,
    phone              VARCHAR(20),
    -- FS: một hoặc nhiều cơ sở; FM: đúng một cơ sở.
    facility_ids       BIGINT[]     NOT NULL DEFAULT '{}',
    note               VARCHAR(500),
    -- Người nghỉ việc được thay (cùng role); NULL = tuyển thêm.
    replaces_account_id BIGINT REFERENCES accounts (id),
    status             VARCHAR(20)  NOT NULL DEFAULT 'Pending'
                       CHECK (status IN ('Pending', 'Approved', 'Rejected', 'Canceled')),
    reviewed_by        BIGINT REFERENCES accounts (id),
    reviewed_at        TIMESTAMPTZ,
    review_note        VARCHAR(500),
    created_account_id BIGINT REFERENCES accounts (id),
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_account_requests_status_created ON account_requests (status, created_at DESC);
-- Một email chỉ có một yêu cầu đang chờ.
CREATE UNIQUE INDEX uq_account_requests_pending_email ON account_requests (lower(email)) WHERE status = 'Pending';
-- Một người nghỉ việc chỉ có một yêu cầu thay đang chờ.
CREATE UNIQUE INDEX uq_account_requests_pending_replacement ON account_requests (replaces_account_id)
    WHERE status = 'Pending' AND replaces_account_id IS NOT NULL;

-- Audit: copy CHECK hiện tại (V32) + action / entity type của yêu cầu tạo tài khoản.
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
        'ACCOUNT_REQUEST_CREATED', 'ACCOUNT_REQUEST_APPROVED', 'ACCOUNT_REQUEST_REJECTED', 'ACCOUNT_REQUEST_CANCELED'
    ));

ALTER TABLE audit_logs DROP CONSTRAINT audit_logs_entity_type_check;

ALTER TABLE audit_logs ADD CONSTRAINT audit_logs_entity_type_check
    CHECK (entity_type IN (
        'RentalRequest', 'ProposalFeedback', 'Invoice', 'PaymentTransaction',
        'RentalOrder', 'StorageUnit', 'Appointment', 'HandoverRecord',
        'RentalContract', 'UnitAccessKey', 'CheckoutRecord', 'Account', 'ReturnRequest',
        'SupportRequest', 'Facility', 'Policy', 'UnitType', 'Role',
        'StaffShift', 'StaffLeave', 'StaffProfile', 'AccountRequest'
    ));
