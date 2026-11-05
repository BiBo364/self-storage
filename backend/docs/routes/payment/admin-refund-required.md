---
auth-model: permission-bound
purpose: ADMIN xem các invoice đang có nghĩa vụ hoàn tiền; read-only, chưa ghi nhận hoàn tiền.
---
# GET /api/admin/payments/refunds?page=&size=

Trả các invoice có `refund_status = Required`, kèm giao dịch `PAY/Success` gần nhất. Route
read-only; chưa có endpoint ghi nhận hoàn tiền.

- **Actor / quyền:** role `ADMIN`.
- **Contract chi tiết:** annotation tại `AdminRefundPaymentController.java`; Swagger UI —
  `http://localhost:8080/swagger-ui/index.html`.

## Luồng / hành vi

- `page` bắt đầu từ 0; `size` mặc định 20, tối đa 100; sai → `400`.
- Query đọc từ `invoices`, không đọc từ case: khoản cần hoàn vẫn hiện dù chưa từng có case đối soát.
- Mỗi dòng: `invoiceId`, `invoiceCode`, `orderId`, `amount`, `refundStatus`, `refundReason`,
  `refundRequestedAt`, `vnpTxnRef`, `gatewayTransactionNo`, `paidAt`.
- `vnpTxnRef`/`gatewayTransactionNo`/`paidAt` lấy từ giao dịch `PAY/Success` gần nhất; có thể null
  nếu chưa có giao dịch thành công.
- Không đổi trạng thái invoice/transaction; không tạo refund transaction.

## Ghi chú nghiệp vụ

- `refund_status=Required` được đặt khi tiền đã vào nhưng khoang không còn giữ được
  (`INVOICE_REFUND_REQUIRED`).
- Hoàn tiền thủ công thuộc Flow 4; route này chỉ là queue hiển thị.
- Khi có endpoint ghi nhận hoàn tiền, trạng thái sẽ chuyển `Required → Processing → Refunded`.

## Liên quan

- Case Pending: [admin-pending-payments.md](admin-pending-payments.md)
- Nhánh thanh toán unit đã mất: [invoice-pay.md](../booking/invoice-pay.md)
- Xem thêm: [routes.md](../../routes.md) · [index.md](../../index.md)
