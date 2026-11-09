---
auth-model: permission-bound
purpose: FM xem các invoice đang có nghĩa vụ hoàn tiền thuộc cơ sở mình.
---
# GET /api/fm/invoices/refunds?facilityId=&page=&size=

Hàng đợi hoàn tiền của FM theo facility: các invoice có `refund_status` `Required` hoặc `Processing` (đã hoàn một
phần). FM ghi nhận khoản đã chuyển qua [fm-invoice-refunds.md](fm-invoice-refunds.md); hoàn đủ thì invoice chuyển
`Refunded` và rời hàng đợi.

- **Actor / quyền:** Bearer + role `FM`; `payment_transaction.refund` scope `FACILITY`.
- **Contract chi tiết:** annotation tại `FmRefundController.java`.

## Hành vi

- `facilityId` bắt buộc; FM phải thuộc facility đó.
- Chỉ invoice có `refund_status` `Required`/`Processing` và thuộc facility.
- Mỗi dòng: `invoiceId`, `invoiceCode`, `orderId`, `amount` (= số phải hoàn `refund_amount`; rỗng thì số tiền hoá
  đơn), `refundStatus`, `refundReason`,
  `refundRequestedAt`, `vnpTxnRef`, `gatewayTransactionNo`, `paidAt`.
- `refundReason`: `UNIT_UNAVAILABLE`, `INVOICE_CLOSED`, `UNIT_CHANGE_EXCESS`, `DEPOSIT_UNSERVED`,
  `HANDOVER_CANCELED`, `CHECKOUT_DEPOSIT` ([payment.md › Hoàn tiền thủ công](../../payment.md#hoàn-tiền-thủ-công)).
- Route chỉ đọc; ghi nhận hoàn tiền ở `POST /api/fm/invoices/{invoiceId}/refunds`.

## Lỗi

| Status | Khi nào |
|---|---|
| 400 | page/size không hợp lệ |
| 403 | Không phải FM hoặc facility ngoài phạm vi |
| 404 | Facility không tồn tại |

## Liên quan

- [fm-invoice-list.md](fm-invoice-list.md)
- [admin-refund-required.md](../payment/admin-refund-required.md)
