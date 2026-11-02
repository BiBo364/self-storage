# POST /api/admin/payments/{txnRef}/reconcile

Lớp đối soát thủ công thay cho `querydr`: ADMIN tra portal VNPay rồi nhập kết quả cho một giao dịch
còn `Pending`. Endpoint không sửa DB tay; nó khoá row, ghi audit và phát event như IPN.

- **Actor / quyền:** Bearer + role `ADMIN`.
- **Contract chi tiết:** annotation tại `AdminPaymentReconciliationController.java`; Swagger UI —
  `http://localhost:8080/swagger-ui/index.html`.

## Request

```json
{
  "outcome": "SUCCESS",
  "note": "portal VNPay: vnp_TransactionNo 14777001",
  "paidAt": "2026-10-30T10:00:00Z",
  "gatewayTransactionNo": "14777001"
}
```

- `outcome`: `SUCCESS` hoặc `FAILED`.
- `note`: bằng chứng/ghi chú, bắt buộc.
- `paidAt`: thời điểm trả theo portal; bắt buộc khi `outcome=SUCCESS`, không được ở tương lai và
  không được trước `PaymentTransaction.createdAt`. So sánh `createdAt` ở độ chính xác giây để khớp
  timestamp của portal; cùng giây là hợp lệ.
- `gatewayTransactionNo`: mã giao dịch VNPay; bắt buộc khi `outcome=SUCCESS`, không được trùng giao
  dịch khác.
- `amount` không có trong request: hệ thống dùng `PaymentTransaction.amount`, không nhận từ người
  nhập.

## Hành vi

1. Tìm giao dịch theo `txnRef`; không có → `404`.
2. Khoá row giao dịch, khoá invoice bằng advisory lock; chỉ nhận `Pending`; đã
   `Success`/`Failed`/`Duplicate` → `409`, không ghi thêm.
3. `SUCCESS`: `paidAt` bắt buộc, không tương lai, không trước `PaymentTransaction.createdAt`
   (so sánh tới giây); thiếu/sai → `400`.
4. `SUCCESS`: `gatewayTransactionNo` bắt buộc; trùng giao dịch khác → `409`.
5. `SUCCESS`: invoice chưa có giao dịch `PAY/Success` → `markSucceeded`, audit
   `PAYMENT_SUCCEEDED` (ghi `gatewayTransactionNo` + `paidAt`), phát `PaymentSucceeded`.
6. `SUCCESS`: invoice đã có `PAY/Success` khác → transaction chuyển `DUPLICATE`, audit
   `PAYMENT_DUPLICATE` (ghi `gatewayTransactionNo` + `paidAt`), phát event notify FM; trả `200` với
   status `DUPLICATE`.
7. `FAILED` → `markFailed`, audit `PAYMENT_FAILED`, phát `PaymentFailed`.
8. Listener booking cập nhật invoice/order/unit như IPN; late success vẫn đi nhánh invoice
   `Canceled`/`Expired` để xử lý tay/hoàn tiền. `DUPLICATE` không cập nhật invoice/order/unit.

## Lỗi

| Status | Khi nào |
|---|---|
| 400 | thiếu/sai `outcome`, `note`, `paidAt`/`gatewayTransactionNo` khi `SUCCESS`, hoặc JSON sai |
| 403 | không phải ADMIN |
| 404 | không có giao dịch với `txnRef` |
| 409 | giao dịch đã chốt hoặc `gatewayTransactionNo` trùng |

`SUCCESS` trên invoice đã có `PAY/Success` khác trả `200` với `data.status=DUPLICATE`, không phải 409.

## Quy trình ops

1. Lọc `PaymentTransaction(Pending)` quá 15 phút.
2. Tra portal VNPay theo `vnp_TxnRef`.
3. Đã trả → gọi `SUCCESS` kèm `paidAt` + `gatewayTransactionNo`; chưa trả → gọi `FAILED` kèm note.
4. Kiểm tra invoice/order/unit + audit; không sửa DB tay.

## Giới hạn

- Không tự retry, không tự phát hiện giao dịch; đây là thay thế có chủ ý cho querydr.
- Giao dịch `Pending` không có ai tra vẫn nằm nguyên — vận hành phải có lịch kiểm tra.

## Liên quan

- [payment.md](../../payment.md) · [invoice-pay.md](../booking/invoice-pay.md) ·
  [routes.md](../../routes.md)
