-- Doanh thu theo kế toán (mốc huỷ đơn cho cọc bị giữ lại), cửa sổ đặt lịch tính lại sau khi đồng ý khoang thay thế,
-- no-show buổi trả kho.

-- Mốc huỷ đơn: cọc của đơn bị huỷ được ghi nhận doanh thu ("cọc bị giữ lại") vào thời điểm này.
ALTER TABLE rental_orders ADD COLUMN canceled_at TIMESTAMPTZ;

-- Đơn đã huỷ trước V23: lấy mốc từ audit RENTAL_ORDER_CANCELED của đơn; không có audit thì để NULL (báo cáo bỏ qua).
UPDATE rental_orders o
SET canceled_at = (SELECT max(l.created_at) FROM audit_logs l
                   WHERE l.action = 'RENTAL_ORDER_CANCELED' AND l.entity_type = 'RentalOrder'
                     AND l.entity_id = o.id::text)
WHERE o.status = 'Canceled';

-- Lúc khách đồng ý đề xuất khoang: mốc cửa sổ đặt lịch check-in khi đơn đã cọc phải đổi khoang.
ALTER TABLE proposal_feedbacks ADD COLUMN agreed_at TIMESTAMPTZ;

-- Số lần khách không đến buổi trả kho.
ALTER TABLE return_requests ADD COLUMN no_show_count INT NOT NULL DEFAULT 0;

INSERT INTO policies (key, value, description) VALUES
    ('return.no_show_grace_minutes', '120',
     'Lịch trả kho quá giờ kết thúc N phút mà FS chưa xác nhận khách đến → no-show, yêu cầu về chờ phân công lại'),
    ('return.max_no_shows', '2', 'Khách không đến buổi trả kho N lần → huỷ yêu cầu trả kho');

-- Audit: copy CHECK hiện tại (V22) + action mới.
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
        'INSPECTION_CORRECTED', 'CONTRACT_ACCESS_SUSPENDED', 'CONTRACT_ACCESS_RESTORED',
        'RETURN_NO_SHOW'
    ));
