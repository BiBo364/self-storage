-- Nhân sự (Flow 5): ca làm việc của FS, nghỉ phép, hồ sơ nhân viên; policy bắt buộc ca khi phân công; permission mới;
-- audit action / entity type mới.

-- Ca làm việc: một FS tại một cơ sở trong một ngày, giờ địa phương (Asia/Ho_Chi_Minh). Không chồng giờ với ca khác
-- của cùng FS (mọi cơ sở) — kiểm ở service dưới khoá advisory theo FS.
CREATE TABLE staff_shifts (
    id          BIGSERIAL PRIMARY KEY,
    facility_id BIGINT NOT NULL REFERENCES facilities (id),
    staff_id    BIGINT NOT NULL REFERENCES accounts (id),
    shift_date  DATE NOT NULL,
    start_time  TIME NOT NULL,
    end_time    TIME NOT NULL,
    note        VARCHAR(500),
    created_by  BIGINT REFERENCES accounts (id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT staff_shifts_time_check CHECK (start_time < end_time)
);

CREATE INDEX idx_staff_shifts_staff_date ON staff_shifts (staff_id, shift_date);
CREATE INDEX idx_staff_shifts_facility_date ON staff_shifts (facility_id, shift_date);

-- Đơn nghỉ phép của FS (cả ngày, from_date..to_date tính cả hai đầu).
CREATE TABLE staff_leaves (
    id          BIGSERIAL PRIMARY KEY,
    staff_id    BIGINT NOT NULL REFERENCES accounts (id),
    from_date   DATE NOT NULL,
    to_date     DATE NOT NULL,
    reason      VARCHAR(500) NOT NULL,
    status      VARCHAR(20) NOT NULL DEFAULT 'Pending'
                CHECK (status IN ('Pending', 'Approved', 'Rejected', 'Canceled')),
    reviewed_by BIGINT REFERENCES accounts (id),
    reviewed_at TIMESTAMPTZ,
    review_note VARCHAR(500),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT staff_leaves_range_check CHECK (from_date <= to_date)
);

CREATE INDEX idx_staff_leaves_staff_dates ON staff_leaves (staff_id, from_date, to_date);
CREATE INDEX idx_staff_leaves_status ON staff_leaves (status);

-- Hồ sơ nhân viên (FS / FM). id_number chỉ ADMIN / BOM đọc được.
CREATE TABLE staff_profiles (
    account_id              BIGINT PRIMARY KEY REFERENCES accounts (id),
    position                VARCHAR(100),
    hire_date               DATE,
    id_number               VARCHAR(20),
    emergency_contact_name  VARCHAR(100),
    emergency_contact_phone VARCHAR(20),
    note                    VARCHAR(1000),
    updated_by              BIGINT REFERENCES accounts (id),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO policies (key, value, description) VALUES
    ('staff.shift_required', 'true',
     'true: phân công FS (check-in, trả kho, hỗ trợ) bắt buộc FS có ca làm việc tại cơ sở bao trọn giờ hẹn')
ON CONFLICT (key) DO NOTHING;

-- Ca mẫu cho demo: mỗi FS đang hoạt động một ca 07:00–21:00 mỗi ngày từ 7 ngày trước tới 60 ngày sau. FS gán nhiều
-- cơ sở thì luân phiên cơ sở theo ngày — hai ca cùng giờ ở hai cơ sở sẽ chồng nhau.
INSERT INTO staff_shifts (facility_id, staff_id, shift_date, start_time, end_time, note)
SELECT s.facility_id, s.account_id, d.day, TIME '07:00', TIME '21:00', 'Ca mẫu'
FROM (
    SELECT f.account_id, f.facility_id,
           row_number() OVER (PARTITION BY f.account_id ORDER BY f.facility_id) - 1 AS idx,
           count(*) OVER (PARTITION BY f.account_id) AS total
    FROM account_facility_assignments f
    JOIN accounts a ON a.id = f.account_id AND a.status = 'ACTIVE'
    JOIN roles r ON r.id = a.role_id AND r.name = 'FS'
) s
CROSS JOIN (
    SELECT g::date AS day, row_number() OVER (ORDER BY g) - 1 AS n
    FROM generate_series(CURRENT_DATE - 7, CURRENT_DATE + 60, INTERVAL '1 day') g
) d
WHERE d.n % s.total = s.idx;

INSERT INTO permissions (code, description) VALUES
    ('staff_shift.manage',   'Quản lý ca làm việc của FS tại cơ sở'),
    ('staff_leave.review',   'Duyệt / từ chối đơn nghỉ phép của FS'),
    ('staff_profile.read',   'Xem hồ sơ nhân viên'),
    ('staff_profile.manage', 'Sửa hồ sơ nhân viên (gồm số giấy tờ tuỳ thân)')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id, scope)
SELECT r.id, p.id, mapping.scope
FROM (VALUES
    ('FM',    'staff_shift.manage',   'FACILITY'),
    ('FM',    'staff_leave.review',   'FACILITY'),
    ('FM',    'staff_profile.read',   'FACILITY'),
    ('BOM',   'staff_profile.read',   'GLOBAL'),
    ('BOM',   'staff_profile.manage', 'GLOBAL'),
    ('ADMIN', 'staff_profile.read',   'GLOBAL'),
    ('ADMIN', 'staff_profile.manage', 'GLOBAL')
) AS mapping (role_name, permission_code, scope)
JOIN roles r ON r.name = mapping.role_name
JOIN permissions p ON p.code = mapping.permission_code
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- Audit: copy CHECK hiện tại (V28) + action / entity type nhân sự.
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
        'STAFF_PROFILE_UPDATED'
    ));

ALTER TABLE audit_logs DROP CONSTRAINT audit_logs_entity_type_check;

ALTER TABLE audit_logs ADD CONSTRAINT audit_logs_entity_type_check
    CHECK (entity_type IN (
        'RentalRequest', 'ProposalFeedback', 'Invoice', 'PaymentTransaction',
        'RentalOrder', 'StorageUnit', 'Appointment', 'HandoverRecord',
        'RentalContract', 'UnitAccessKey', 'CheckoutRecord', 'Account', 'ReturnRequest',
        'SupportRequest', 'Facility', 'Policy', 'UnitType', 'Role',
        'StaffShift', 'StaffLeave', 'StaffProfile'
    ));
