---
auth-model: ownership
purpose: Khách quay về từ cổng — chốt bằng tham số VNPay có chữ ký trên Return URL, đối soát giao dịch mới nhất với cổng (payOS tra cứu link / VNPay querydr) rồi trả trạng thái.
---
# POST /api/invoices/{invoiceId}/payment-check

FE gọi trên trang kết quả (`<FRONTEND_URL>/payments/result`) sau khi cổng (VNPay mặc định, hoặc payOS) redirect
khách về, để biết hoá đơn đã `Paid` chưa.

- **Actor / quyền:** bearer + `ACCOUNT_ACTIVE` + ownership (→ 404).
- **Body (tuỳ chọn):** `{"gatewayParams": {"vnp_TxnRef": "…", …, "vnp_SecureHash": "…"}}` — các tham số `vnp_*` VNPay
  gắn trên Return URL, tối đa 50 khoá (quá → 400). Không có body thì chỉ tra cứu cổng.
- **Contract chi tiết:** annotation tại `booking/controller/InvoicePaymentController.java`; Swagger UI —
  `http://localhost:8080/swagger-ui/index.html`.

## Luồng / hành vi

- Kiểm hoá đơn thuộc khách (→ 404) **trước** mọi bước dưới.
- Có `gatewayParams` (thiếu `vnp_TxnRef` hoặc `vnp_SecureHash` thì bỏ qua) → `PaymentService.confirmFromReturn`: đi
  đúng các bước của IPN — chữ ký HMAC-SHA512 (VNPay ký bộ tham số Return URL bằng HashSecret của merchant, cùng sơ
  đồ với IPN), `vnp_TmnCode`, số tiền, idempotent theo `vnp_TxnRef` — thêm điều kiện giao dịch thuộc đúng hoá đơn
  `{invoiceId}`. Thành công → `Success` (audit / case đối soát ghi nguồn `Return URL`); VNPay báo lỗi / khách huỷ →
  `Failed`. Sai chữ ký, khác hoá đơn, lệch số tiền → bỏ qua, không lỗi, không ghi gì. Đây là đường chốt khi chạy
  local: IPN không tới được `localhost` và `querydr` sandbox có lúc trả `99` cho mọi yêu cầu.
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
- FE (`PaymentResultPage`) gửi kèm mọi tham số `vnp_*` trên URL ở mỗi lần kiểm tra (gọi lặp không ghi lại).

## Ghi chú nghiệp vụ

- payOS redirect thẳng về trang FE (`payos.return-url` mặc định `<FRONTEND_URL>/payments/result`); VNPay về trang FE khi
  `VNPAY_RETURN_URL` trỏ `<FRONTEND_URL>/payments/result` (giá trị trong `.env.example`). Route
  `/api/payments/vnpay/return` chỉ trả JSON kiểm chữ ký, không ghi dữ liệu — để test backend khi không chạy FE.

## Liên quan

- Xem thêm: [routes.md](../../routes.md) · [index.md](../../index.md) · [payment.md](../../payment.md)
