-- Hỗ trợ / sự cố (Flow 7) và back-office (Flow 4/5 + quản trị hệ thống). Spec Flow 4–7 chỉ có tiêu đề — đây là
-- thiết kế as-built của nhánh full-demo, xem backend/docs/support/requests.md và backend/docs/backoffice/*.md.

CREATE TABLE support_requests (
    id                BIGSERIAL PRIMARY KEY,
    customer_id       BIGINT NOT NULL REFERENCES accounts (id),
    facility_id       BIGINT NOT NULL REFERENCES facilities (id),
    contract_id       BIGINT REFERENCES rental_contracts (id),
    category          VARCHAR(20) NOT NULL
                      CHECK (category IN ('LOST_KEY', 'ACCESS_CODE', 'UNIT_ISSUE', 'BILLING', 'OTHER')),
    title             VARCHAR(255) NOT NULL,
    description       TEXT NOT NULL,
    photos            TEXT[],
    status            VARCHAR(20) NOT NULL DEFAULT 'Open'
                      CHECK (status IN ('Open', 'Assigned', 'Resolved', 'Canceled')),
    assigned_staff_id BIGINT REFERENCES accounts (id),
    resolution        TEXT,
    fee_invoice_id    BIGINT REFERENCES invoices (id),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    assigned_at       TIMESTAMPTZ,
    resolved_at       TIMESTAMPTZ,
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_support_requests_facility_status ON support_requests (facility_id, status);
CREATE INDEX idx_support_requests_customer ON support_requests (customer_id);

-- Lịch sử đăng nhập (Admin theo dõi): mọi lần thử, thành công hay thất bại.
CREATE TABLE login_events (
    id          BIGSERIAL PRIMARY KEY,
    account_id  BIGINT REFERENCES accounts (id),
    email       VARCHAR(255) NOT NULL,
    success     BOOLEAN NOT NULL,
    reason      VARCHAR(50),
    ip_address  VARCHAR(64),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_login_events_created ON login_events (created_at DESC);
CREATE INDEX idx_login_events_account ON login_events (account_id, created_at DESC);

-- Audit: copy CHECK hiện tại (V20) + action hỗ trợ / back-office.
ALTER TABLE audit_logs DROP CONSTRAINT audit_logs_action_check;

ALTER TABLE audit_logs ADD CONSTRAINT audit_logs_action_check
    CHECK (action IN (
        'RENTAL_REQUEST_APPROVED', 'RENTAL_REQUEST_REJECTED', 'RENTAL_REQUEST_CLAIMED',
        'RENTAL_REQUEST_CONVERTED', 'RENTAL_REQUEST_EXPIRED',
        'PROPOSAL_AGREED', 'PROPOSAL_REJECTED', 'PROPOSAL_REPROPOSED', 'PROPOSAL_CREATED',
        'PROPOSAL_CANCELED',
        'INVOICE_CREATED', 'INVOICE_CANCELED', 'INVOICE_PAID', 'INVOICE_EXPIRED',
        'PAYMENT_SUCCEEDED', 'PAYMENT_FAILED', 'MANUAL_REFUND_RECORDED',
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
        'ACCOUNT_REGISTERED'
    ));

ALTER TABLE audit_logs DROP CONSTRAINT audit_logs_entity_type_check;

ALTER TABLE audit_logs ADD CONSTRAINT audit_logs_entity_type_check
    CHECK (entity_type IN (
        'RentalRequest', 'ProposalFeedback', 'Invoice', 'PaymentTransaction',
        'RentalOrder', 'StorageUnit', 'Appointment', 'HandoverRecord',
        'RentalContract', 'UnitAccessKey', 'CheckoutRecord', 'Account', 'ReturnRequest',
        'SupportRequest', 'Facility', 'Policy', 'UnitType', 'Role'
    ));
