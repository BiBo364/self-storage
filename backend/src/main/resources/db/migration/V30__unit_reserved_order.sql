-- Khoang Reserved ghi đơn đang giữ nó. Trước đây "đơn giữ khoang" suy từ trạng thái đơn (Deposited/Scheduled/
-- InProgress) + unit_id; đơn đã cọc đổi khoang mà khoang mới bị đơn khác cọc trước vẫn trỏ unit_id tới khoang đó,
-- huỷ đơn này sẽ nhả mất khoang của đơn kia. Từ nay chỉ đơn ghi ở reserved_order_id mới nhả được khoang.
ALTER TABLE storage_units
    ADD COLUMN reserved_order_id BIGINT REFERENCES rental_orders (id) ON DELETE SET NULL;

-- Khoang đang Reserved: gán đơn giữ khi xác định được đúng một đơn đang giữ khoang trỏ tới nó. Không xác định được
-- (không có / nhiều hơn một đơn) để NULL — khoang NULL nhả theo luật cũ (đơn đang giữ khoang nào trỏ tới cũng nhả).
UPDATE storage_units su
SET reserved_order_id = (SELECT o.id FROM rental_orders o
                         WHERE o.unit_id = su.id AND o.status IN ('Deposited', 'Scheduled', 'InProgress'))
WHERE su.status = 'Reserved'
  AND (SELECT count(*) FROM rental_orders o
       WHERE o.unit_id = su.id AND o.status IN ('Deposited', 'Scheduled', 'InProgress')) = 1;
