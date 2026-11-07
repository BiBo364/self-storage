-- ============================================================================
-- Dữ liệu mẫu kho đang thuê (gia hạn, trả kho, quá hạn) — CHẠY TAY trên DB local.
--
-- KHÔNG phải migration: Flyway không đọc thư mục này. Bình thường hợp đồng Active sinh ra khi bàn giao xong;
-- file này dựng sẵn hợp đồng để thử thẳng /api/customer/rentals, trả kho và cron quá hạn.
--
-- Chạy (backend đã chạy Flyway ít nhất tới V20 trên DB self_storage):
--   Get-Content backend\sql\dev\rental-sample.sql -Raw |
--     docker compose exec -T postgres psql -U self_storage -d self_storage
--
-- Khách = customer1@lemar.vn, FM = fm1@lemar.vn, FS = fs1@lemar.vn (Test@1234), cơ sở Q7. Giá 2.000đ/tháng,
-- cọc 2.000đ để thanh toán thật qua payOS cũng rẻ. Mỗi lần chạy tạo khoang mới (RNT-<giờ>-X).
--
--   T1  còn 2 tháng             → gia hạn / gửi yêu cầu trả kho → FM phân công → FS trả kho
--   T2  còn 3 ngày              → cron nhắc CONTRACT_EXPIRING_SOON
--   T3  quá hạn 10 ngày         → cron phí quá hạn: (10 − 3) × 20.000đ trên hoá đơn INV-OVD
-- Cron Flow 6 chạy 150 giây sau khi app khởi động, sau đó mỗi giờ.
-- ============================================================================

BEGIN;

CREATE TEMP TABLE rnt_ctx ON COMMIT DROP AS
SELECT (SELECT id FROM facilities WHERE code = 'Q7')                AS facility_id,
       (SELECT id FROM unit_types WHERE name = 'Small')             AS small_type,
       (SELECT id FROM accounts WHERE email = 'customer1@lemar.vn') AS customer_id,
       'RNT-' || to_char(clock_timestamp(), 'HH24MISS')             AS tag;

CREATE TEMP TABLE rnt_case ON COMMIT DROP AS
SELECT * FROM (VALUES
    ('T1', current_date + 60),
    ('T2', current_date + 3),
    ('T3', current_date - 10)
) AS t(label, end_date);

INSERT INTO storage_units (code, facility_id, unit_type_id, status, monthly_price)
SELECT ctx.tag || '-' || c.label, ctx.facility_id, ctx.small_type, 'Rented', 2000
FROM rnt_case c CROSS JOIN rnt_ctx ctx;

CREATE TEMP TABLE rnt_row ON COMMIT DROP AS
SELECT c.label, c.end_date, u.id AS unit_id,
       nextval(pg_get_serial_sequence('rental_requests', 'id')) AS request_id,
       nextval(pg_get_serial_sequence('rental_orders', 'id'))   AS order_id,
       nextval(pg_get_serial_sequence('rental_contracts', 'id')) AS contract_id
FROM rnt_case c
CROSS JOIN rnt_ctx ctx
JOIN storage_units u ON u.code = ctx.tag || '-' || c.label AND u.facility_id = ctx.facility_id;

INSERT INTO rental_requests (id, facility_id, customer_name, normalized_customer_email, customer_phone,
                             unit_type_id, start_date, period, unit_id, status, responded_at)
SELECT r.request_id, ctx.facility_id, 'Khách Demo', 'customer1@lemar.vn', '0900000000',
       ctx.small_type, r.end_date - interval '3 months', 3, r.unit_id, 'Converted', now()
FROM rnt_row r CROSS JOIN rnt_ctx ctx;

INSERT INTO rental_orders (id, request_id, customer_id, unit_id, status, expires_at)
SELECT r.order_id, r.request_id, ctx.customer_id, r.unit_id, 'Done', now()
FROM rnt_row r CROSS JOIN rnt_ctx ctx;

INSERT INTO rental_contracts (id, order_id, customer_id, unit_id, code, terms_version, monthly_price,
                              deposit_amount, period, start_date, end_date, signed_at, status)
SELECT r.contract_id, r.order_id, ctx.customer_id, r.unit_id, 'HD-Q7-' || ctx.tag || '-' || r.label,
       'builtin-v1', 2000, 2000, 3, (r.end_date - interval '3 months')::date, r.end_date, now(), 'Active'
FROM rnt_row r CROSS JOIN rnt_ctx ctx;

INSERT INTO unit_access_keys (unit_id, contract_id, access_type, quantity)
SELECT r.unit_id, r.contract_id, 'PhysicalKey', 2 FROM rnt_row r;

-- Kết quả: contract_id dùng cho /api/customer/rentals/{contractId}
SELECT r.label AS kich_ban, r.contract_id, r.order_id, u.code AS khoang, r.end_date
FROM rnt_row r JOIN storage_units u ON u.id = r.unit_id
ORDER BY r.label;

COMMIT;
