---
auth-model: permission-bound
purpose: ADMIN xem các invoice đang có nghĩa vụ hoàn tiền; read-only — FM ghi nhận hoàn tiền ở route riêng.
---
# GET /api/admin/payments/refunds?page=&size=

Trả các invoice có `refund_status` `Required` hoặc `Processing`, kèm giao dịch `PAY/Success` gần nhất. Route
read-only; FM ghi nhận hoàn tiền qua `POST /api/fm/invoices/{invoiceId}/refunds`
([fm-invoice-refunds.md](../booking/fm-invoice-refunds.md)).

- **Actor / quyền:** role `ADMIN`; đây là queue oversight. FM dùng
  `GET /api/fm/invoices/refunds` theo facility.
- **Contract chi tiết:** annotation tại `AdminRefundPaymentController.java`; Swagger UI —
  `http://localhost:8080/swagger-ui/index.html`.

## Luồng / hành vi

- `page` bắt đầu từ 0; `size` mặc định 20, tối đa 100; sai → `400`.
- Query đọc từ `invoices`, không đọc từ case: khoản cần hoàn vẫn hiện dù chưa từng có case đối soát.
- Mỗi dòng: `invoiceId`, `invoiceCode`, `orderId`, `amount` (= số phải hoàn; rỗng thì số tiền hoá đơn),
  `refundStatus`, `refundReason`,
  `refundRequestedAt`, `vnpTxnRef`, `gatewayTransactionNo`, `paidAt`.
- `vnpTxnRef`/`gatewayTransactionNo`/`paidAt` lấy từ giao dịch `PAY/Success` gần nhất; có thể null
  nếu chưa có giao dịch thành công.
- Không đổi trạng thái invoice/transaction; không tạo refund transaction.

## Ghi chú nghiệp vụ

- `refund_status=Required` được đặt (audit `INVOICE_REFUND_REQUIRED`) mỗi khi tiền đã vào phải trả lại khách —
  danh sách lý do ở [payment.md › Hoàn tiền thủ công](../../payment.md#hoàn-tiền-thủ-công).
- FM ghi nhận khoản đã chuyển: chưa đủ số phải hoàn → `Processing`, đủ → `Refunded` (rời queue).

## Liên quan

- Case Pending: [admin-pending-payments.md](admin-pending-payments.md)
- Nhánh thanh toán unit đã mất: [invoice-pay.md](../booking/invoice-pay.md)
- Xem thêm: [routes.md](../../routes.md) · [index.md](../../index.md)
