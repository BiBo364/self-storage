---
purpose: Module payment — cổng thanh toán (payOS mặc định, VNPay tuỳ chọn): tạo phiên, webhook/IPN, đối soát, huỷ phiên.
---
# Payment — cổng thanh toán

## Cổng đang dùng

`payment.provider` (`PAYMENT_PROVIDER`): **`payos` (mặc định)** | `vnpay`. payOS là cổng mặc định, VNPay là tuỳ
chọn — quyết định của chủ dự án. Chỉ một `PaymentGateway` được dựng
(`@ConditionalOnProperty`); chỉ cổng đang dùng bắt buộc đủ credential — thiếu là app chết lúc khởi động.

| | payOS (`PayOsGateway`) | VNPay (`VnPayGateway`) |
|---|---|---|
| Phương thức | chuyển khoản VietQR | thẻ / QR qua cổng VNPay |
| Mã giao dịch | `orderCode` số: `epochMillis × 100 + 2 số ngẫu nhiên` | `INV{id}T{yyMMddHHmmss}{4 ký tự}` |
| Tạo phiên | `POST /v2/payment-requests`, ký HMAC-SHA256 `amount&cancelUrl&description&orderCode&returnUrl`, `expiredAt` = tạo + 15' | URL ký HMAC-SHA512, `vnp_ExpireDate` = tạo + 15' |
| Xác nhận chính | webhook `POST /api/payments/payos/webhook` (chữ ký trên `data`) | IPN `GET /api/payments/vnpay/ipn` |
| Đối soát dự phòng | `GET /v2/payment-requests/{orderCode}` | `querydr` |
| Huỷ phiên | `POST /v2/payment-requests/{orderCode}/cancel` | không hỗ trợ (phiên tự hết hạn) |
| Sandbox | **không có** — giao dịch là tiền thật | có |
| Hoàn tiền | cổng không có API — FM chuyển khoản tay rồi ghi nhận ([Hoàn tiền thủ công](#hoàn-tiền-thủ-công)) | như payOS |

Nội dung chuyển khoản payOS: ASCII, tối đa 25 ký tự (`TT hoa don {invoiceId}`). `returnUrl`/`cancelUrl` = trang
`<FRONTEND_URL>/payments/result` của FE; FE gọi `POST /api/invoices/{id}/payment-check` để đối soát, không tin query
trên URL.

## Luồng VNPay (khi `payment.provider=vnpay`)

Module `payment` (`backend/src/main/java/vn/lemar/selfstorage/payment/`): cổng thanh toán VNPay
dùng chung cho Flow 1 (thu cọc), Flow 2 (thu tháng đầu), Flow 3 (thu gia hạn). Cả ba flow gọi cùng
một service, chỉ truyền hóa đơn vào.

## Luồng tổng thể

```text
Caller (flow, hoặc FE nút "Thanh toán")
   │  PaymentService.startPayment(invoiceId, request)
   │    → ghi PaymentTransaction(Pending) TRƯỚC (khách bỏ ngang vẫn còn dấu vết đối soát)
   ▼
VnPayGateway.buildPaymentUrl()
   │  ký HMAC-SHA512 (amount ×100, giờ GMT+7, tham số sắp alphabet)
   │  → trả URL để caller redirect khách sang cổng VNPay
   ▼
Khách trả tiền trên cổng VNPay
   │
   ├─► GET /api/payments/vnpay/ipn        (server VNPay gọi; NGUỒN XÁC NHẬN CHÍNH)
   │     verify chữ ký → tìm giao dịch theo vnp_TxnRef → check vnp_TmnCode
   │     → check vnp_Amount → guard còn Pending → ghi Success/Failed
   │     → bắn PaymentSucceeded / PaymentFailed → trả {"RspCode":"00"}
   │
   └─► GET /api/payments/vnpay/return     (redirect trình duyệt; CHỈ hiển thị)
         kiểm chữ ký để hiển thị validSignature — không ghi DB, không bắn event
   ▼
Module sở hữu Invoice nghe PaymentSucceeded → chuyển Invoice sang Paid
   (booking/application/InvoicePaymentSettlement — AFTER_COMMIT + REQUIRES_NEW)
```

## Hành vi

- **Đồng hồ của hóa đơn: `Invoice.due_date`.** Quá hạn là mốc duy nhất của "quá hạn thanh toán" —
  `BookingExpiryJob` (5 phút) chuyển hoá đơn `Unpaid` quá `due_date` sang `Expired` rồi xử lý theo loại hoá đơn
  ([booking/deposit-payment.md › Cron Flow 1](booking/deposit-payment.md#cron-flow-1)).
  - **LƯU Ý:** đừng nhầm với cửa sổ phiên VNPay (`vnp_ExpireDate` = lúc tạo URL + `PAYMENT_WINDOW`,
    hiện 15') — cửa sổ chỉ là giới hạn kỹ thuật (hết cửa sổ là cổng không cho trả tiếp); khách bấm
    trả sát `due_date` vẫn có đủ 15'.
- **Đối soát phiên im lặng.** Khách bỏ ngang (đóng browser, IPN không về) để lại giao dịch `Pending`.
  Job định kỳ quét `Pending` quá ngưỡng và gọi `querydr`: cổng có giao dịch → chốt theo trạng thái
  thật; không có → đóng `Failed` + `failure_reason` ghi rõ phiên hết hạn.
- **Xác nhận muộn (lưới an toàn).** Hi hữu — khách đã trả trong cửa sổ hợp lệ nhưng hệ thống chỉ biết
  sau khi invoice/đơn đã hủy (chỉ khi job/server lỗi dài): vẫn ghi `Success` + đủ dấu vết, log ERROR
  để vận hành xử lý thủ công (hoàn tiền hoặc xếp chỗ khác). Không tự mở lại đơn.

## Quy tắc bất biến

- **IPN là nguồn xác nhận thanh toán chính.** Return URL nằm trên thanh địa chỉ, khách sửa được —
  không bao giờ dùng để ghi nhận đã trả tiền. Khi IPN không về, chỉ `querydr` (kênh server-to-server
  cùng chữ ký HMAC) được dùng làm nguồn đối soát dự phòng — không có nguồn nào khác.
- **Đóng phiên dựa trên kết quả `querydr`, không dựa trên đồng hồ.** Ngưỡng thời gian chỉ để chọn lúc
  hỏi cổng; `querydr` lỗi thì giữ `Pending`, lần chạy sau đối soát tiếp.
- **Idempotent theo `vnp_TxnRef`.** VNPay retry tối đa 10 lần (cách 5 phút) và có thể gọi song song;
  row bị khóa `PESSIMISTIC_WRITE` khi đọc, lần thứ hai trả `02` và không ghi gì. Unique constraint
  không thay được khóa vì cả hai cùng update một row.
- **Thứ tự kiểm trong IPN:** chữ ký (`97`) → tìm giao dịch (`01`) → `vnp_TmnCode` (`01`)
  → `vnp_Amount` (`04`) → guard trạng thái (`02`) → OK (`00`). Sai chữ ký thì không để lộ gì về
  giao dịch.
- **Số tiền:** cổng nhận `amount × 100`; đối chiếu khi IPN về. Nguồn số tiền tin cậy là **hóa đơn
  trong DB** — booking truyền `invoice.amount` đọc từ DB lúc mở phiên
  (`InvoicePaymentService` → `PaymentService.startInvoicePayment`), không nhận số tiền từ client.
- **Ranh giới module:** `PaymentTransaction.invoiceId` là `Long`, không quan hệ JPA với bảng
  `invoices`; module khác giao tiếp qua event, không import chéo.
- **Listener event bắt buộc `AFTER_COMMIT`.** `PaymentSucceeded`/`PaymentFailed` được publish
  *trong* transaction IPN — module nghe phải dùng `@TransactionalEventListener(phase = AFTER_COMMIT)`
  (hoặc `REQUIRES_NEW`). Listener đồng bộ mặc định sẽ thấy state chưa commit, và exception từ
  listener sẽ rollback giao dịch IPN khiến VNPay retry đủ 10 lần.
- **Không dùng `ApiEnvelope` cho IPN.** VNPay đọc phản hồi theo đúng hai key `RspCode`/`Message` ở
  mức gốc — đây là contract của bên thứ ba.

## Webhook payOS

- `PaymentService.handleNotification(body)`: `PayOsGateway.parseNotification` kiểm chữ ký (`data` sắp key alphabet,
  `key=value` nối `&`, `null` → rỗng, HMAC-SHA256 bằng checksum key) → tìm giao dịch theo `orderCode` (khoá row) →
  số tiền khớp → còn `Pending` thì `Success` + `PaymentSucceeded` (hoặc `Failed` + `PaymentFailed`).
- Kết quả: `ACCEPTED` / `ALREADY_SETTLED` (gọi lặp) / `NOT_FOUND` → HTTP 200; `INVALID_SIGNATURE` /
  `INVALID_AMOUNT` → 400. Request thử của payOS khi khai báo webhook (mã không tồn tại) vẫn 200.
- Giao dịch đã đóng `Failed` (phiên đã huỷ) mà cổng vẫn báo đã thu → log ERROR "Thanh toán muộn…", trả
  `ALREADY_SETTLED`, hoàn tiền thủ công (khách chuyển khoản tay theo QR cũ sau khi link đã huỷ).
- Khai URL webhook (https công khai) ở my.payos.vn → Kênh thanh toán. Chạy local không có URL public thì
  `payment-check` + job đối soát vẫn chốt được giao dịch.

## Khoá phiên & huỷ phiên

- **Phiên đang mở = còn giao dịch `Pending` tạo trong 24 giờ gần nhất** (`PaymentService.invoicesWithOpenSession`,
  `OPEN_SESSION_LIMIT` = 24h), cho tới khi cổng xác nhận phiên đóng (đã trả / huỷ / hết hạn — qua webhook,
  `payment-check` hoặc job đối soát). Trong 24 giờ đó khoang không thể có hai phiên trả được tiền cùng lúc.
- Giao dịch `Pending` quá 24 giờ (cổng không trả lời được, đối soát lỗi liên tục) **không còn tính là phiên mở**: cron
  hết hạn hoá đơn / huỷ đơn / đóng phiên bàn giao và nút thanh toán lại không bị chặn mãi bởi một giao dịch treo.
  `PaymentReconcileJob` vẫn tiếp tục hỏi cổng về giao dịch đó; nếu sau đó cổng báo đã thu tiền thì đi nhánh
  [xác nhận muộn](#hành-vi) (ghi `Success`, log ERROR, FM hoàn tiền thủ công).
- `PaymentService.cancelSession(txnRef, reason)`: cổng xác nhận đã huỷ → khoá row, còn `Pending` thì `Failed`
  "Đã huỷ phiên: …" + `PaymentFailed` (booking ghi audit `PAYMENT_FAILED`). Cổng không huỷ được → giữ `Pending`,
  webhook / đối soát quyết.
- `PaymentSessionCloser`:
  - `closeAfterCommit(invoiceIds, reason)` — hoá đơn/đơn bị huỷ: gọi cổng **sau commit**, best-effort, mỗi giao dịch
    một transaction `REQUIRES_NEW`. Lỗi gọi cổng không rollback việc huỷ (huỷ phía mình là nguồn sự thật).
  - `closeNow(invoiceId, reason)` — khách bấm thanh toán lại: đối soát trước (đã trả thì chốt luôn), chưa trả thì huỷ
    phiên cũ rồi mới mở phiên mới.

## Hoàn tiền thủ công

payOS / VNPay không có API hoàn tiền dùng được — FM chuyển khoản trả khách ngoài hệ thống rồi ghi nhận lại qua
`GET` / `POST /api/fm/invoices/{invoiceId}/refunds`
([routes/booking/fm-invoice-refunds.md](routes/booking/fm-invoice-refunds.md)). Áp dụng cho mọi trường hợp hệ thống
báo "cần hoàn": hoàn cọc khi trả kho, cọc thừa khi đổi khoang, tiền vào sau khi hoá đơn đã đóng, huỷ sau khi đã trả
tháng đầu.

- `booking/application/ManualRefundService` kiểm quyền `payment_transaction.refund` theo cơ sở của hoá đơn, validate
  (số tiền > 0, ghi chú bắt buộc ≤ 500 ký tự → 400), khoá hoá đơn rồi gọi `PaymentService.recordManualRefund`.
- `recordManualRefund` (propagation `MANDATORY`): số tiền > **còn hoàn được** → 409; ghi `PaymentTransaction`
  `direction = REFUND`, `status = Success`, `txn_ref = RF{invoiceId}-{epochMillis}`, nội dung = ghi chú,
  `paid_at` = lúc ghi.
- **Còn hoàn được** (`PaymentService.refundable`) = tổng giao dịch `PAY` `Success` của hoá đơn − tổng `REFUND`
  `Success`. Hoá đơn không thu qua cổng (đối trừ cọc khi trả kho) có số này = 0.
- Audit `MANUAL_REFUND_RECORDED` (entity `PaymentTransaction`, `new_value` = `invoiceId`, `invoiceCode`, `amount`;
  `reason` = ghi chú).
- Ghi nhận hoàn tiền không đổi trạng thái hoá đơn, đơn hay hợp đồng.

## Cấu hình

- `PAYMENT_PROVIDER` — `payos` (mặc định) | `vnpay`.
- payOS (bắt buộc khi `payos`): `PAYOS_CLIENT_ID`, `PAYOS_API_KEY`, `PAYOS_CHECKSUM_KEY`; tuỳ chọn `PAYOS_BASE_URL`
  (mặc định `https://api-merchant.payos.vn`), `PAYOS_RETURN_URL` / `PAYOS_CANCEL_URL` (mặc định
  `<FRONTEND_URL>/payments/result`).
- VNPay (bắt buộc khi `vnpay`): `VNPAY_TMN_CODE`, `VNPAY_HASH_SECRET`, `VNPAY_PAY_URL`, `VNPAY_RETURN_URL`;
  `VNPAY_API_URL` tuỳ chọn (API `querydr`, mặc định sandbox). Hướng dẫn sandbox: [`docs/vnpay-testing.md`](../../docs/vnpay-testing.md).

## Đối soát `querydr`

- `VnPayGateway.query(txnRef, createdAt, orderInfo, ip)` gọi API merchant (`vnpay.api-url`, mặc định
  `https://sandbox.vnpayment.vn/merchant_webapi/api/transaction`), ký chuỗi `|` theo thứ tự cố định của VNPay
  (`requestId|version|command|tmnCode|txnRef|transactionDate|createDate|ipAddr|orderInfo`) và kiểm chữ ký response.
- `vnp_TransactionDate` = `PaymentTransaction.created_at` — `startPayment` truyền đúng mốc này làm `vnp_CreateDate`
  (cắt về giây) nên cổng tìm lại được phiên.
- `PaymentService.reconcile(txnRef)` (khoá row như IPN): cổng báo đã trả + số tiền khớp → `Success` +
  `PaymentSucceeded`; số tiền lệch → giữ `Pending` + log ERROR; cổng báo lỗi → `Failed` + `PaymentFailed`; cổng
  không có giao dịch (`91`) và đã quá 20' (cửa sổ 15' + đệm 5') → `Failed` "Phiên thanh toán hết hạn…"; lỗi mạng /
  sai chữ ký → giữ `Pending`.
- Hai caller: `POST /api/invoices/{id}/payment-check` (khách quay về) và `PaymentReconcileJob` (5', giao dịch
  `Pending` quá 20').
- Module payment mở named interface `payment::application`, `payment::event`, `payment::dto` cho booking/handover;
  `PaymentSnapshot.status` là chuỗi DB (`Pending`/`Success`/`Failed`), không lộ enum domain.

## Trạng thái hiện tại

- Khởi tạo thanh toán `POST /api/invoices/{invoiceId}/pay` và chốt hoá đơn sau khi cổng xác nhận: xem
  [booking/deposit-payment.md](booking/deposit-payment.md).
- Cron chờ phiên thanh toán đang mở đóng xong mới hết hạn hoá đơn / huỷ đơn.
- `PaymentFailed` có một listener — `booking/application/PaymentFailureAudit` (AFTER_COMMIT + REQUIRES_NEW) ghi audit
  `PAYMENT_FAILED`; hoá đơn giữ `Unpaid`, khách mở phiên mới được.
- payOS: đã chạy thật tạo link / tra cứu / huỷ link; chưa có giao dịch chuyển khoản thật và chưa nhận webhook thật
  (cần URL public).
- VNPay: chưa kiểm chứng `querydr` với terminal sandbox thật (cần credential VNPay sandbox hợp lệ).
