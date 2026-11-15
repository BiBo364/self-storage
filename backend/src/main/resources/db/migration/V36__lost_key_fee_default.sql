-- Phí mất chìa mặc định: 100.000 đ mỗi chìa cơ (trước đây seed 200.000 ở V25). Chỉ đổi khi policy còn giữ giá seed cũ —
-- BOM đã chỉnh giá khác thì giữ nguyên.
UPDATE policies
SET value = '100000', updated_at = now()
WHERE key = 'fee.lost_key' AND value = '200000';
