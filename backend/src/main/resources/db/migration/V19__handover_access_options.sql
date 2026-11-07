-- Flow 2.4: cơ sở bật loại quyền truy cập nào (khóa cơ / mã số) và lựa chọn của khách khi xác nhận bàn giao.
-- Cấu hình loại khóa thuộc Flow 5 nhưng chưa có màn hình — mặc định bật cả hai.
ALTER TABLE facilities
    ADD COLUMN enabled_key_access  BOOLEAN NOT NULL DEFAULT true,
    ADD COLUMN enabled_code_access BOOLEAN NOT NULL DEFAULT true;

-- Khách chọn hình thức nhận quyền truy cập sau khi hoá đơn tháng đầu Paid:
-- AccessCode → chốt ngay; PhysicalKey/Both → chờ FS giao chìa (FS xác nhận mới chốt).
ALTER TABLE handover_records
    ADD COLUMN requested_access      VARCHAR(20)
        CHECK (requested_access IS NULL OR requested_access IN ('AccessCode', 'PhysicalKey', 'Both')),
    ADD COLUMN customer_confirmed_at TIMESTAMPTZ;

-- Flow 2.3: mốc bắt đầu tính tiền mặc định. Spec ghi "chờ BOM" — MVP dùng ngày sinh hợp đồng (giờ VN).
INSERT INTO policies (key, value, description) VALUES
    ('contract.start_date_rule', 'handover_date', 'Mốc bắt đầu tính tiền thuê: handover_date = ngày sinh hợp đồng')
ON CONFLICT (key) DO NOTHING;
