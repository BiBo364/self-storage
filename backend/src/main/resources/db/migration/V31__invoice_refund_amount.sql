-- Số tiền phải hoàn của hoá đơn đang chờ hoàn (refund_status Required/Processing). NULL = hoàn toàn bộ số tiền hoá
-- đơn (khoang mất, tiền vào hoá đơn đã đóng); có giá trị khi chỉ hoàn một phần (cọc dư khi đổi khoang, hoàn cọc khi
-- trả kho). FM ghi nhận đủ số này qua hoàn tiền thủ công thì hoá đơn chuyển Refunded.
ALTER TABLE invoices
    ADD COLUMN refund_amount NUMERIC(12, 2) CHECK (refund_amount IS NULL OR refund_amount > 0);
