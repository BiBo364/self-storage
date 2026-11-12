-- ============================================================================
-- Dữ liệu demo: tên thật cho account, 5 cơ sở, nhân viên, khoang + kịch bản quá hạn — CHẠY TAY trên DB local.
--
-- KHÔNG phải migration: Flyway không đọc thư mục này nên test tích hợp và bản deploy không nhận dữ liệu này.
--
-- Chạy từ thư mục gốc repo, sau khi backend đã khởi động ít nhất một lần trên DB (Flyway dựng schema):
--   backend\sql\dev\seed-demo.cmd                       (cmd / PowerShell — tự đợi Flyway xong)
--   docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U self_storage -d self_storage \
--     < backend/sql/dev/demo-seed.sql                   (Git Bash / macOS)
-- Reset rồi nạp lại: docker compose down -v → docker compose up -d → bật backend → chạy lệnh trên.
--
-- Một DB chỉ nạp được một lần (lần hai báo lỗi, không ghi gì). Mọi mốc ngày tính theo ngày chạy script (giờ VN),
-- số tiền theo policy hiện có (mặc định: ân hạn 3 ngày, 20.000đ/ngày, khoá truy cập sau 30 ngày).
-- Mật khẩu mọi account: Test@1234. Thông báo của các kịch bản đã seed sẵn nên cron không gửi lại mail.
--
-- Kịch bản quá hạn — cơ sở Q7, FM fm1 (Lê Minh Tuấn), FS fs1 / fs2:
--   QH0  customer7  A-05  còn 3 ngày hết hạn          → nhắc sắp hết hạn; thử gia hạn trước khi quá hạn
--   QH1  customer2  A-06  hết hạn 2 ngày trước        → đang trong ân hạn, chưa phát sinh phí
--   QH2  customer3  A-07  quá hạn 10 ngày             → INV-OVD 140.000đ Unpaid: thanh toán phí quá hạn
--   QH3  customer4  B-04  quá hạn 35 ngày             → INV-OVD 640.000đ + mã khoá số bị tạm khoá: trả phí và gia
--                                                       hạn → lượt cron sau mở lại truy cập
--   QH4  customer5  B-05  quá hạn 12 ngày, đã gửi yêu cầu trả kho → INV-OVD 180.000đ; FM phân công FS, phí gộp
--                                                       vào quyết toán khi trả kho
--   QH5  customer6  C-02  tiền thuê tháng 2 quá hạn (Expired), tháng 3 Unpaid → FM nhận RENT_UNPAID; khách trả tháng 3
-- Hợp đồng đang thuê bình thường ở các cơ sở khác: customer8–customer13 (sơ đồ có khoang "đã thuê").
--
-- Thanh toán test: đặt PAYMENT_PROVIDER=vnpay trong .env (VNPay sandbox) để không trả tiền thật qua payOS.
-- OverdueJob chạy 150 giây sau khi backend khởi động rồi mỗi giờ; cần lượt cron ngay thì khởi động lại backend.
-- ============================================================================

SET client_encoding = 'UTF8';

BEGIN;

DO $$
BEGIN
    IF to_regclass('flyway_schema_history') IS NULL
       OR NOT EXISTS (SELECT 1 FROM flyway_schema_history WHERE version = '34' AND success) THEN
        RAISE EXCEPTION 'DB chưa chạy migration tới V34 — bật backend một lần cho Flyway dựng schema rồi chạy lại.';
    END IF;
    IF EXISTS (SELECT 1 FROM accounts WHERE email = 'fm3@lemar.vn') THEN
        RAISE EXCEPTION 'DB này đã có dữ liệu demo — reset DB (docker compose down -v) rồi chạy lại.';
    END IF;
END $$;

-- ---------------------------------------------------------------------------- account

-- Account seed V2: đổi tên hiển thị V13 ("FM Quận 7", "Khách Demo"…) sang họ tên đầy đủ, chỉ khi chưa ai sửa tên.
UPDATE accounts a
SET name = v.name, phone = COALESCE(a.phone, v.phone)
FROM (VALUES
    ('admin@lemar.vn',      'Quản trị viên',              'Nguyễn Hoàng Long',  '0903456789'),
    ('bom1@lemar.vn',       'Business Operation Manager', 'Trần Thị Mai Anh',   '0912345678'),
    ('fm1@lemar.vn',        'FM Quận 7',                  'Lê Minh Tuấn',       '0938112233'),
    ('fm2@lemar.vn',        'FM Thủ Đức',                 'Phạm Thu Hà',        '0977223344'),
    ('fs1@lemar.vn',        'FS Quận 7',                  'Võ Quốc Bảo',        '0905667788'),
    ('customer1@lemar.vn',  'Khách Demo',                 'Đặng Thùy Linh',     '0911223344'),
    ('unverified@lemar.vn', 'Khách chưa xác minh',        'Bùi Văn Khoa',       '0988776655'),
    ('banned@lemar.vn',     'Khách bị chặn',              'Hồ Thị Ngọc Trinh',  '0966554433')
) AS v(email, old_name, name, phone)
WHERE a.email = v.email AND (a.name IS NULL OR a.name = v.old_name);

