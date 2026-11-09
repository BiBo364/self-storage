---
auth-model: ownership
purpose: Khách quay về từ cổng — đối soát giao dịch mới nhất với cổng (payOS tra cứu link / VNPay querydr) rồi trả trạng thái.
---
# POST /api/invoices/{invoiceId}/payment-check

FE gọi trên trang kết quả (`<FRONTEND_URL>/payments/result`) sau khi cổng (VNPay mặc định, hoặc payOS) redirect
khách về, để biết hoá đơn đã `Paid` chưa.

- **Actor / quyền:** bearer + `ACCOUNT_ACTIVE` + ownership (→ 404).
- **Contract chi tiết:** annotation tại `booking/controller/InvoicePaymentController.java`; Swagger UI —
  `http://localhost:8080/swagger-ui/index.html`.

## Luồng / hành vi

- Gọi `PaymentService.reconcileLatest` cho giao dịch mới nhất của hoá đơn: giao dịch đã chốt → trả nguyên trạng,
  không gọi cổng; còn `Pending` → hỏi cổng server-to-server (payOS `GET /v2/payment-requests/{orderCode}` với API
  key; VNPay `querydr` có chữ ký HMAC).
- Cổng xác nhận đã trả + số tiền khớp → chốt như IPN (khoá theo invoice; invoice đã có `PAY/Success` khác thì
  giao dịch thành `Duplicate`, FM được báo hoàn) → listener chốt hoá đơn; đóng case đối soát nếu có. Cổng báo lỗi /
  huỷ → `Failed`. Cổng chưa có kết quả, không có giao dịch, lỗi mạng, sai chữ ký, lệch số tiền → giữ `Pending` — không
  đóng phiên theo đồng hồ; watchdog mở case cho ADMIN ([payment.md](../../payment.md)).
- Đọc lại hoá đơn **sau** khi listener chốt (listener chạy `AFTER_COMMIT` của bước đối soát).
- Response `PaymentCheck`: `invoiceId`, `invoiceStatus`, `paymentStatus`, `failureReason`, `bookCheckinOrderId`.
  `bookCheckinOrderId` = id đơn khi hoá đơn là cọc (`Deposit`/`DepositSupplement`) đã `Paid` **và** đơn đặt được lịch
  check-in ngay (`AppointmentBookingService.bookable`); còn lại `null`.
- Trang kết quả FE: có `bookCheckinOrderId` → nút "Đặt lịch nhận kho" sang bước đặt lịch check-in; thanh toán thất bại
  → tiêu đề "Thanh toán thất bại, vui lòng thử lại!".
- Route này không thay IPN VNPay / webhook payOS: đó vẫn là nguồn xác nhận chính. Hệ thống không có job tự hỏi cổng;
  tra cứu chỉ chạy khi khách quay về trang kết quả (chạy local không cần URL public).

## Ghi chú nghiệp vụ

- Return URL VNPay (`/api/payments/vnpay/return`) chỉ hiển thị, không ghi dữ liệu; payOS redirect thẳng về trang FE.

## Liên quan

- Xem thêm: [routes.md](../../routes.md) · [index.md](../../index.md) · [payment.md](../../payment.md)
