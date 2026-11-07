---
auth-model:
purpose: payOS báo kết quả chuyển khoản — nguồn xác nhận chính khi payment.provider=payos.
---
# POST /api/payments/payos/webhook

Server payOS gọi khi có chuyển khoản khớp một payment link.

- **Actor / quyền:** public (payOS không có JWT); xác thực bằng chữ ký HMAC-SHA256 (checksum key) trong body.
- **Contract chi tiết:** annotation tại `payment/controller/PayOsWebhookController.java`; Swagger UI —
  `http://localhost:8080/swagger-ui/index.html`.

## Luồng / hành vi

- Xem [payment.md › Webhook payOS](../../payment.md#webhook-payos).
- Idempotent theo `orderCode`: lần sau trả `ALREADY_SETTLED`, không ghi thêm, không phát event lần hai.
- Thành công → `PaymentSucceeded` → booking chốt hoá đơn sau commit ([deposit-payment.md](../../booking/deposit-payment.md)).

## Ghi chú nghiệp vụ

- URL phải là https công khai, khai ở my.payos.vn. Không có URL public (chạy local) thì `payment-check` + job đối
  soát vẫn chốt giao dịch.
- payOS không có sandbox — mọi webhook là giao dịch thật.

## Liên quan

- Xem thêm: [routes.md](../../routes.md) · [payment.md](../../payment.md)