INSERT INTO accounts (email, password_hash, email_verified_at, role_id, status, name, phone)
SELECT v.email, '$2a$10$RSb.3SX6jl/QwQ.O2Lbt1uXgErlw5Gy9o7XHMkxLTiUoDAyuYMpJW', now(), r.id, 'ACTIVE', v.name, v.phone
FROM (VALUES
    ('fm3@lemar.vn',        'FM',       'Ngô Thanh Sơn',          '0934556677'),
    ('fm4@lemar.vn',        'FM',       'Huỳnh Ngọc Trâm',        '0909887766'),
    ('fm5@lemar.vn',        'FM',       'Đỗ Đức Thắng',           '0918223344'),
    ('fs2@lemar.vn',        'FS',       'Trương Gia Huy',         '0907112233'),
    ('fs3@lemar.vn',        'FS',       'Lý Thanh Tâm',           '0936778899'),
    ('fs4@lemar.vn',        'FS',       'Phan Văn Hậu',           '0979334455'),
    ('fs5@lemar.vn',        'FS',       'Nguyễn Ngọc Hân',        '0932445566'),
    ('fs6@lemar.vn',        'FS',       'Đinh Công Phúc',         '0916778899'),
    ('fs7@lemar.vn',        'FS',       'Mai Xuân Trường',        '0903998877'),
    ('fs8@lemar.vn',        'FS',       'Tạ Thị Kim Oanh',        '0939112244'),
    ('fs9@lemar.vn',        'FS',       'Châu Minh Khang',        '0908334466'),
    ('fs10@lemar.vn',       'FS',       'Lâm Bảo Ngọc',           '0919557799'),
    ('customer2@lemar.vn',  'CUSTOMER', 'Nguyễn Thị Lan',         '0902113355'),
    ('customer3@lemar.vn',  'CUSTOMER', 'Trần Văn Hùng',          '0913224466'),
    ('customer4@lemar.vn',  'CUSTOMER', 'Lê Hoàng Nam',           '0935336677'),
    ('customer5@lemar.vn',  'CUSTOMER', 'Phạm Minh Châu',         '0976447788'),
    ('customer6@lemar.vn',  'CUSTOMER', 'Võ Thị Hồng Nhung',      '0981558899'),
    ('customer7@lemar.vn',  'CUSTOMER', 'Hoàng Gia Bảo',          '0904669911'),
    ('customer8@lemar.vn',  'CUSTOMER', 'Đoàn Quang Vinh',        '0917770022'),
    ('customer9@lemar.vn',  'CUSTOMER', 'Kiều Thị Thanh Thảo',    '0933881133'),
    ('customer10@lemar.vn', 'CUSTOMER', 'Lưu Đức Anh',            '0978992244'),
    ('customer11@lemar.vn', 'CUSTOMER', 'Trịnh Mỹ Duyên',         '0906103355'),
    ('customer12@lemar.vn', 'CUSTOMER', 'Quách Tuấn Kiệt',        '0914214466'),
    ('customer13@lemar.vn', 'CUSTOMER', 'Dương Thị Bích Ngọc',    '0937325577')
) AS v(email, role, name, phone)
JOIN roles r ON r.name = v.role;

-- ---------------------------------------------------------------------------- cơ sở

UPDATE facilities SET floor_count = 2 WHERE code = 'TD' AND floor_count = 1;

INSERT INTO facilities (code, name, address, operating_hours, status, fm_account_id, latitude, longitude, floor_count)
SELECT v.code, v.name, v.address, v.hours, 'Active', a.id, v.lat, v.lng, v.floors
FROM (VALUES
    ('BT', 'Self Storage Bình Thạnh', '208 Xô Viết Nghệ Tĩnh, Phường 21, Bình Thạnh, TP.HCM',
     '07:00–21:00 hằng ngày', 'fm3@lemar.vn', 10.804000, 106.712000, 3),
    ('TB', 'Self Storage Tân Bình', '75 Cộng Hòa, Phường 4, Tân Bình, TP.HCM',
     '08:00–20:00 hằng ngày', 'fm4@lemar.vn', 10.801600, 106.652500, 2),
    ('GV', 'Self Storage Gò Vấp', '312 Phan Văn Trị, Phường 11, Gò Vấp, TP.HCM',
     '08:00–20:00, Chủ nhật đến 17:00', 'fm5@lemar.vn', 10.827000, 106.689000, 2)
) AS v(code, name, address, hours, fm_email, lat, lng, floors)
JOIN accounts a ON a.email = v.fm_email;

-- Loại khoang mỗi cơ sở cung cấp (Q7 / TD đã có từ V4: TD không có Large, Gò Vấp cũng vậy).
INSERT INTO facility_unit_type (facility_id, unit_type_id)
SELECT f.id, t.id
FROM facilities f
JOIN unit_types t ON f.code IN ('BT', 'TB') OR (f.code = 'GV' AND t.name IN ('Small', 'Medium'))
WHERE f.code IN ('BT', 'TB', 'GV')
ON CONFLICT DO NOTHING;

