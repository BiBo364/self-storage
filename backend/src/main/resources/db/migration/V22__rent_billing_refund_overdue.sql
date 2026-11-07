-- Thu tiền thuê định kỳ, ghi nhận hoàn tiền thủ công, xử lý quá hạn kéo dài, chốt bản hợp đồng chưa ký,
-- sửa hiện trạng bàn giao sau khi chốt.

-- Policy không có code đọc: hạn giữ đơn sau cọc dùng order.expiry_days.
DELETE FROM policies WHERE key = 'order.deposit_expiry_days';

UPDATE policies
SET description = 'Lần đặt lịch check-in đầu tiên phải trong N ngày kể từ lúc trả cọc (đặt lại sau khi lịch bị huỷ thì '
                  || 'chỉ cần còn trong hạn giữ đơn)'
WHERE key = 'appointment.booking_window_days';

INSERT INTO policies (key, value, description) VALUES
    ('rent.invoice_lead_days', '5', 'Lập hoá đơn tiền thuê tháng kế tiếp trước ngày bắt đầu tháng N ngày'),
    ('overdue.escalation_days', '30',
     'Quá hạn hợp đồng N ngày (tính từ end_date) → tạm khoá mã khoá số và báo FM xử lý thu hồi');

-- Bản hợp đồng chốt lúc ký: in lại hiển thị đúng bản đã ký, không render lại từ dữ liệu hiện tại.
ALTER TABLE rental_contracts ADD COLUMN document_html TEXT;

-- Tạm khoá truy cập do quá hạn kéo dài (mã khoá số đã thu hồi; mở lại khi hết quá hạn).
ALTER TABLE rental_contracts ADD COLUMN access_suspended_at TIMESTAMPTZ;

-- Audit: copy CHECK hiện tại (V21) + action mới.
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
        'ACCOUNT_REGISTERED',
        'INSPECTION_CORRECTED', 'CONTRACT_ACCESS_SUSPENDED', 'CONTRACT_ACCESS_RESTORED'
    ));
