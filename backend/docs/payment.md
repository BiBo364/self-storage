---
purpose: Module payment — cổng thanh toán (VNPay mặc định, payOS tuỳ chọn): tạo phiên, IPN/webhook, tra cứu khi khách quay về, local repair, đối soát thủ công, huỷ phiên và hoàn tiền thủ công.
---
# Payment — cổng thanh toán

Module `payment` (`backend/src/main/java/vn/lemar/selfstorage/payment/`): cổng thanh toán dùng chung cho Flow 1
(thu cọc), Flow 2 (thu tháng đầu), Flow 3/6 (gia hạn, tiền thuê, phí quá hạn, quyết toán trả kho). Mọi flow gọi cùng
một service, chỉ truyền hoá đơn vào.

## Cổng đang dùng

`payment.provider` (`PAYMENT_PROVIDER`): **`vnpay` (mặc định)** | `payos`. Chỉ một `PaymentGateway` được dựng
(`@ConditionalOnProperty`); chỉ cổng đang dùng bắt buộc đủ credential — thiếu là app chết lúc khởi động.

| | VNPay (`VnPayGateway`) | payOS (`PayOsGateway`) |
|---|---|---|
| Phương thức | thẻ / QR qua cổng VNPay | chuyển khoản VietQR |
| Mã giao dịch | `INV{id}T{yyMMddHHmmss}{4 ký tự}` (hoá đơn cọc: `INV{id}T{yyyyMMddHHmmss}{5 ký tự}`) | `orderCode` số: `epochMillis × 100 + 2 số ngẫu nhiên` |
| Tạo phiên | URL ký HMAC-SHA512, `vnp_ExpireDate` = tạo + 15' | `POST /v2/payment-requests`, ký HMAC-SHA256 `amount&cancelUrl&description&orderCode&returnUrl`, `expiredAt` = tạo + 15' |
| Xác nhận chính | IPN `GET /api/payments/vnpay/ipn` | webhook `POST /api/payments/payos/webhook` (chữ ký trên `data`) |
| Tra cứu khi khách quay về | `querydr` | `GET /v2/payment-requests/{orderCode}` |
| Huỷ phiên | không hỗ trợ (phiên tự hết hạn) | `POST /v2/payment-requests/{orderCode}/cancel` |
| Sandbox | có | **không có** — giao dịch là tiền thật |
| Hoàn tiền | cổng không có API dùng được — FM chuyển khoản tay rồi ghi nhận ([Hoàn tiền thủ công](#hoàn-tiền-thủ-công)) | như VNPay |

Nội dung chuyển khoản payOS: ASCII, tối đa 25 ký tự (`TT hoa don {invoiceId}`). `returnUrl`/`cancelUrl` = trang
`<FRONTEND_URL>/payments/result` của FE; FE gọi `POST /api/invoices/{id}/payment-check`, không tin query trên URL.

## Luồng tổng thể

```text
Caller (booking, FE nút "Thanh toán")
   │  PaymentService.startPayment / startInvoicePayment(invoiceId, …)
   │    → ghi PaymentTransaction(Pending) TRƯỚC (khách bỏ ngang vẫn còn dấu vết đối soát)
   ▼
PaymentGateway.buildPaymentUrl()   (mốc tạo phiên = created_at của giao dịch)
   │  VNPay: ký HMAC-SHA512 (amount ×100, giờ GMT+7, tham số sắp alphabet); payOS: tạo payment link
   │  → cache URL Redis payment:url:{invoiceId} (TTL 15') → trả URL để caller redirect khách
   ▼
Khách trả tiền trên cổng
   │
   ├─► IPN VNPay / webhook payOS        (server cổng gọi; NGUỒN XÁC NHẬN CHÍNH)
   │     verify chữ ký → tìm giao dịch → (VNPay: vnp_TmnCode) → số tiền → còn Pending
   │     → khoá invoice → Success (hoặc Duplicate) / Failed → event
   │
   ├─► POST /api/invoices/{id}/payment-check  (khách quay về trang kết quả)
   │     hỏi cổng server-to-server về giao dịch mới nhất; kết quả chốt như IPN
   │
   └─► GET /api/payments/vnpay/return   (redirect trình duyệt; CHỈ hiển thị)
   ▼
booking nghe PaymentSucceeded → chuyển Invoice sang Paid, chốt đơn và khoang theo loại hoá đơn
(routes/booking/invoice-pay.md › Nhánh thành công)
```

## Các lớp xác nhận & đối soát

Không có job tự gọi cổng. Các lớp không thay thế nhau; mỗi lớp chỉ xử lý state mà lớp trước bỏ lại.

| Lớp | Khi nào chạy | Chặn được | Không chặn được |
|---|---|---|---|
| **1. IPN / webhook** | VNPay gọi `GET /api/payments/vnpay/ipn`; payOS gọi `POST /api/payments/payos/webhook` | sai chữ ký (`97` / 400), sai merchant/amount (`01`/`04` / 400), gọi lặp (`02` / 200); ghi `Success`/`Failed` | xác nhận không về; listener booking lỗi sau khi đã commit |
| **2. Tra cứu khi khách quay về** | FE gọi `POST /api/invoices/{id}/payment-check` | giao dịch `Pending` mà cổng đã có kết quả (chạy local không có URL public vẫn chốt được) | khách không quay về trang kết quả |
| **3. Local repair** | `DepositStateRepairJob` mỗi 5 phút | `PaymentTransaction(PAY/Success)` + invoice cọc `Unpaid` do listener lỗi; replay listener idempotent | giao dịch còn `Pending`; không tự gọi cổng/tạo giao dịch |
| **4. Đối soát thủ công** | `PendingPaymentWatchdogJob` tạo case; ADMIN tra portal rồi reconcile | `Pending` không ai chốt; chốt `Success`/`Failed`; Success thứ hai thành `Duplicate`, notify FM | không tự retry; không tự hoàn tiền; job chỉ alert, không tự chốt |

**Nhớ nhanh:** cổng gọi về → lớp 1. Khách quay về → lớp 2. Success đã ghi nhưng invoice còn `Unpaid` → lớp 3.
Không ai chốt, transaction còn `Pending` → lớp 4.

Lớp 4 dùng **watchdog + case**: `PendingPaymentWatchdogJob` mỗi 5 phút tạo case cho
`PAY/Pending` quá 15 phút, gửi notification cho ADMIN; ADMIN xem danh sách case rồi gọi
`POST /api/admin/payments/{txnRef}/reconcile`. Job không đổi trạng thái và không gọi cổng.
Case/evidence chi tiết ở [routes/payment/admin-pending-payments.md](routes/payment/admin-pending-payments.md).
Lớp 1/2 chốt giao dịch thì case tương ứng cũng được đóng (`resolved_by_email` = `IPN` / `webhook` /
`payment-check` / `cancel-session`). Không lớp nào tự hoàn tiền.

## Hành vi

- **Đồng hồ của hóa đơn: `Invoice.due_date`.** Quá hạn là mốc duy nhất của "quá hạn thanh toán" —
  `BookingExpiryJob` (5 phút) chuyển hoá đơn `Unpaid` quá `due_date` sang `Expired` rồi xử lý theo loại hoá đơn
  ([booking/deposit-payment.md › Cron Flow 1](booking/deposit-payment.md#cron-flow-1)).
  - **LƯU Ý:** đừng nhầm với cửa sổ phiên ở cổng (`vnp_ExpireDate` / `expiredAt` = lúc tạo + `PaymentSession.WINDOW`,
    hiện 15') — cửa sổ chỉ là giới hạn kỹ thuật (hết cửa sổ là cổng không cho trả tiếp); khách bấm trả sát
    `due_date` vẫn có đủ 15'.
- **Phiên đang mở = giao dịch `Pending` tạo trong 15'** (`PaymentSession.WINDOW`; `PaymentSessionQuery`,
  `PaymentService.invoicesWithOpenSession`). Cùng một con số khoá khoang ở `/pay`, cho cron hết hạn hoá đơn / huỷ
  đơn / đóng phiên bàn giao đợi phiên đóng, và cho tiền thuê/phạt không đổi số tiền khi khách đang trả.
- **Xác nhận muộn / unit đã mất.** Tiền đã vào thì invoice vẫn `Paid`. Nếu khoang không còn giữ được, không reserve
  unit, order giữ trạng thái cũ, invoice có `refund_status=Required` + `refund_reason=UNIT_UNAVAILABLE`; ghi audit
  `INVOICE_REFUND_REQUIRED`, notify FM/customer và xử lý hoàn tiền thủ công.
- **Tiền vào giao dịch đã đóng.** Giao dịch đã `Failed` (phiên bị huỷ ở cổng, cổng báo lỗi) mà IPN/webhook sau đó báo
  đã thu (khách quét QR cũ) → log ERROR, khoá invoice: chưa có `PAY/Success` thì `Failed → Success`
  (`failure_reason` giữ lý do đóng cũ, tiền tố "Thanh toán muộn sau khi phiên đã đóng"), audit `PAYMENT_SUCCEEDED`
  (`old_value.status = Failed`), phát `PaymentSucceeded`; đã có thì `Duplicate`. Phản hồi vẫn là "đã xử lý"
  (`02` / `ALREADY_SETTLED`). Booking chốt hoá đơn nếu còn `Unpaid`; hoá đơn đã `Canceled`/`Expired` thì đánh dấu
  `refund_status=Required` (`INVOICE_CLOSED`) và báo FM hoàn.
- **Sửa state khi listener lỗi.** `PaymentTransaction(PAY/Success)` là bản ghi bền.
  `DepositStateRepairJob` quét invoice `Unpaid` (`Deposit`/`DepositSupplement`) có giao dịch
  `PAY/Success`, rồi gọi lại `DepositPaymentListener` (idempotent). Không thêm bảng outbox.
- **URL phiên cache Redis.** Key `payment:url:{invoiceId}`, TTL 15 phút, khớp hạn phiên ở cổng.
  `/pay` lần nữa trên cùng hoá đơn trả lại URL cũ; cache mất thì `resumePayment` dựng lại URL từ
  transaction Pending — cùng mã giao dịch, mốc tạo/hết hạn gốc, không tạo giao dịch mới. Không còn Pending trong
  15 phút, hoặc cổng không dựng lại được (payOS không tạo lại link cho cùng `orderCode`) thì caller trả 409. Cache
  không phải khóa — khóa phiên vẫn suy từ `PaymentTransaction(Pending)`.
- **`vnp_OrderInfo` bỏ dấu trước khi ký.** VNPay chỉ hiển thị được ASCII; chuỗi có dấu bị encode
  thành `?`. `VnPayGateway` chuẩn hoá về không dấu trước khi ghép tham số, nên chuỗi ký và chuỗi
  khách thấy khớp nhau: `Đặt cọc khoang chứa A-01` → `Dat coc khoang chua A-01`.
- **Timeout gọi cổng.** Tạo link, tra cứu, huỷ phiên (payOS và VNPay `querydr`) có timeout kết nối 5 giây, đọc
  15 giây (`GatewayHttp`): các lời gọi này chạy trong transaction đang khoá đơn / hoá đơn / giao dịch, cổng treo
  không được giữ khoá mãi. Hết timeout → lỗi; tra cứu giữ `Pending`, tạo link thì transaction rollback.

## Quy tắc bất biến

- **IPN / webhook là nguồn xác nhận thanh toán chính.** Return URL nằm trên thanh địa chỉ, khách sửa được —
  không bao giờ dùng để ghi nhận đã trả tiền. Nguồn dự phòng: tra cứu server-to-server có chữ ký khi khách quay về
  (`payment-check`) và **đối soát thủ công qua portal**; không có job tự động gọi cổng.
- **Không tự đóng `Pending` theo đồng hồ.** Giao dịch Pending chỉ đổi trạng thái khi cổng báo kết quả (IPN,
  webhook, tra cứu, cổng xác nhận đã huỷ link) hoặc ops chốt qua endpoint ADMIN, có audit + event như IPN.
- **Idempotent theo mã giao dịch.** VNPay retry tối đa 10 lần (cách 5 phút) và có thể gọi song song;
  row bị khóa `PESSIMISTIC_WRITE` khi đọc, lần thứ hai trả `02` và không ghi gì. Unique constraint
  không thay được khóa vì cả hai cùng update một row.
- **Thứ tự kiểm trong IPN:** chữ ký (`97`) → tìm giao dịch (`01`) → `vnp_TmnCode` (`01`)
  → `vnp_Amount` (`04`) → guard trạng thái (`02`) → OK (`00`). Sai chữ ký thì không để lộ gì về
  giao dịch.
- **Số tiền:** VNPay nhận `amount × 100`; đối chiếu khi xác nhận về. Nguồn số tiền tin cậy là **hóa đơn
  trong DB** — booking đọc `Invoice` rồi truyền amount vào `startPayment` / `startInvoicePayment`; `payment`
  không import `booking` nên không tự đọc lại.
- **Không mở phiên mới khi đã có giao dịch `PAY/Success`.** Cổng xác nhận ghi giao dịch trước khi listener
  booking chạy; nếu listener chưa chạy/lỗi, invoice vẫn `Unpaid`. `/pay` kiểm giao dịch thành công
  và trả 409, không thu lần hai; local repair hoặc đối soát thủ công chốt phần còn lại.
- **Một invoice tối đa một `PAY/Success`.** Advisory lock theo invoice là lớp chính; partial unique
  index `(invoice_id) WHERE direction = 'PAY' AND status = 'Success'` (V18) là chốt cuối ở DB.
  Mọi nguồn xác nhận đi chung một đường (`PaymentService.confirmSuccess`): nếu invoice đã có `PAY/Success`,
  transaction mới chuyển `Duplicate` — không phát `PaymentSucceeded`, không đụng invoice/order/unit. Ghi audit
  `PAYMENT_DUPLICATE`, notify FM để hoàn tiền thủ công. IPN trả `02`; manual trả `200` với status `DUPLICATE`.
- **Ranh giới module:** `PaymentTransaction.invoiceId` là `Long`, không quan hệ JPA với bảng
  `invoices`; module khác giao tiếp qua event, không import chéo. Payment mở named interface
  `payment::application`, `payment::events`, `payment::dto`; `PaymentSnapshot.status` là chuỗi DB
  (`Pending`/`Success`/`Failed`/`Duplicate`), không lộ enum domain.
- **Listener event bắt buộc `AFTER_COMMIT`.** `PaymentSucceeded`/`PaymentFailed`/`PaymentDuplicateDetected` được
  publish *trong* transaction xác nhận — module nghe phải dùng
  `@TransactionalEventListener(phase = AFTER_COMMIT)` (hoặc `REQUIRES_NEW`). Listener đồng bộ mặc định sẽ thấy
  state chưa commit, và exception từ listener sẽ rollback giao dịch xác nhận khiến cổng retry.
- **Không dùng `ApiEnvelope` cho IPN.** VNPay đọc phản hồi theo đúng hai key `RspCode`/`Message` ở
  mức gốc — đây là contract của bên thứ ba.

## Webhook payOS

- `PaymentService.handleNotification(body)`: `PayOsGateway.parseNotification` kiểm chữ ký (`data` sắp key alphabet,
  `key=value` nối `&`, `null` → rỗng, HMAC-SHA256 bằng checksum key) → tìm giao dịch theo `orderCode` (khoá row) →
  số tiền khớp → còn `Pending` thì chốt như IPN (`Success`/`Duplicate` hoặc `Failed`).
- Kết quả: `ACCEPTED` / `ALREADY_SETTLED` (gọi lặp, tiền về muộn) / `NOT_FOUND` → HTTP 200; `INVALID_SIGNATURE` /
  `INVALID_AMOUNT` → 400. Request thử của payOS khi khai báo webhook (mã không tồn tại) vẫn 200.
- Khai URL webhook (https công khai) ở my.payos.vn → Kênh thanh toán. Chạy local không có URL public thì
  `payment-check` chốt được giao dịch.

## Tra cứu cổng khi khách quay về

- `PaymentService.reconcileLatest(invoiceId)` → `reconcile(txnRef)` (khoá row như IPN): giao dịch đã chốt → trả
  nguyên trạng, không gọi cổng. Còn `Pending` → `PaymentGateway.query`:
  - cổng báo đã trả + số tiền khớp → chốt như IPN (`Success`/`Duplicate`), đóng case;
  - cổng báo lỗi / huỷ → `Failed` + `PaymentFailed`, đóng case;
  - số tiền lệch → giữ `Pending` + log ERROR; chưa có kết quả / không có giao dịch / lỗi mạng / sai chữ ký → giữ
    `Pending`.
- VNPay `querydr`: `VnPayGateway.query` gọi API merchant (`vnpay.api-url`, mặc định
  `https://sandbox.vnpayment.vn/merchant_webapi/api/transaction`), ký chuỗi `|` theo thứ tự cố định
  (`requestId|version|command|tmnCode|txnRef|transactionDate|createDate|ipAddr|orderInfo`) và kiểm chữ ký response;
  `vnp_TransactionDate` = `PaymentTransaction.created_at` (cùng mốc `vnp_CreateDate` lúc tạo phiên). payOS: `PAID` =
  đã trả; `CANCELLED`/`EXPIRED`/`FAILED` = đóng không thu tiền; còn lại = đang chờ.

## Huỷ phiên ở cổng

- `PaymentService.cancelSession(txnRef, reason)`: cổng xác nhận đã huỷ → khoá row, còn `Pending` thì `Failed`
  "Đã huỷ phiên: …" + audit `PAYMENT_FAILED` + `PaymentFailed`. Cổng không huỷ được (VNPay không hỗ trợ, đã trả, lỗi
  mạng) → giữ `Pending`.
- `PaymentSessionCloser.closeAfterCommit(invoiceIds, reason)` — hoá đơn/đơn bị huỷ (cascade huỷ đơn, hoá đơn cọc cạnh
  tranh, hợp đồng huỷ, hoá đơn thay thế): gọi cổng **sau commit**, best-effort, mỗi giao dịch một transaction
  `REQUIRES_NEW`. Lỗi gọi cổng không rollback việc huỷ (huỷ phía mình là nguồn sự thật). Khách bấm thanh toán lại
  trên cùng hoá đơn **không** huỷ phiên cũ — `/pay` trả lại URL cũ.

## Hoàn tiền thủ công

Cổng không có API hoàn tiền dùng được — một cơ chế cho mọi khoản phải trả lại khách:

1. **Đánh dấu cần hoàn** — hoá đơn `refund_status = Required` (`refund_reason`, `refund_requested_at`,
   `refund_amount` = số phải hoàn, rỗng = cả hoá đơn), audit `INVOICE_REFUND_REQUIRED`:

   | `refund_reason` | Khi nào | Ghi ở |
   |---|---|---|
   | `UNIT_UNAVAILABLE` | cọc đã vào nhưng khoang không còn giữ được | `DepositPaymentListener` |
   | `INVOICE_CLOSED` | tiền vào hoá đơn đã `Canceled`/`Expired` | `DepositPaymentListener`, `InvoicePaymentSettlement` |
   | `UNIT_CHANGE_EXCESS` | đổi sang khoang có cọc thấp hơn — phần dư | `ProposalService.issueDeposit` |
   | `DEPOSIT_UNSERVED` | đơn `Pending` đã trả cọc hết hạn mà chưa xếp được khoang | `BookingExpiryService` |
   | `HANDOVER_CANCELED` | tháng đầu đã trả nhưng hợp đồng chưa bàn giao bị huỷ | `HandoverOrderService.closeContractInvoices` |
   | `CHECKOUT_DEPOSIT` | trả kho: cọc trừ các khoản phát sinh còn dư | `CheckoutService` |

   Hoàn một phần cọc (`UNIT_CHANGE_EXCESS`, `DEPOSIT_UNSERVED`, `CHECKOUT_DEPOSIT`) chia vào các hoá đơn
   `Deposit`/`DepositSupplement` `Paid` của đơn, hoá đơn mới nhất trước, mỗi hoá đơn tối đa số còn hoàn được qua cổng
   (`InvoiceRefundService.requireDeposit`). Thanh toán trùng (`Duplicate`) không đổi `refund_status`; FM nhận
   `PAYMENT_DUPLICATE_REFUND_REQUIRED` và hoàn trên chính hoá đơn đó.
2. **Hàng đợi** — FM: `GET /api/fm/invoices/refunds` (tab "Hoàn tiền" màn hoá đơn cơ sở); ADMIN:
   `GET /api/admin/payments/refunds`. Cả hai đọc invoice `Required`/`Processing`.
3. **Ghi nhận** — FM chuyển khoản trả khách ngoài hệ thống rồi ghi lại qua
   `GET` / `POST /api/fm/invoices/{invoiceId}/refunds` ([routes/booking/fm-invoice-refunds.md](routes/booking/fm-invoice-refunds.md)):
   - `booking/application/ManualRefundService` kiểm quyền `payment_transaction.refund` theo cơ sở của hoá đơn,
     validate (số tiền > 0, ghi chú bắt buộc ≤ 500 ký tự → 400), khoá hoá đơn rồi gọi
     `PaymentService.recordManualRefund`.
   - `recordManualRefund` (propagation `MANDATORY`): số tiền > **còn hoàn được** → 409; ghi `PaymentTransaction`
     `direction = REFUND`, `status = Success`, `txn_ref = RF{invoiceId}-{epochMillis}`, nội dung = ghi chú,
     `paid_at` = lúc ghi.
   - **Còn hoàn được** (`PaymentService.refundable`) = tổng `PAY` `Success` + `Duplicate` của hoá đơn − tổng
     `REFUND` `Success`. Hoá đơn không thu qua cổng (đối trừ cọc khi trả kho) có số này = 0.
   - Hoá đơn đang `Required`/`Processing`: tổng đã hoàn ≥ số phải hoàn → `Refunded` + `refunded_at`, chưa đủ →
     `Processing`. Hoá đơn `None` (FM hoàn chủ động) giữ `None`.
   - Audit `MANUAL_REFUND_RECORDED` (entity `PaymentTransaction`, `new_value` = `invoiceId`, `invoiceCode`, `amount`,
     `invoiceRefundStatus`; `reason` = ghi chú). Không đổi `Invoice.status`, đơn hay hợp đồng.

## Đối soát thủ công

- **Endpoint:** `POST /api/admin/payments/{txnRef}/reconcile`; quyền `ADMIN`; contract chi tiết ở
  [routes/payment/manual-reconcile.md](routes/payment/manual-reconcile.md).
- **Case Pending:** `PendingPaymentWatchdogJob` tạo case `Open`; ADMIN xem
  `GET /api/admin/payments/reconciliation?view=open|history`; reconcile đóng case và lưu
  note/evidence. `/api/admin/payments/pending` là alias cũ của `view=open`.
  Evidence là URL file Admin upload qua `POST /api/media/uploads`, optional.
- **Chỉ giao dịch `Pending`.** Row bị khoá `PESSIMISTIC_WRITE`; invoice được khoá thêm bằng
  advisory lock để hai manual request cùng invoice serialize. Giao dịch đã `Success`/`Failed`/
  `Duplicate`, hoặc `gatewayTransactionNo` trùng giao dịch khác trả `409`, không ghi thêm.
  Invoice đã có `PAY/Success` khác trả `200` với status `DUPLICATE`, không phải `409`.
  `SUCCESS` bắt buộc `paidAt` (không tương lai, không trước `createdAt` — so sánh tới giây) và
  `gatewayTransactionNo`; thiếu/sai trả `400`. Amount lấy từ `PaymentTransaction`, không nhận từ
  request.
- **Ghi như IPN:** audit `PAYMENT_SUCCEEDED`/`PAYMENT_FAILED` (`newValue` của Success gồm `gatewayTransactionNo` +
  `paidAt`; reason của manual ghi email admin), phát `PaymentSucceeded`/`PaymentFailed`; listener booking cập nhật
  invoice/order/unit.
- **FM xem danh sách:** `GET /api/fm/invoices?facilityId=...` trả hoá đơn cơ sở kèm
  `paymentStatus`, `pendingAgeMinutes`, `needsReconciliation`; đây là tín hiệu để FE highlight,
  không tự đối soát.
- **Quy trình ops:** lọc `PaymentTransaction(Pending)` quá 15'; tra portal cổng theo mã giao dịch;
  nếu đã trả → gọi endpoint với `outcome=SUCCESS`, `paidAt`, `gatewayTransactionNo`; nếu không →
  `outcome=FAILED` + note. Không sửa DB tay.
- **Giới hạn:** không tự retry reconcile; watchdog chỉ tạo case/alert, không tự chốt.
  Giao dịch `Pending` không có ai tra vẫn nằm nguyên — case giúp phát hiện, không thay ops.
  Evidence chỉ là bằng chứng hỗ trợ; nguồn sự thật vẫn là transaction/audit.

## Known limitations

- **Không có đối soát phiên tự động.** Xác nhận không về và khách không quay về trang kết quả →
  `PaymentTransaction(Pending)` nằm nguyên; invoice/order/unit giữ state cũ; ops phải tra portal rồi chốt qua
  endpoint ADMIN. Hết cửa sổ 15' giao dịch không còn tính là phiên mở, nên cron hết hạn hoá đơn / huỷ đơn vẫn chạy;
  tiền về sau đó đi nhánh [tiền vào giao dịch đã đóng / hoá đơn đã đóng](#hành-vi).
- **Listener lỗi sau xác nhận** được local repair xử lý tối đa mỗi 5 phút cho hoá đơn cọc; nếu job không cứu được
  thì vẫn cần đối soát thủ công.
- **Không có hoàn tiền tự động.** Mọi khoản hoàn đi qua [Hoàn tiền thủ công](#hoàn-tiền-thủ-công).
- **Giả định một instance.** `DepositStateRepairJob` và `PendingPaymentWatchdogJob` chạy mỗi 5 phút;
  chưa cấu hình ShedLock vì deployment hiện tại chỉ một instance. Nếu scale ngang phải bổ sung khóa job.
- payOS: đã chạy thật tạo link / tra cứu / huỷ link; chưa nhận webhook thật (cần URL public). VNPay `querydr` chưa
  kiểm chứng với terminal sandbox thật.

## Cấu hình

- `PAYMENT_PROVIDER` — `vnpay` (mặc định) | `payos`.
- VNPay (bắt buộc khi `vnpay`): `VNPAY_TMN_CODE`, `VNPAY_HASH_SECRET`, `VNPAY_PAY_URL`, `VNPAY_RETURN_URL` — thiếu là
  app không boot; `VNPAY_API_URL` tuỳ chọn (API `querydr`, mặc định sandbox). Hướng dẫn:
  [`docs/vnpay-testing.md`](../../docs/vnpay-testing.md), chạy local có IPN: [`docs/vnpay-run-local.md`](../../docs/vnpay-run-local.md).
- payOS (bắt buộc khi `payos`): `PAYOS_CLIENT_ID`, `PAYOS_API_KEY`, `PAYOS_CHECKSUM_KEY`; tuỳ chọn `PAYOS_BASE_URL`
  (mặc định `https://api-merchant.payos.vn`), `PAYOS_RETURN_URL` / `PAYOS_CANCEL_URL` (mặc định
  `<FRONTEND_URL>/payments/result`).

## Trạng thái hiện tại

- Gateway VNPay + payOS, IPN + webhook + Return, entity: có.
- **Endpoint khởi tạo thanh toán `POST /api/invoices/{invoiceId}/pay`: có** — mọi loại hoá đơn; contract ở
  `routes/booking/invoice-pay.md`.
- **`PaymentSucceeded`: `booking` đã nghe** — `DepositPaymentListener` (hoá đơn cọc) và `InvoicePaymentSettlement`
  (hoá đơn gắn hợp đồng). `PaymentFailed` chỉ được `DepositPaymentListener` ghi log — khoá phiên tự hết khi giao dịch
  rời `Pending`; audit `PAYMENT_FAILED` do `payment` ghi. `PaymentDuplicateDetected` → `PaymentDuplicateListener`
  báo FM.
- **Sửa state booking từ giao dịch Success: có** — `DepositStateRepairJob`.
- **Đối soát thủ công: có** — `POST /api/admin/payments/{txnRef}/reconcile` (ADMIN).
- **Tra cứu cổng khi khách quay về: có** — `POST /api/invoices/{invoiceId}/payment-check`.
- **Hoàn tiền thủ công: có** — đánh dấu `refund_status`, hàng đợi FM/ADMIN, ghi nhận `REFUND`.
- **Cron chờ phiên mở:** hết hạn hoá đơn / huỷ đơn / đóng phiên bàn giao đợi giao dịch `Pending` trong cửa sổ 15'.