-- FS của từng cơ sở (fs1 đã gán Q7 từ V2).
INSERT INTO account_facility_assignments (account_id, facility_id)
SELECT a.id, f.id
FROM (VALUES
    ('fs2@lemar.vn', 'Q7'), ('fs3@lemar.vn', 'TD'), ('fs4@lemar.vn', 'TD'), ('fs5@lemar.vn', 'BT'),
    ('fs6@lemar.vn', 'BT'), ('fs7@lemar.vn', 'TB'), ('fs8@lemar.vn', 'TB'), ('fs9@lemar.vn', 'GV'),
    ('fs10@lemar.vn', 'GV')
) AS v(email, facility_code)
JOIN accounts a ON a.email = v.email
JOIN facilities f ON f.code = v.facility_code;

-- ---------------------------------------------------------------------------- nhân sự

INSERT INTO staff_profiles (account_id, position, hire_date, id_number, emergency_contact_name,
                            emergency_contact_phone, updated_by)
SELECT a.id, v.position, v.hire_date::date, v.id_number, v.contact_name, v.contact_phone,
       (SELECT id FROM accounts WHERE email = 'admin@lemar.vn')
FROM (VALUES
    ('fm1@lemar.vn',  'Quản lý cơ sở',   '2022-03-01', '079088001234', 'Lê Thị Hoa',      '0903111222'),
    ('fm2@lemar.vn',  'Quản lý cơ sở',   '2022-06-15', '079190002345', 'Phạm Văn Đức',    '0903222333'),
    ('fm3@lemar.vn',  'Quản lý cơ sở',   '2023-01-09', '079087003456', 'Ngô Thị Thu',     '0903333444'),
    ('fm4@lemar.vn',  'Quản lý cơ sở',   '2023-08-21', '079192004567', 'Huỳnh Văn Lộc',   '0903444555'),
    ('fm5@lemar.vn',  'Quản lý cơ sở',   '2024-02-26', '079089005678', 'Đỗ Thị Nga',      '0903555666'),
    ('fs1@lemar.vn',  'Nhân viên cơ sở', '2023-04-03', '079095006789', 'Võ Thị Lệ',       '0904111222'),
    ('fs2@lemar.vn',  'Nhân viên cơ sở', '2024-05-13', '079098007890', 'Trương Văn Tài',  '0904222333'),
    ('fs3@lemar.vn',  'Nhân viên cơ sở', '2023-09-11', '079196008901', 'Lý Văn Hưng',     '0904333444'),
    ('fs4@lemar.vn',  'Nhân viên cơ sở', '2024-11-04', '079099009012', 'Phan Thị Mận',    '0904444555'),
    ('fs5@lemar.vn',  'Nhân viên cơ sở', '2024-01-15', '079197010123', 'Nguyễn Văn Tâm',  '0904555666'),
    ('fs6@lemar.vn',  'Nhân viên cơ sở', '2025-03-17', '079000011234', 'Đinh Thị Hằng',   '0904666777'),
    ('fs7@lemar.vn',  'Nhân viên cơ sở', '2024-07-01', '079096012345', 'Mai Văn Quý',     '0904777888'),
    ('fs8@lemar.vn',  'Nhân viên cơ sở', '2025-06-09', '079199013456', 'Tạ Văn Bình',     '0904888999'),
    ('fs9@lemar.vn',  'Nhân viên cơ sở', '2024-09-23', '079097014567', 'Châu Thị Diễm',   '0905111333'),
    ('fs10@lemar.vn', 'Nhân viên cơ sở', '2025-08-04', '079201015678', 'Lâm Văn Phát',    '0905222444')
) AS v(email, position, hire_date, id_number, contact_name, contact_phone)
JOIN accounts a ON a.email = v.email
ON CONFLICT (account_id) DO NOTHING;

-- Ca mẫu 07:00–21:00 cho FS chưa có ca (policy staff.shift_required bật: thiếu ca thì FM không phân công được),
-- từ 7 ngày trước tới 60 ngày sau — giống ca V32 tạo cho fs1.
INSERT INTO staff_shifts (facility_id, staff_id, shift_date, start_time, end_time, note)
SELECT f.facility_id, f.account_id, g::date, TIME '07:00', TIME '21:00', 'Ca mẫu'
FROM account_facility_assignments f
JOIN accounts a ON a.id = f.account_id AND a.status = 'ACTIVE'
JOIN roles r ON r.id = a.role_id AND r.name = 'FS'
CROSS JOIN generate_series((now() AT TIME ZONE 'Asia/Ho_Chi_Minh')::date - 7,
                           (now() AT TIME ZONE 'Asia/Ho_Chi_Minh')::date + 60, INTERVAL '1 day') g
WHERE NOT EXISTS (SELECT 1 FROM staff_shifts s WHERE s.staff_id = f.account_id);

