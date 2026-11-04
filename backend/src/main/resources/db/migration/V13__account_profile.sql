-- Account profile: tên hiển thị + số điện thoại (proposal #207).
-- Nullable để không phá account cũ/test insert chỉ có email; API fallback về email khi null.
ALTER TABLE accounts ADD COLUMN name VARCHAR(255);
ALTER TABLE accounts ADD COLUMN phone VARCHAR(20);

-- Backfill khách hàng từ request mới nhất của cùng email.
UPDATE accounts a
SET name = COALESCE(a.name, sub.customer_name),
    phone = COALESCE(a.phone, sub.customer_phone)
FROM (
    SELECT DISTINCT ON (normalized_customer_email)
           normalized_customer_email, customer_name, customer_phone
    FROM rental_requests
    ORDER BY normalized_customer_email, created_at DESC, id DESC
) sub
WHERE a.email = sub.normalized_customer_email;

-- Tên cho account seed V2 (không sửa V2 — CI so khớp sql/seed.sql).
UPDATE accounts SET name = 'Quản trị viên' WHERE email = 'admin@lemar.vn' AND name IS NULL;
UPDATE accounts SET name = 'Business Operation Manager' WHERE email = 'bom1@lemar.vn' AND name IS NULL;
UPDATE accounts SET name = 'FM Quận 7' WHERE email = 'fm1@lemar.vn' AND name IS NULL;
UPDATE accounts SET name = 'FM Thủ Đức' WHERE email = 'fm2@lemar.vn' AND name IS NULL;
UPDATE accounts SET name = 'FS Quận 7' WHERE email = 'fs1@lemar.vn' AND name IS NULL;
UPDATE accounts SET name = 'Khách Demo' WHERE email = 'customer1@lemar.vn' AND name IS NULL;
UPDATE accounts SET name = 'Khách chưa xác minh' WHERE email = 'unverified@lemar.vn' AND name IS NULL;
UPDATE accounts SET name = 'Khách bị chặn' WHERE email = 'banned@lemar.vn' AND name IS NULL;
