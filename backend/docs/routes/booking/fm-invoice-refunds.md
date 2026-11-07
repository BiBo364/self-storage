---
auth-model: permission-bound
purpose: FM xem lịch sử và ghi nhận khoản hoàn tiền đã chuyển thủ công cho khách trên một hoá đơn của cơ sở.
---

# GET / POST /api/fm/invoices/{invoiceId}/refunds

Cổng thanh toán không có API hoàn tiền: FM chuyển khoản trả khách ngoài hệ thống rồi ghi nhận lại ở đây. Dùng cho mọi
trường hợp hệ thống báo "cần hoàn" (hoàn cọc khi trả kho, cọc thừa khi đổi khoang, tiền vào sau khi hoá đơn đã đóng,
huỷ sau khi đã trả tháng đầu). FE: panel "Hoàn tiền" trên màn "Hoá đơn cơ sở" của FM.

- **Actor / quyền:** role-bound `FM` (`/api/fm/**`) + permission `payment_transaction.refund` (scope `FACILITY`)
  theo cơ sở của hoá đơn — `Access.can(permission, facilityId)`.
- **Contract chi tiết:** annotation tại `booking/controller/FmInvoiceRefundController.java`; Swagger UI —
  `http://localhost:8080/swagger-ui/index.html` (route cần token: bấm **Authorize** và dán JWT).

## Luồng / hành vi

- `GET`: trả `RefundSummary` = `invoiceId`, `refundable`, `refunds[]` (`id`, `amount`, `note`, `recordedAt`, cũ nhất
  trước).
- `refundable` = tổng giao dịch `PAY` `Success` của hoá đơn − tổng giao dịch `REFUND` `Success`
  (`PaymentService.refundable`). Hoá đơn không thu qua cổng (vd hoá đơn đối trừ cọc khi trả kho) có `refundable = 0`.
- `POST` body `{ "amount": 200000, "note": "CK hoàn cọc, mã GD ..." }`, một transaction
  (`ManualRefundService.record`):
  1. `amount` thiếu / ≤ 0 → 400 "Số tiền hoàn phải lớn hơn 0"; `note` trống hoặc > 500 ký tự → 400 "Cần ghi chú hoàn
     tiền (lý do, mã chuyển khoản), tối đa 500 ký tự";
  2. kiểm quyền theo cơ sở của hoá đơn (hoá đơn không tồn tại → 404; ngoài cơ sở → 403);
  3. khoá row hoá đơn — hai lần ghi song song không cùng vượt `refundable`;
  4. `amount > refundable` → 409 "Số tiền hoàn vượt số đã thu qua cổng thanh toán của hoá đơn";
  5. ghi `PaymentTransaction` `direction = REFUND`, `status = Success`, `txn_ref = RF{invoiceId}-{epochMillis}`,
     nội dung = ghi chú, `paid_at` = lúc ghi (`PaymentService.recordManualRefund`);
  6. audit `MANUAL_REFUND_RECORDED` (entity `PaymentTransaction`, `new_value` = `invoiceId`, `invoiceCode`, `amount`;
     `reason` = ghi chú; actor = FM);
  7. trả `RefundSummary` mới.
- Ghi nhận hoàn tiền không đổi trạng thái hoá đơn / đơn / hợp đồng và không gửi notification.

## Ghi chú nghiệp vụ

- Báo cáo doanh thu back-office không trừ các khoản hoàn này
  ([backoffice/overview.md](../../backoffice/overview.md)).
- Chi tiết mô hình hoàn tiền: [payment.md › Hoàn tiền thủ công](../../payment.md#hoàn-tiền-thủ-công).

## Ví dụ

Chưa có output thật trong tài liệu — chạy theo mẫu dưới trên DB local (`fm1@lemar.vn`, hoá đơn đã thu qua cổng).

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"fm1@lemar.vn","password":"Test@1234"}' | jq -r .data.token)

curl -s http://localhost:8080/api/fm/invoices/12/refunds -H "Authorization: Bearer $TOKEN"

curl -s -X POST http://localhost:8080/api/fm/invoices/12/refunds -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"amount":200000,"note":"CK hoàn cọc"}'
```

## Liên quan

- [routes.md](../../routes.md) · [payment.md](../../payment.md) · [permission-catalog.md](../../permission-catalog.md)