-- ---------------------------------------------------------------------------- khoang

-- Vị trí: tầng · bên lối đi nhìn từ cửa vào · thứ tự tính từ cửa (1 = sát cửa). Khoang có hợp đồng chuyển Rented ở dưới.
INSERT INTO storage_units (code, facility_id, unit_type_id, status, floor_no, aisle_side, aisle_position)
SELECT v.code, f.id, t.id, v.status, v.floor_no, v.side, v.pos
FROM (VALUES
    ('Q7', 'A-05', 'Small',  'Available',   1, 'Left',  3),
    ('Q7', 'A-06', 'Small',  'Available',   1, 'Left',  4),
    ('Q7', 'A-07', 'Small',  'Available',   1, 'Right', 3),
    ('Q7', 'B-04', 'Medium', 'Available',   1, 'Right', 4),
    ('Q7', 'B-05', 'Medium', 'Available',   2, 'Left',  3),
    ('Q7', 'C-02', 'Large',  'Available',   2, 'Right', 3),
    ('Q7', 'C-03', 'Large',  'Available',   2, 'Left',  4),

    ('TD', 'A-01', 'Small',  'Available',   1, 'Left',  1),
    ('TD', 'A-02', 'Small',  'Available',   1, 'Left',  2),
    ('TD', 'A-03', 'Small',  'Available',   1, 'Left',  3),
    ('TD', 'A-04', 'Small',  'Available',   1, 'Right', 1),
    ('TD', 'B-01', 'Medium', 'Available',   1, 'Right', 2),
    ('TD', 'B-02', 'Medium', 'Available',   1, 'Right', 3),
    ('TD', 'B-03', 'Medium', 'Available',   2, 'Left',  1),
    ('TD', 'B-04', 'Medium', 'Available',   2, 'Left',  2),
    ('TD', 'B-05', 'Medium', 'Available',   2, 'Right', 1),
    ('TD', 'A-05', 'Small',  'Maintenance', 2, 'Right', 2),

    ('BT', 'A-01', 'Small',  'Available',   1, 'Left',  1),
    ('BT', 'A-02', 'Small',  'Available',   1, 'Left',  2),
    ('BT', 'A-03', 'Small',  'Available',   1, 'Left',  3),
    ('BT', 'A-04', 'Small',  'Available',   1, 'Left',  4),
    ('BT', 'A-05', 'Small',  'Available',   1, 'Right', 1),
    ('BT', 'A-06', 'Small',  'Available',   1, 'Right', 2),
    ('BT', 'A-07', 'Small',  'Available',   1, 'Right', 3),
    ('BT', 'B-01', 'Medium', 'Available',   2, 'Left',  1),
    ('BT', 'B-02', 'Medium', 'Available',   2, 'Left',  2),
    ('BT', 'B-03', 'Medium', 'Available',   2, 'Left',  3),
    ('BT', 'B-04', 'Medium', 'Available',   2, 'Right', 1),
    ('BT', 'B-05', 'Medium', 'Available',   2, 'Right', 2),
    ('BT', 'C-01', 'Large',  'Available',   3, 'Left',  1),
    ('BT', 'C-02', 'Large',  'Available',   3, 'Left',  2),
    ('BT', 'C-03', 'Large',  'Maintenance', 3, 'Right', 1),

    ('TB', 'A-01', 'Small',  'Available',   1, 'Left',  1),
    ('TB', 'A-02', 'Small',  'Available',   1, 'Left',  2),
    ('TB', 'A-03', 'Small',  'Available',   1, 'Left',  3),
    ('TB', 'B-01', 'Medium', 'Available',   1, 'Right', 1),
    ('TB', 'B-02', 'Medium', 'Available',   1, 'Right', 2),
    ('TB', 'B-03', 'Medium', 'Available',   1, 'Right', 3),
    ('TB', 'C-01', 'Large',  'Available',   2, 'Left',  1),
    ('TB', 'C-02', 'Large',  'Available',   2, 'Left',  2),
    ('TB', 'B-04', 'Medium', 'Available',   2, 'Right', 1),

    ('GV', 'A-01', 'Small',  'Available',   1, 'Left',  1),
    ('GV', 'A-02', 'Small',  'Available',   1, 'Left',  2),
    ('GV', 'A-03', 'Small',  'Available',   1, 'Left',  3),
    ('GV', 'A-04', 'Small',  'Available',   1, 'Left',  4),
    ('GV', 'A-05', 'Small',  'Available',   1, 'Right', 1),
    ('GV', 'A-06', 'Small',  'Maintenance', 1, 'Right', 2),
    ('GV', 'B-01', 'Medium', 'Available',   2, 'Left',  1),
    ('GV', 'B-02', 'Medium', 'Available',   2, 'Left',  2),
    ('GV', 'B-03', 'Medium', 'Available',   2, 'Right', 1)
) AS v(facility_code, code, type_name, status, floor_no, side, pos)
JOIN facilities f ON f.code = v.facility_code
JOIN unit_types t ON t.name = v.type_name;

