-- ============================================================================
-- Dữ liệu mẫu bàn giao kho (Flow 2.2–2.5) — CHẠY TAY trên DB local.
--
-- KHÔNG phải migration: Flyway không đọc thư mục này. Bình thường phiên bàn giao sinh ra khi khách trả cọc → đặt
-- lịch → FM phân công FS → FS bấm "khách đến"; file này dựng sẵn tới bước đó để thử thẳng API bàn giao.
--
-- Chạy (backend đã chạy Flyway ít nhất tới V19 trên DB self_storage):
--   Get-Content backend\sql\dev\handover-sample.sql -Raw |
--     docker compose exec -T postgres psql -U self_storage -d self_storage
--
-- Khách = customer1@lemar.vn, FS = fs1@lemar.vn, FM = fm1@lemar.vn (mật khẩu Test@1234), cơ sở Q7.
-- Mỗi lần chạy tạo khoang mới (mã HOV-<giờ>-X), chạy lại bao nhiêu lần cũng được.
-- Giá khoang 2.000đ/tháng để hoá đơn tháng đầu trả thật qua payOS cũng rẻ.
--
--   H1  phiên mới, khách vừa đến      → đi hết luồng: identity → inspection → agree → sign → pay → confirm
--   H2  phiên mới, khách vừa đến      → thử từ chối khoang / khách không ký / huỷ đơn
--   H3  phiên đã quá due_at           → chờ cron 5' (hoặc khởi động lại app) → CANCELED, đơn về Deposited
-- ============================================================================

BEGIN;

CREATE TEMP TABLE hov_ctx ON COMMIT DROP AS
SELECT (SELECT id FROM facilities WHERE code = 'Q7')                AS facility_id,
       (SELECT id FROM unit_types WHERE name = 'Small')             AS small_type,
       (SELECT id FROM accounts WHERE email = 'customer1@lemar.vn') AS customer_id,
       (SELECT id FROM accounts WHERE email = 'fs1@lemar.vn')       AS fs_id,
       'HOV-' || to_char(clock_timestamp(), 'HH24MISS')             AS tag;

CREATE TEMP TABLE hov_case ON COMMIT DROP AS
SELECT * FROM (VALUES
    ('H1', interval '1 day'),
    ('H2', interval '1 day'),
    ('H3', interval '-5 minutes')
) AS t(label, due_in);

INSERT INTO storage_units (code, facility_id, unit_type_id, status, monthly_price)
SELECT ctx.tag || '-' || c.label, ctx.facility_id, ctx.small_type, 'Reserved', 2000
FROM hov_case c CROSS JOIN hov_ctx ctx;

CREATE TEMP TABLE hov_row ON COMMIT DROP AS
SELECT c.label, c.due_in, u.id AS unit_id,
       nextval(pg_get_serial_sequence('rental_requests', 'id')) AS request_id,
       nextval(pg_get_serial_sequence('rental_orders', 'id'))   AS order_id,
       nextval(pg_get_serial_sequence('appointments', 'id'))    AS appointment_id
FROM hov_case c
CROSS JOIN hov_ctx ctx
JOIN storage_units u ON u.code = ctx.tag || '-' || c.label AND u.facility_id = ctx.facility_id;

INSERT INTO rental_requests (id, facility_id, customer_name, normalized_customer_email, customer_phone,
                             unit_type_id, start_date, period, unit_id, status, responded_at)
SELECT r.request_id, ctx.facility_id, 'Khách Demo', 'customer1@lemar.vn', '0900000000',
       ctx.small_type, current_date, 3, r.unit_id, 'Converted', now()
FROM hov_row r CROSS JOIN hov_ctx ctx;

INSERT INTO rental_orders (id, request_id, customer_id, unit_id, status, expires_at)
SELECT r.order_id, r.request_id, ctx.customer_id, r.unit_id, 'InProgress', now() + interval '20 days'
FROM hov_row r CROSS JOIN hov_ctx ctx;

INSERT INTO proposal_feedbacks (order_id, customer_id, unit_id, status)
SELECT r.order_id, ctx.customer_id, r.unit_id, 'Agreed'
FROM hov_row r CROSS JOIN hov_ctx ctx;

INSERT INTO invoices (order_id, customer_id, code, title, description, type, status, amount, due_date)
SELECT r.order_id, ctx.customer_id, 'INV-DEP-Q7-' || ctx.tag || '-' || r.label,
       'Đặt cọc khoang chứa ' || ctx.tag || '-' || r.label, 'Hoá đơn mock — đã thu', 'Deposit', 'Paid', 2000,
       now() + interval '3 days'
FROM hov_row r CROSS JOIN hov_ctx ctx;

INSERT INTO appointments (id, customer_id, facility_id, staff_id, type, date, started_at, end_at, arrived_at,
                          status)
SELECT r.appointment_id, ctx.customer_id, ctx.facility_id, ctx.fs_id, 'CHECKIN', current_date,
       now() - interval '30 minutes', now() + interval '90 minutes', now() - interval '10 minutes', 'Done'
FROM hov_row r CROSS JOIN hov_ctx ctx;

INSERT INTO rental_appointments (appointment_id, order_id)
SELECT r.appointment_id, r.order_id FROM hov_row r;

INSERT INTO handover_records (order_id, appointment_id, unit_id, due_at)
SELECT r.order_id, r.appointment_id, r.unit_id, now() + r.due_in FROM hov_row r;

-- Kết quả: handover_id dùng cho /api/staff/handovers/{id} và /api/customer/handovers/{id}
SELECT r.label AS kich_ban, h.id AS handover_id, r.order_id, u.code AS khoang, h.due_at
FROM hov_row r
JOIN handover_records h ON h.appointment_id = r.appointment_id
JOIN storage_units u ON u.id = r.unit_id
ORDER BY r.label;

COMMIT;
