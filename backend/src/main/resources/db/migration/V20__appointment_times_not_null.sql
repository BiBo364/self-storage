-- Flow 2.1: lịch hẹn luôn có khung giờ; no-show và notification đọc trực tiếp hai mốc này.
ALTER TABLE appointments ALTER COLUMN started_at SET NOT NULL;
ALTER TABLE appointments ALTER COLUMN end_at SET NOT NULL;