-- ---------------------------------------------------------------------------- hợp đồng đang thuê

CREATE TEMP TABLE demo_ctx ON COMMIT DROP AS
SELECT (now() AT TIME ZONE 'Asia/Ho_Chi_Minh')::date                                   AS today,
       (SELECT value::int     FROM policies WHERE key = 'overdue.grace_days')           AS grace_days,
       (SELECT value          FROM policies WHERE key = 'overdue.fee_per_day')          AS fee_text,
       (SELECT value::numeric FROM policies WHERE key = 'overdue.fee_per_day')          AS fee_per_day,
       (SELECT value::int     FROM policies WHERE key = 'overdue.escalation_days')      AS escalation_days,
       (SELECT value::int     FROM policies WHERE key = 'rent.invoice_lead_days')       AS lead_days,
       (SELECT value::int     FROM policies WHERE key = 'contract.expiry_reminder_days') AS reminder_days,
       (SELECT value::int     FROM policies WHERE key = 'invoice.deposit_months')       AS deposit_months;

-- Ngày bắt đầu: hoặc "months_ago tháng trước + day_offset ngày", hoặc suy ngược từ ngày hết hạn mong muốn
-- (end_offset ngày so với hôm nay). unpaid_from_month: tháng tiền thuê đầu tiên chưa trả (NULL = đã trả đủ tới nay).
CREATE TEMP TABLE demo_case (
    label             TEXT,
    email             TEXT,
    facility_code     TEXT,
    unit_code         TEXT,
    period            INT,
    months_ago        INT,
    day_offset        INT,
    end_offset        INT,
    unpaid_from_month INT,
    return_requested  BOOLEAN
) ON COMMIT DROP;

INSERT INTO demo_case VALUES
    ('QH0', 'customer7@lemar.vn',  'Q7', 'A-05',  3, NULL, NULL,    3, NULL, false),
    ('QH1', 'customer2@lemar.vn',  'Q7', 'A-06',  3, NULL, NULL,   -2, NULL, false),
    ('QH2', 'customer3@lemar.vn',  'Q7', 'A-07',  3, NULL, NULL,  -10, NULL, false),
    ('QH3', 'customer4@lemar.vn',  'Q7', 'B-04',  6, NULL, NULL,  -35, NULL, false),
    ('QH4', 'customer5@lemar.vn',  'Q7', 'B-05',  3, NULL, NULL,  -12, NULL, true),
    ('QH5', 'customer6@lemar.vn',  'Q7', 'C-02',  6,    2,    4, NULL,    2, false),
    ('TD1', 'customer8@lemar.vn',  'TD', 'A-02',  6,    0,  -20, NULL, NULL, false),
    ('TD2', 'customer9@lemar.vn',  'TD', 'B-03', 12,    0,  -75, NULL, NULL, false),
    ('BT1', 'customer10@lemar.vn', 'BT', 'A-03',  3,    0,  -40, NULL, NULL, false),
    ('BT2', 'customer11@lemar.vn', 'BT', 'C-01',  6,    0,  -10, NULL, NULL, false),
    ('TB1', 'customer12@lemar.vn', 'TB', 'A-01',  6,    0, -100, NULL, NULL, false),
    ('GV1', 'customer13@lemar.vn', 'GV', 'B-01',  3,    0,   -5, NULL, NULL, false);

CREATE TEMP TABLE demo_row ON COMMIT DROP AS
SELECT c.*,
       f.id AS facility_id, f.fm_account_id AS fm_id,
       u.id AS unit_id, u.unit_type_id, t.monthly_price,
       a.id AS customer_id, a.name AS customer_name, a.phone AS customer_phone,
       CASE WHEN c.end_offset IS NOT NULL
            THEN ((x.today + c.end_offset + 1) - make_interval(months => c.period))::date
            ELSE (x.today - make_interval(months => c.months_ago))::date + c.day_offset
       END AS start_date,
       nextval(pg_get_serial_sequence('rental_requests', 'id'))  AS request_id,
       nextval(pg_get_serial_sequence('rental_orders', 'id'))    AS order_id,
       nextval(pg_get_serial_sequence('rental_contracts', 'id')) AS contract_id
FROM demo_case c
CROSS JOIN demo_ctx x
JOIN facilities f ON f.code = c.facility_code
JOIN storage_units u ON u.facility_id = f.id AND u.code = c.unit_code
JOIN unit_types t ON t.id = u.unit_type_id
JOIN accounts a ON a.email = c.email;

-- end_date như RentalContract: start_date + period tháng − 1 ngày. Phí như OverdueService.calc (chưa ai đến buổi
-- trả kho nên ngày dừng là hôm nay); tạm khoá như escalate (quá hạn ≥ escalation_days và chưa gửi yêu cầu trả kho).
ALTER TABLE demo_row
    ADD COLUMN end_date     DATE,
    ADD COLUMN start_ts     TIMESTAMPTZ,
    ADD COLUMN signed_at    TIMESTAMPTZ,
    ADD COLUMN code         TEXT,
    ADD COLUMN deposit      NUMERIC(12, 2),
    ADD COLUMN days_overdue INT,
    ADD COLUMN charged_days INT,
    ADD COLUMN suspended    BOOLEAN;

