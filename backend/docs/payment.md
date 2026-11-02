---
purpose: Module payment — luồng thanh toán VNPay: tạo giao dịch, IPN/return, local repair và đối soát thủ công khi IPN mất.
---
# Payment — luồng thanh toán VNPay

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
Module sở hữu Invoice (booking) nghe PaymentSucceeded → chuyển Invoice sang Paid,
chốt đơn và khoang theo loại hoá đơn (routes/booking/invoice-pay.md › Nhánh thành công)
```

## Hành vi

- **Đồng hồ của hóa đơn: `Invoice.due_date`.** Quá hạn là mốc duy nhất của "quá hạn thanh toán" —
  Flow 6 xử lý (`Expired` + cascade hủy đơn).
  - **LƯU Ý:** đừng nhầm với cửa sổ phiên VNPay (`vnp_ExpireDate` = lúc tạo URL + `PAYMENT_WINDOW`,
    hiện 15') — cửa sổ chỉ là giới hạn kỹ thuật (hết cửa sổ là cổng không cho trả tiếp); khách bấm
    trả sát `due_date` vẫn có đủ 15'.
- **Ba lớp xác nhận & đối soát.** Không dùng `querydr` tự động:
  1. **IPN** — nguồn xác nhận chính, tự động.
  2. **Local repair** — `PaymentTransaction(PAY/Success)` còn đó nhưng listener booking lỗi; job
     `DepositStateRepairJob` quét invoice `Unpaid` và gọi lại listener.
  3. **Đối soát thủ công** — IPN mất hẳn nên giao dịch còn `Pending`; ops tra portal VNPay theo
     `vnp_TxnRef` rồi nhập kết quả qua endpoint ADMIN. Đây là thay thế cho querydr; chấp nhận
     không có job tự đóng `Pending`.
- **Xác nhận muộn (lưới an toàn).** Hi hữu — khách đã trả trong cửa sổ hợp lệ nhưng hệ thống chỉ biết
  sau khi invoice/đơn đã hủy (chỉ khi job/server lỗi dài): vẫn ghi `Success` + đủ dấu vết, log ERROR
  để vận hành xử lý thủ công (hoàn tiền hoặc xếp chỗ khác). Không tự mở lại đơn.
- **Sửa state khi listener IPN lỗi.** `PaymentTransaction(PAY/Success)` là bản ghi bền.
  `DepositStateRepairJob` quét invoice `Unpaid` (`Deposit`/`DepositSupplement`) có giao dịch
  `PAY/Success`, rồi gọi lại `DepositPaymentListener` (idempotent). Không cần querydr, không thêm
  bảng outbox. Job không cứu được IPN mất hẳn (giao dịch còn `Pending`) — ca đó vẫn cần nguồn đối
  soát ngoài (báo cáo portal).
- **URL phiên cache Redis.** Key `payment:url:{invoiceId}`, TTL 15 phút, khớp `vnp_ExpireDate`.
  `/pay` lần nữa trên cùng hoá đơn trả lại URL cũ; cache mất thì `resumePayment` dựng lại URL từ
  transaction Pending — cùng `vnp_TxnRef`, `vnp_CreateDate`/`vnp_ExpireDate` gốc, không tạo giao dịch
  mới. Không còn Pending trong 15 phút thì caller trả 409. Cache không phải khóa — khóa phiên vẫn
  suy từ `PaymentTransaction(Pending)`.
- **`vnp_OrderInfo` bỏ dấu trước khi ký.** VNPay chỉ hiển thị được ASCII; chuỗi có dấu bị encode
  thành `?`. `VnPayGateway` chuẩn hoá về không dấu trước khi ghép tham số, nên chuỗi ký và chuỗi
  khách thấy khớp nhau: `Đặt cọc khoang chứa A-01` → `Dat coc khoang chua A-01`.

## Quy tắc bất biến

- **IPN là nguồn xác nhận thanh toán chính.** Return URL nằm trên thanh địa chỉ, khách sửa được —
  không bao giờ dùng để ghi nhận đã trả tiền. Khi IPN không về, nguồn dự phòng là **đối soát thủ
  công qua portal VNPay**; không có querydr tự động trong hệ thống.
- **Không tự đóng `Pending` theo đồng hồ.** Giao dịch Pending không tự chuyển trạng thái; ops là
  người quyết định sau khi tra portal, qua endpoint ADMIN, có audit + event như IPN.
- **Idempotent theo `vnp_TxnRef`.** VNPay retry tối đa 10 lần (cách 5 phút) và có thể gọi song song;
  row bị khóa `PESSIMISTIC_WRITE` khi đọc, lần thứ hai trả `02` và không ghi gì. Unique constraint
  không thay được khóa vì cả hai cùng update một row.
- **Thứ tự kiểm trong IPN:** chữ ký (`97`) → tìm giao dịch (`01`) → `vnp_TmnCode` (`01`)
  → `vnp_Amount` (`04`) → guard trạng thái (`02`) → OK (`00`). Sai chữ ký thì không để lộ gì về
  giao dịch.
- **Số tiền:** cổng nhận `amount × 100`; đối chiếu khi IPN về. Nguồn số tiền tin cậy là **hóa đơn
  trong DB** — caller đọc `Invoice` rồi truyền amount vào `startPayment`; `payment` không import
  `booking` nên không tự đọc lại.
- **Không mở phiên mới khi đã có giao dịch `PAY/Success`.** IPN ghi giao dịch trước khi listener
  booking chạy; nếu listener chưa chạy/lỗi, invoice vẫn `Unpaid`. `/pay` kiểm giao dịch thành công
  và trả 409, không thu lần hai; local repair hoặc đối soát thủ công chốt phần còn lại.
- **Một invoice tối đa một `PAY/Success`.** Advisory lock theo invoice là lớp chính; partial unique
  index `(invoice_id) WHERE direction = 'PAY' AND status = 'Success'` (V13) là chốt cuối ở DB.
  Nếu một nguồn xác nhận thành công thứ hai cho cùng invoice, transaction thứ hai chuyển `Duplicate`
  — không phát `PaymentSucceeded`, không đụng invoice/order/unit. Ghi audit `PAYMENT_DUPLICATE`,
  notify FM để hoàn tiền thủ công. IPN trả `02`; manual trả `200` với status `DUPLICATE`.
- **Ranh giới module:** `PaymentTransaction.invoiceId` là `Long`, không quan hệ JPA với bảng
  `invoices`; module khác giao tiếp qua event, không import chéo.
- **Listener event bắt buộc `AFTER_COMMIT`.** `PaymentSucceeded`/`PaymentFailed` được publish
  *trong* transaction IPN — module nghe phải dùng `@TransactionalEventListener(phase = AFTER_COMMIT)`
  (hoặc `REQUIRES_NEW`). Listener đồng bộ mặc định sẽ thấy state chưa commit, và exception từ
  listener sẽ rollback giao dịch IPN khiến VNPay retry đủ 10 lần.
- **Không dùng `ApiEnvelope` cho IPN.** VNPay đọc phản hồi theo đúng hai key `RspCode`/`Message` ở
  mức gốc — đây là contract của bên thứ ba.


## Known limitations

- **Không có đối soát phiên tự động (`querydr`).** IPN không về → `PaymentTransaction(Pending)` nằm
  nguyên; invoice/order/unit giữ state cũ; ops phải tra portal rồi chốt qua endpoint ADMIN. Không có
  job tự đóng; `Pending` còn hạn 15' có thể chặn mở phiên mới.
- **Listener lỗi sau IPN** được local repair xử lý tối đa mỗi 5 phút; nếu job không cứu được thì vẫn
  cần đối soát thủ công.
- **Chưa có cron hủy đơn quá hạn thanh toán** (Flow 6); invoice/order không tự `Expired`/`Canceled`,
  khoang không tự release theo hạn.
- **Chưa có hoàn tiền tự động** (Flow 4). Tiền vào invoice đã `Canceled`/`Expired`, hoặc khoang đã mất,
  chỉ log ERROR; hoàn tiền thủ công.
- **Chỉ VNPay.** Chưa provider-neutral; `PaymentTransaction` chưa có cột provider; chưa có ZaloPay/payOS.
- **Giả định một instance.** `DepositStateRepairJob` chạy mỗi 5 phút; chưa cấu hình ShedLock vì
  deployment hiện tại chỉ một instance. Nếu scale ngang phải bổ sung khóa job.

## Cấu hình

Bốn biến `VNPAY_*` fail-fast (thiếu là app không boot): `VNPAY_TMN_CODE`, `VNPAY_HASH_SECRET`,
`VNPAY_PAY_URL`, `VNPAY_RETURN_URL`. Hướng dẫn sandbox: `docs/vnpay-sandbox.md`.


## Đối soát thủ công — thay cho querydr

- **Endpoint:** `POST /api/admin/payments/{txnRef}/reconcile`; quyền `ADMIN`; contract chi tiết ở
  [routes/payment/manual-reconcile.md](routes/payment/manual-reconcile.md).
- **Chỉ giao dịch `Pending`.** Row bị khoá `PESSIMISTIC_WRITE`; invoice được khoá thêm bằng
  advisory lock để hai manual request cùng invoice serialize. Giao dịch đã `Success`/`Failed`,
  invoice đã có `PAY/Success`, hoặc `gatewayTransactionNo` trùng giao dịch khác trả `409`, không
  ghi thêm. `SUCCESS` bắt buộc `paidAt` (không tương lai, không trước `createdAt` — so sánh tới
  giây) và
  `gatewayTransactionNo`; thiếu/sai trả `400`. Amount lấy từ `PaymentTransaction`, không nhận từ
  request.
- **Ghi như IPN:** `markSucceeded`/`markFailed`, audit `PAYMENT_SUCCEEDED`/`PAYMENT_FAILED`
  (`newValue` của Success gồm `gatewayTransactionNo` + `paidAt`), phát
  `PaymentSucceeded`/`PaymentFailed`; listener booking cập nhật invoice/order/unit.
- **Quy trình ops:** lọc `PaymentTransaction(Pending)` quá 15'; tra portal VNPay theo `vnp_TxnRef`;
  nếu đã trả → gọi endpoint với `outcome=SUCCESS`, `paidAt`, `gatewayTransactionNo`; nếu không →
  `outcome=FAILED` + note. Không sửa DB tay.
- **Giới hạn:** không tự retry, không tự phát hiện giao dịch; chất lượng phụ thuộc thao tác ops.
  Giao dịch `Pending` không có ai tra thì vẫn nằm nguyên — đây là mất mát đã chấp nhận khi bỏ querydr.

## Trạng thái hiện tại

- Gateway + IPN + Return + entity: có (VNPay).
- **Endpoint khởi tạo thanh toán `POST /api/invoices/{invoiceId}/pay`: có** — mở phiên cho hoá đơn
  `Deposit`/`DepositSupplement`; contract ở `routes/booking/invoice-pay.md`.
- **`PaymentSucceeded`/`PaymentFailed`: `booking` đã nghe** — `DepositPaymentListener` chốt hoá đơn,
  đơn và khoang khi `PaymentSucceeded`; `PaymentFailed` chỉ ghi log vì khoá phiên tự hết hạn. Chi
  tiết ở `routes/booking/invoice-pay.md`.
- **Sửa state booking từ giao dịch Success: có** — `DepositStateRepairJob`, xem “Sửa state khi
  listener IPN lỗi”.
- **Đối soát thủ công: có** — `POST /api/admin/payments/{txnRef}/reconcile` (ADMIN), xem
  [routes/payment/manual-reconcile.md](routes/payment/manual-reconcile.md).
- **Querydr tự động: không dùng** — thay bằng đối soát thủ công; chấp nhận `Pending` không tự đóng.
- **Chặn hủy đơn khi phiên thanh toán còn mở: chưa có trong code** — cần chốt và ghi vào docs trước.
