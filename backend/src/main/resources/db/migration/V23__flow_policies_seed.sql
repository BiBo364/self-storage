-- Policy cho đặt lịch check-in (Flow 1.5), nhắc đơn sắp hết hạn (cron Flow 1) và bàn giao (Flow 2.3).
-- appointment.*: chốt ở behavior-gap đặt lịch check-in — 3 khung cố định/ngày, capacity 1 lịch/khung/cơ sở.
-- handover.payment_grace_hours, contract.prepaid_months: spec ghi "chờ BOM" / 1 tháng — giá trị mặc định,
-- BOM đổi qua màn hình chính sách.
INSERT INTO policies (key, value, description) VALUES
    ('appointment.booking_window_days', '7', 'Khách chỉ đặt được lịch check-in trong N ngày tới (tính từ hôm nay)'),
    ('appointment.max_days_after_deposit', '14', 'Ngày hẹn check-in cách lúc cọc tối đa N ngày'),
    ('appointment.daily_slot_count', '3', 'Số khung giờ check-in cố định mỗi ngày'),
    ('appointment.capacity_mode', 'fixed_windows', 'Cách tính sức chứa lịch hẹn (MVP: khung cố định)'),
    ('order.expiry_reminder_days', '3', 'Nhắc khách khi đơn còn N ngày là hết hạn'),
    ('handover.payment_grace_hours', '24', 'Hạn thanh toán hoá đơn tháng đầu sau khi ký hợp đồng (giờ)'),
    ('contract.prepaid_months', '1', 'Số tháng trả trước khi bàn giao')
ON CONFLICT (key) DO NOTHING;