UPDATE demo_row r
SET end_date  = (r.start_date + make_interval(months => r.period))::date - 1,
    start_ts  = r.start_date::timestamp AT TIME ZONE 'Asia/Ho_Chi_Minh',
    signed_at = (r.start_date::timestamp + TIME '09:30') AT TIME ZONE 'Asia/Ho_Chi_Minh',
    code      = 'HD-' || r.facility_code || '-' || to_char(r.start_date, 'YYMMDD') || '-'
                || upper(substr(md5(random()::text), 1, 4)),
    deposit   = r.monthly_price * x.deposit_months
FROM demo_ctx x;

UPDATE demo_row r
SET days_overdue = GREATEST(0, x.today - r.end_date),
    charged_days = GREATEST(0, (x.today - r.end_date) - x.grace_days),
    suspended    = (x.today - r.end_date) >= x.escalation_days AND NOT r.return_requested
FROM demo_ctx x;

INSERT INTO rental_requests (id, facility_id, customer_name, normalized_customer_email, customer_phone, unit_type_id,
                             start_date, period, unit_id, status, created_at, responded_at)
SELECT request_id, facility_id, customer_name, email, customer_phone, unit_type_id,
       start_date, period, unit_id, 'Converted', start_ts - INTERVAL '10 days', start_ts - INTERVAL '9 days'
FROM demo_row;

INSERT INTO rental_orders (id, request_id, customer_id, unit_id, status, expires_at)
SELECT order_id, request_id, customer_id, unit_id, 'Done', start_ts + INTERVAL '20 days'
FROM demo_row;

INSERT INTO proposal_feedbacks (order_id, customer_id, unit_id, status)
SELECT order_id, customer_id, unit_id, 'Agreed' FROM demo_row;

INSERT INTO rental_contracts (id, order_id, customer_id, unit_id, code, terms_version, monthly_price, deposit_amount,
                              period, start_date, end_date, signed_at, status, access_suspended_at)
SELECT contract_id, order_id, customer_id, unit_id, code, 'builtin-v1', monthly_price, deposit,
       period, start_date, end_date, signed_at, 'Active', CASE WHEN suspended THEN now() END
FROM demo_row;

UPDATE storage_units SET status = 'Rented', updated_at = now()
WHERE id IN (SELECT unit_id FROM demo_row);

-- Chìa cơ + mã khoá số. Hợp đồng bị tạm khoá: mã khoá số đã thu hồi (khách lấy mã mới sau khi mở lại).
INSERT INTO unit_access_keys (unit_id, contract_id, access_type, quantity, issued_at, status)
SELECT unit_id, contract_id, 'PhysicalKey', 2, signed_at, 'Active' FROM demo_row;

INSERT INTO unit_access_keys (unit_id, contract_id, access_type, code_hash, issued_at, revoked_at, status)
SELECT unit_id, contract_id, 'AccessCode', '$2a$10$RSb.3SX6jl/QwQ.O2Lbt1uXgErlw5Gy9o7XHMkxLTiUoDAyuYMpJW', signed_at,
       CASE WHEN suspended THEN now() END, CASE WHEN suspended THEN 'Revoked' ELSE 'Active' END
FROM demo_row;

-- ---------------------------------------------------------------------------- hoá đơn + giao dịch

-- Tiền thuê từng tháng đã tới lượt lập (như RentBillingService: tháng k bắt đầu start_date + (k − 1) tháng, lập trước
-- rent.invoice_lead_days ngày). Tháng 1 là hoá đơn tháng đầu thu lúc bàn giao.
CREATE TEMP TABLE demo_bill ON COMMIT DROP AS
SELECT r.order_id, r.contract_id, r.customer_id, r.facility_code, r.unit_code, r.code AS contract_code, r.period,
       r.monthly_price, r.signed_at, k AS month_no,
       (r.start_date + make_interval(months => k - 1))::date AS month_start,
       (r.start_date + make_interval(months => k))::date - 1 AS month_end,
       CASE WHEN r.unpaid_from_month IS NOT NULL AND k >= r.unpaid_from_month
            THEN CASE WHEN (r.start_date + make_interval(months => k - 1))::date < x.today THEN 'Expired' ELSE 'Unpaid' END
            ELSE 'Paid'
       END AS status
FROM demo_row r
CROSS JOIN demo_ctx x
CROSS JOIN LATERAL generate_series(1, r.period) AS k
WHERE (r.start_date + make_interval(months => k - 1))::date <= x.today + x.lead_days;

ALTER TABLE demo_bill ADD COLUMN created_at TIMESTAMPTZ, ADD COLUMN due_at TIMESTAMPTZ;

UPDATE demo_bill b
SET created_at = CASE WHEN b.month_no = 1 THEN b.signed_at
                      ELSE LEAST(now() - INTERVAL '1 hour',
                                 ((b.month_start - x.lead_days)::timestamp + TIME '08:00') AT TIME ZONE 'Asia/Ho_Chi_Minh')
                 END
