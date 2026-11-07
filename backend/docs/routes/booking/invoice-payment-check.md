---
auth-model: ownership
purpose: Khách quay về từ cổng — đối soát giao dịch mới nhất với cổng (payOS tra cứu link / VNPay querydr) rồi trả trạng thái.
---
# POST /api/invoices/{invoiceId}/payment-check

FE gọi trên trang kết quả sau khi VNPay redirect khách về, để biết hoá đơn đã `Paid` chưa.

- **Actor / quyền:** bearer + `ACCOUNT_ACTIVE` + ownership (→ 404).
- **Contract chi tiết:** annotation tại `booking/controller/InvoicePaymentController.java`; Swagger UI —
  `http://localhost:8080/swagger-ui/index.html`.

## Luồng / hành vi

- Gọi `PaymentService.reconcileLatest` cho giao dịch mới nhất của hoá đơn: giao dịch đã chốt → trả nguyên trạng,
  không gọi cổng; còn `Pending` → hỏi cổng server-to-server (payOS `GET /v2/payment-requests/{orderCode}` với API
  key; VNPay `querydr` có chữ ký HMAC).
- Cổng xác nhận đã trả + số tiền khớp → `Success` + `PaymentSucceeded` → listener chốt hoá đơn (như IPN).
  Cổng báo lỗi → `Failed`. Cổng không có giao dịch và chưa quá cửa sổ phiên → giữ `Pending`.
- Đọc lại hoá đơn **sau** khi listener chốt (listener chạy `AFTER_COMMIT` của bước đối soát).
- Route này không thay IPN: IPN vẫn là nguồn chính; `querydr` là nguồn dự phòng duy nhất được phép
  (payment.md › Quy tắc bất biến). Lợi ích: chạy local không cần URL public cho IPN.

## Ghi chú nghiệp vụ

- Return URL (`/api/payments/vnpay/return`) vẫn chỉ hiển thị, không ghi dữ liệu.

## Liên quan

- Xem thêm: [routes.md](../../routes.md) · [index.md](../../index.md) · [payment.md](../../payment.md)
