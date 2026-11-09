-- ============================================================================
-- Dữ liệu mẫu thanh toán hoá đơn cọc (Flow 1.4) — CHẠY TAY trên DB local.
--
-- KHÔNG phải migration: Flyway không đọc thư mục này. Bình thường hoá đơn cọc sinh ra khi khách đồng ý đề xuất;
-- file này dựng sẵn đơn + proposal Agreed + hoá đơn Unpaid để gọi thẳng POST /api/invoices/{id}/pay.
--
-- Chạy (backend đã chạy Flyway ít nhất tới V18 trên DB self_storage):
--   Get-Content backend\sql\dev\payment-sample.sql -Raw |
--     docker compose exec -T postgres psql -U self_storage -d self_storage
--
-- Khách = customer1@lemar.vn / Test@1234, cơ sở Q7 (FM = fm1@lemar.vn). Mỗi lần chạy tạo khoang mới
-- (mã MOCK-<giờ>-X) nên chạy lại bao nhiêu lần cũng được, không đụng khoang seed A-01…C-01.
-- Số tiền 2.000đ để nếu thử chuyển khoản thật qua payOS thì rẻ (payOS không có sandbox).
--
--   A   khoang Available                 → bấm thanh toán: nhận link payOS (luồng thành công)
--   B1  khoang dùng chung với B2         → thanh toán B1 trước; B2 bấm lúc B1 còn mở phiên → 409 "đang xử lý";
--   B2                                     B1 trả xong → B2 tự bị huỷ, khách nhận PROPOSAL_UNIT_TAKEN
--   C   khoang Maintenance               → bấm thanh toán: hoá đơn Canceled, 409 "không khả dụng"
--   D   đơn đã quá expires_at            → bấm thanh toán: 409 "Đơn thuê kho đã hết hạn"
-- ============================================================================

BEGIN;

CREATE TEMP TABLE mock_ctx ON COMMIT DROP AS
SELECT (SELECT id FROM facilities WHERE code = 'Q7')                AS facility_id,
       (SELECT id FROM unit_types WHERE name = 'Small')             AS small_type,
       (SELECT id FROM accounts WHERE email = 'customer1@lemar.vn') AS customer_id,
       'MOCK-' || to_char(clock_timestamp(), 'HH24MISS')            AS tag;

-- Kịch bản: nhãn, hậu tố khoang (B1/B2 cùng khoang), trạng thái khoang, đơn đã quá hạn?
CREATE TEMP TABLE mock_case ON COMMIT DROP AS
SELECT * FROM (VALUES
    ('A',  'A', 'Available',   false),
    ('B1', 'B', 'Available',   false),
    ('B2', 'B', 'Available',   false),
    ('C',  'C', 'Maintenance', false),
    ('D',  'D', 'Available',   true)
) AS t(label, unit_suffix, unit_status, expired);

INSERT INTO storage_units (code, facility_id, unit_type_id, status, monthly_price)
SELECT DISTINCT ctx.tag || '-' || c.unit_suffix, ctx.facility_id, ctx.small_type, c.unit_status, 2000
FROM mock_case c CROSS JOIN mock_ctx ctx;

CREATE TEMP TABLE mock_order ON COMMIT DROP AS
SELECT c.label, u.id AS unit_id, c.expired
FROM mock_case c
CROSS JOIN mock_ctx ctx
JOIN storage_units u ON u.code = ctx.tag || '-' || c.unit_suffix AND u.facility_id = ctx.facility_id;

ALTER TABLE mock_order ADD COLUMN request_id BIGINT, ADD COLUMN order_id BIGINT;

UPDATE mock_order m SET request_id = r.id
FROM (SELECT o.label, nextval(pg_get_serial_sequence('rental_requests', 'id')) AS id FROM mock_order o) r
WHERE r.label = m.label;

INSERT INTO rental_requests (id, facility_id, customer_name, normalized_customer_email, customer_phone,
                             unit_type_id, start_date, period, unit_id, status, responded_at)
SELECT m.request_id, ctx.facility_id, 'Khách mock ' || m.label, 'customer1@lemar.vn', '0900000000',
       ctx.small_type, current_date + 3, 3, m.unit_id, 'Converted', now()
FROM mock_order m CROSS JOIN mock_ctx ctx;

UPDATE mock_order m SET order_id = o.id
FROM (SELECT o.label, nextval(pg_get_serial_sequence('rental_orders', 'id')) AS id FROM mock_order o) o
WHERE o.label = m.label;

INSERT INTO rental_orders (id, request_id, customer_id, unit_id, status, expires_at)
SELECT m.order_id, m.request_id, ctx.customer_id, m.unit_id, 'Pending',
       CASE WHEN m.expired THEN now() - interval '1 hour' ELSE now() + interval '30 days' END
FROM mock_order m CROSS JOIN mock_ctx ctx;

INSERT INTO proposal_feedbacks (order_id, customer_id, unit_id, status)
SELECT m.order_id, ctx.customer_id, m.unit_id, 'Agreed'
FROM mock_order m CROSS JOIN mock_ctx ctx;

INSERT INTO invoices (order_id, customer_id, code, title, description, type, status, amount, due_date)
SELECT m.order_id, ctx.customer_id,
       'INV-DEP-Q7-' || ctx.tag || '-' || m.label,
       'Đặt cọc khoang chứa ' || u.code,
       'Hoá đơn mock — kịch bản ' || m.label,
       'Deposit', 'Unpaid', 2000, now() + interval '3 days'
FROM mock_order m
CROSS JOIN mock_ctx ctx
JOIN storage_units u ON u.id = m.unit_id;

-- Kết quả: dùng invoice_id để gọi POST /api/invoices/{invoice_id}/pay
SELECT m.label AS kich_ban, i.id AS invoice_id, m.order_id, u.code AS khoang, u.status AS trang_thai_khoang,
       i.amount AS so_tien,
       CASE WHEN m.expired THEN 'đã quá hạn' ELSE 'còn hạn' END AS don
FROM mock_order m
JOIN invoices i ON i.order_id = m.order_id
JOIN storage_units u ON u.id = m.unit_id
ORDER BY m.label;

COMMIT;