FROM demo_ctx x;

UPDATE demo_bill b
SET due_at = CASE WHEN b.month_no = 1 THEN b.created_at + INTERVAL '24 hours'
                  ELSE GREATEST(b.month_start, (b.created_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::date + 3)::timestamp
                       AT TIME ZONE 'Asia/Ho_Chi_Minh'
             END;

WITH deposit AS (
    INSERT INTO invoices (order_id, customer_id, code, title, type, description, status, amount, created_at, due_date)
    SELECT order_id, customer_id,
           'INV-DEP-' || facility_code || '-' || to_char(start_date - 8, 'YYMMDD') || '-'
               || upper(substr(md5(random()::text), 1, 6)),
           'Đặt cọc khoang chứa ' || unit_code, 'Deposit',
           'Thanh toán đặt cọc khoang chứa ' || unit_code || ' để đảm bảo giữ chỗ.',
           'Paid', deposit, start_ts - INTERVAL '8 days', start_ts - INTERVAL '5 days'
    FROM demo_row
    RETURNING id, amount, status, created_at, code
),
rent AS (
    INSERT INTO invoices (contract_id, customer_id, code, title, type, description, status, amount, created_at,
                          due_date)
    SELECT contract_id, customer_id,
           'INV-RNT-' || facility_code || '-' || to_char(created_at AT TIME ZONE 'Asia/Ho_Chi_Minh', 'YYMMDD') || '-'
               || upper(substr(md5(random()::text), 1, 6)),
           CASE WHEN month_no = 1 THEN 'Tiền thuê tháng đầu khoang ' || unit_code
                ELSE 'Tiền thuê tháng ' || month_no || '/' || period || ' — hợp đồng ' || contract_code END,
           'Rental',
           CASE WHEN month_no = 1
                THEN 'Thanh toán tiền thuê tháng đầu theo hợp đồng để nhận bàn giao khoang ' || unit_code || '.'
                ELSE 'Kỳ ' || to_char(month_start, 'DD/MM/YYYY') || ' – ' || to_char(month_end, 'DD/MM/YYYY')
                     || ', khoang ' || unit_code || '.' END,
           status, monthly_price, created_at, due_at
    FROM demo_bill
    RETURNING id, amount, status, created_at, code
)
INSERT INTO payment_transactions (invoice_id, gateway_transaction_no, transaction_content, vnp_txn_ref, amount,
                                  direction, status, created_at, paid_at)
SELECT id, 'DEMO-GW-' || id, 'Thanh toan ' || code, 'DEMO-' || id, amount, 'PAY', 'Success',
       created_at + INTERVAL '1 hour', created_at + INTERVAL '2 hours'
FROM (SELECT * FROM deposit UNION ALL SELECT * FROM rent) paid
WHERE status = 'Paid';

-- Phí quá hạn cộng dồn trên một hoá đơn INV-OVD Unpaid, hạn now + 7 ngày (như ContractInvoiceService.syncOverdueFee).
INSERT INTO invoices (contract_id, customer_id, code, title, type, description, status, amount, created_at, due_date)
SELECT r.contract_id, r.customer_id,
       'INV-OVD-' || r.facility_code || '-' || to_char(x.today, 'YYMMDD') || '-' || upper(substr(md5(random()::text), 1, 6)),
       'Phí quá hạn hợp đồng', 'Penalty', 'Phí quá hạn ' || r.charged_days || ' ngày sau thời gian ân hạn.',
       'Unpaid', r.charged_days * x.fee_per_day, now(), now() + INTERVAL '7 days'
FROM demo_row r CROSS JOIN demo_ctx x
WHERE r.charged_days > 0;

-- Yêu cầu trả kho đang chờ FM phân công.
INSERT INTO return_requests (contract_id, customer_id, facility_id, preferred_date, note, status, created_at, updated_at)
SELECT r.contract_id, r.customer_id, r.facility_id, x.today + 2, 'Hết nhu cầu thuê, muốn trả kho sớm.', 'Pending',
       now() - INTERVAL '1 day', now() - INTERVAL '1 day'
FROM demo_row r CROSS JOIN demo_ctx x
WHERE r.return_requested;

-- ---------------------------------------------------------------------------- thông báo

-- Nội dung như NotificationTemplateCatalog. Có sẵn thông báo theo đơn nên lượt cron đầu không gửi lại (dedup theo
-- order_notifications) — khỏi gửi mail tới các địa chỉ demo.
CREATE TEMP TABLE demo_notice ON COMMIT DROP AS
SELECT nextval(pg_get_serial_sequence('notifications', 'id')) AS id, n.*
FROM (
    SELECT r.customer_id AS account_id, r.order_id, 'CONTRACT_EXPIRING_SOON' AS type,
           'Hợp đồng thuê sắp hết hạn' AS title,
           'Hợp đồng ' || r.code || ' (khoang ' || r.unit_code || ') hết hạn ngày ' || to_char(r.end_date, 'DD/MM/YYYY')
               || ', còn ' || (r.end_date - x.today) || ' ngày. Gia hạn hoặc gửi yêu cầu trả kho trong mục Kho của tôi.'
               AS body
    FROM demo_row r CROSS JOIN demo_ctx x
    WHERE r.end_date BETWEEN x.today AND x.today + x.reminder_days
    UNION ALL
    SELECT r.customer_id, r.order_id, 'CONTRACT_OVERDUE', 'Hợp đồng thuê đã quá hạn',
           'Hợp đồng ' || r.code || ' (khoang ' || r.unit_code || ') đã hết hạn ngày ' || to_char(r.end_date, 'DD/MM/YYYY')
               || '. Phí quá hạn ' || x.fee_text || 'đ/ngày được tính sau ' || x.grace_days
               || ' ngày ân hạn. Vui lòng gia hạn hoặc trả kho.'
    FROM demo_row r CROSS JOIN demo_ctx x
    WHERE r.days_overdue > 0
    UNION ALL
    SELECT r.fm_id, r.order_id, 'CONTRACT_OVERDUE', 'Hợp đồng quá hạn',
           'Hợp đồng ' || r.code || ' (khoang ' || r.unit_code || ') đã quá hạn từ ngày '
               || to_char(r.end_date, 'DD/MM/YYYY') || '.'
    FROM demo_row r
    WHERE r.days_overdue > 0
    UNION ALL
    SELECT r.customer_id, r.order_id, 'CONTRACT_ACCESS_SUSPENDED', 'Quyền truy cập khoang tạm khoá',
           'Hợp đồng ' || r.code || ' (khoang ' || r.unit_code || ') đã quá hạn ' || r.days_overdue
               || ' ngày. Mã khoá số đã tạm khoá; gia hạn hoặc trả kho và thanh toán phí quá hạn để mở lại.'
    FROM demo_row r
    WHERE r.suspended
    UNION ALL
    SELECT r.fm_id, r.order_id, 'CONTRACT_ACCESS_SUSPENDED', 'Hợp đồng quá hạn kéo dài',
           'Hợp đồng ' || r.code || ' (khoang ' || r.unit_code || ') đã quá hạn ' || r.days_overdue
               || ' ngày, mã khoá số đã tạm khoá. Liên hệ khách để gia hạn hoặc trả kho; khách không phản hồi thì xử lý '
               || 'thu hồi theo quy trình của cơ sở.'
    FROM demo_row r
    WHERE r.suspended
    UNION ALL
    SELECT r.fm_id, r.order_id, 'RETURN_REQUESTED', 'Yêu cầu trả kho mới',
           'Khách gửi yêu cầu trả khoang ' || r.unit_code || ' (hợp đồng ' || r.code || ') ngày mong muốn '
               || to_char(x.today + 2, 'DD/MM/YYYY') || '. Vui lòng phân công FS.'
    FROM demo_row r CROSS JOIN demo_ctx x
    WHERE r.return_requested
    UNION ALL
    SELECT r.fm_id, r.order_id, 'RENT_UNPAID', 'Khách chưa trả tiền thuê',
           'Hoá đơn tiền thuê ' || i.code || ' của hợp đồng ' || r.code || ' (khoang ' || r.unit_code
               || ') đã quá hạn chưa thanh toán (' || i.amount || 'đ). Cần liên hệ khách.'
    FROM demo_row r
    JOIN invoices i ON i.contract_id = r.contract_id AND i.type = 'Rental' AND i.status = 'Expired'
) n;

INSERT INTO notifications (id, account_id, type, title, body, created_at)
SELECT id, account_id, type, title, body, now() FROM demo_notice;

INSERT INTO order_notifications (order_id, notification_id)
SELECT order_id, id FROM demo_notice;

-- ---------------------------------------------------------------------------- kết quả

SELECT r.label AS kich_ban, r.email AS khach, r.facility_code || ' ' || r.unit_code AS khoang, r.code AS hop_dong,
       to_char(r.end_date, 'DD/MM/YYYY') AS het_han, r.days_overdue AS qua_han_ngay,
       (SELECT string_agg(i.code || ' ' || i.status || ' ' || i.amount, ', ' ORDER BY i.id)
        FROM invoices i
        WHERE i.contract_id = r.contract_id AND (i.status <> 'Paid')) AS hoa_don_chua_tra,
       CASE WHEN r.suspended THEN 'tạm khoá' ELSE '' END AS truy_cap
FROM demo_row r
ORDER BY r.label;

SELECT f.code AS co_so, a.name AS fm, count(u.id) AS so_khoang,
       count(u.id) FILTER (WHERE u.status = 'Available') AS con_trong
FROM facilities f
LEFT JOIN accounts a ON a.id = f.fm_account_id
LEFT JOIN storage_units u ON u.facility_id = f.id
GROUP BY f.code, a.name, f.id
ORDER BY f.id;

COMMIT;
