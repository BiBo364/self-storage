-- Kho đang thuê (gia hạn, yêu cầu trả kho), trả kho rút gọn và phí quá hạn.
-- Spec Flow 3/6 chỉ có tiêu đề, Flow 2.a "chưa qua review" — đây là thiết kế as-built của nhánh full-demo,
-- xem backend/docs/tenancy/rentals.md và backend/docs/checkout/return.md.

CREATE TABLE return_requests (
    id                BIGSERIAL PRIMARY KEY,
    contract_id       BIGINT NOT NULL REFERENCES rental_contracts (id),
    customer_id       BIGINT NOT NULL REFERENCES accounts (id),
    facility_id       BIGINT NOT NULL REFERENCES facilities (id),
    preferred_date    DATE NOT NULL,
    note              TEXT,
    status            VARCHAR(20) NOT NULL DEFAULT 'Pending'
                      CHECK (status IN ('Pending', 'Assigned', 'Completed', 'Canceled')),
    assigned_staff_id BIGINT REFERENCES accounts (id),
    appointment_id    BIGINT REFERENCES appointments (id),
    cancel_reason     TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    assigned_at       TIMESTAMPTZ,
    completed_at      TIMESTAMPTZ,
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Mỗi hợp đồng tối đa một yêu cầu trả kho đang mở.
CREATE UNIQUE INDEX uq_return_requests_open_per_contract
    ON return_requests (contract_id)
    WHERE status IN ('Pending', 'Assigned');

CREATE TABLE checkout_records (
    id                    BIGSERIAL PRIMARY KEY,
    return_request_id     BIGINT NOT NULL UNIQUE REFERENCES return_requests (id),
    contract_id           BIGINT NOT NULL REFERENCES rental_contracts (id),
    unit_id               BIGINT NOT NULL REFERENCES storage_units (id),
    appointment_id        BIGINT REFERENCES appointments (id),
    is_unit_emptied       BOOLEAN NOT NULL DEFAULT false,
    unit_emptied_at       TIMESTAMPTZ,
    cleaning_required     BOOLEAN NOT NULL DEFAULT false,
    damage_amount         NUMERIC(12,2) NOT NULL DEFAULT 0 CHECK (damage_amount >= 0),
    damages               TEXT,
    returned_key_quantity INTEGER CHECK (returned_key_quantity IS NULL OR returned_key_quantity >= 0),
    photos                TEXT[],
    note                  TEXT,
    deposit_amount        NUMERIC(12,2),
    total_charges         NUMERIC(12,2),
    refund_amount         NUMERIC(12,2),
    shortfall_invoice_id  BIGINT REFERENCES invoices (id),
    result                VARCHAR(20) NOT NULL DEFAULT 'IN_PROGRESS'
                          CHECK (result IN ('IN_PROGRESS', 'PENDING_ITEMS', 'AWAITING_PAYMENT', 'COMPLETED')),
    inspected_at          TIMESTAMPTZ,
    access_revoked_at     TIMESTAMPTZ,
    completed_at          TIMESTAMPTZ,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Bảo trì sau trả kho dùng cột storage_units.maintenance_started_at đã có từ V1 (bảo trì FM bật tay để null).

INSERT INTO policies (key, value, description) VALUES
    ('extension.max_months', '12', 'Số tháng gia hạn tối đa mỗi lần'),
    ('extension.invoice_due_days', '3', 'Hạn thanh toán hoá đơn gia hạn (ngày)'),
    ('contract.expiry_reminder_days', '7', 'Nhắc khách khi hợp đồng còn N ngày là hết hạn'),
    ('overdue.grace_days', '3', 'Số ngày ân hạn sau end_date trước khi tính phí quá hạn'),
    ('overdue.fee_per_day', '20000', 'Phí quá hạn mỗi ngày (VND)'),
    ('fee.cleaning', '100000', 'Phí vệ sinh khi trả kho chưa sạch (VND)'),
    ('fee.lost_key', '200000', 'Phí mỗi chìa khóa không trả lại / thay khóa (VND)'),
    ('unit.maintenance_days', '2', 'Số ngày bảo trì sau trả kho trước khi mở lại cho thuê'),
    ('checkout.invoice_due_days', '7', 'Hạn thanh toán phần còn thiếu khi quyết toán trả kho (ngày)')
ON CONFLICT (key) DO NOTHING;

-- Audit: copy CHECK hiện tại (V22) + action mới của Flow 3/6/2.a.
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
        'ACCOUNT_REGISTERED'
    ));

ALTER TABLE audit_logs DROP CONSTRAINT audit_logs_entity_type_check;

ALTER TABLE audit_logs ADD CONSTRAINT audit_logs_entity_type_check
    CHECK (entity_type IN (
        'RentalRequest', 'ProposalFeedback', 'Invoice', 'PaymentTransaction',
        'RentalOrder', 'StorageUnit', 'Appointment', 'HandoverRecord',
        'RentalContract', 'UnitAccessKey', 'CheckoutRecord', 'Account', 'ReturnRequest'
    ));
