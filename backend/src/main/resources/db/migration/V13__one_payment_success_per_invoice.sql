-- Bất biến tiền: một invoice chỉ có tối đa một PaymentTransaction(PAY/Success).
-- Advisory lock ở application là lớp chính; index này là chốt cuối ở DB.
-- Nếu DB đang có dữ liệu vi phạm, migration fail để ops dọn trước, không tự xoá tiền.

DO $$
DECLARE duplicate_invoice BIGINT;
BEGIN
    SELECT invoice_id INTO duplicate_invoice
    FROM payment_transactions
    WHERE direction = 'PAY' AND status = 'Success'
    GROUP BY invoice_id
    HAVING count(*) > 1
    LIMIT 1;

    IF duplicate_invoice IS NOT NULL THEN
        RAISE EXCEPTION
            'Invoice % có nhiều hơn một PAY/Success; dọn dữ liệu trước khi chạy V13',
            duplicate_invoice;
    END IF;
END $$;

CREATE UNIQUE INDEX uq_payment_transactions_one_success_per_invoice
    ON payment_transactions (invoice_id)
    WHERE direction = 'PAY' AND status = 'Success';
