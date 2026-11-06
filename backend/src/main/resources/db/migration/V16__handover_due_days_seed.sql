-- Flow 2.1: V2 cố ý chưa seed `handover.due_days` ("chờ BOM, gợi ý 1–2 ngày").
-- Chốt giá trị 1 ngày (cận dưới gợi ý); BOM đổi bằng UPDATE policies, không cần migration mới.
-- Dùng Flyway thay vì sửa V2 (checksum); `sql/seed.sql` giữ mirror V2 nên không đổi theo.
INSERT INTO policies (key, value, description) VALUES
    ('handover.due_days', '1', 'Hạn khách phải hoàn tất bàn giao sau khi đến cơ sở (Flow 2.1)')
ON CONFLICT (key) DO NOTHING;
