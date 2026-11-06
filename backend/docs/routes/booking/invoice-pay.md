---
auth-model: ownership
purpose: Mở phiên thanh toán cho hóa đơn cọc — năm nhánh kiểm của Flow 1.4 và nhánh chốt khi IPN về.
---
# POST /api/invoices/{invoiceId}/pay

Khách bấm "Tiến hành thanh toán" trên hóa đơn cọc. Endpoint kiểm điều kiện, mở một phiên thanh
toán và trả URL VNPay để FE chuyển hướng.

- **Actor / quyền:** khách đã đăng nhập, **đã xác minh email**, và phải là chủ hóa đơn.
- **Contract chi tiết:** annotation tại `InvoicePaymentController.java`; Swagger UI —
  `http://localhost:8080/swagger-ui/index.html`.
- **Loại hóa đơn nhận:** `Deposit` và `DepositSupplement` (cọc bổ sung khi đổi khoang,
  `booking/proposal-flow.md`). Hóa đơn thuê/gia hạn thuộc luồng khác.

## Năm nhánh kiểm — đúng thứ tự này

| # | Điều kiện | Kết quả |
|---|---|---|
| 1 | `RentalOrder.expires_at < now` | 409 "Đơn thuê kho đã hết hạn…" — không đổi gì |
| 2 | khoang `Reserved`/`Rented` | 409 + `Invoice=Canceled` + audit `INVOICE_CANCELED` + notify FM `PROPOSAL_REPROPOSAL_REQUIRED`; đơn đang giữ khoang thì bật `needs_reproposal` |
| 3 | khoang `Maintenance` | 409 + `Invoice=Canceled` + audit `INVOICE_CANCELED` + notify FM `PROPOSAL_REPROPOSAL_REQUIRED`; đơn đang giữ khoang thì bật `needs_reproposal` |
| 4 | khoang đang có phiên thanh toán khác | 409 "…đang trong quá trình xử lý thanh toán…" — không đổi gì |
| 5 | không vướng gì | `PaymentTransaction(Pending)` + trả `paymentUrl` |

Trước năm nhánh, hoá đơn không còn `Unpaid` hoặc không phải `Deposit`/`DepositSupplement` cũng trả
409 "Hóa đơn này không ở trạng thái chờ thanh toán cọc."

Hoá đơn còn `Unpaid` nhưng đã có `PaymentTransaction(PAY/Success)` (IPN đã ghi, listener chưa
chạy/lỗi) cũng trả 409 "Hóa đơn này đã được thanh toán thành công. Vui lòng chờ hệ thống xác nhận."
— không mở phiên mới và không huỷ hoá đơn; đối soát xử lý tiếp.

Thứ tự quan trọng: đơn quá hạn chặn **trước** khi đụng tới khoang (không vô hiệu hóa đơn của một
khoang vẫn còn tốt), còn khóa phiên kiểm **sau cùng** (không báo "đang xử lý" cho khoang thật ra
đã `Reserved`).

Việc huỷ hoá đơn ở nhánh 2/3 phải commit dù endpoint trả 409. `startDeposit` khai
`noRollbackFor = DepositNotAllowedException.class`; `InvoiceVoidingService` chạy trong cùng
transaction (`MANDATORY`) nên `Canceled` + `needs_reproposal` được ghi trước khi exception ra
handler. Nó khoá row hoá đơn và bỏ qua nếu hoá đơn không còn `Unpaid` — IPN có thể đang đánh `Paid`
cùng lúc.

## Khóa giao dịch ngắn hạn

Không có bảng khóa; khóa suy từ `PaymentTransaction(Pending)` trong Postgres. Redis chỉ cache
URL của phiên (`payment:url:{invoiceId}`, TTL 15 phút), không phải khóa. Một khoang coi là đang bận
khi tồn tại `PaymentTransaction` còn `Pending` thuộc đơn của khoang đó, tạo chưa quá
`PaymentSession.WINDOW` (15 phút) — đúng bằng `vnp_ExpireDate` mà gateway đặt, nên khóa và URL hết
hạn cùng lúc.

Kiểm **theo khoang, không theo hóa đơn**: hai khách khác nhau trả cho hai hóa đơn khác nhau vẫn
tranh cùng một khoang.

Thanh toán thất bại không cần gỡ khóa: giao dịch chuyển `Failed` nên tự hết `Pending`.

## Nhánh thành công — chốt khi IPN về, không chốt ở đây

Endpoint này **không bao giờ** ghi nhận đã thu tiền. `DepositPaymentListener` nghe
`PaymentSucceeded` rồi ghi trong một transaction, khác nhau theo loại hóa đơn:

| Loại | Ghi gì |
|---|---|
| `Deposit` | `Invoice=Paid` · `RentalOrder=Deposited` · `StorageUnit=Reserved` · notify khách `DEPOSIT_PAYMENT_SUCCEEDED` · audit `RENTAL_ORDER_DEPOSITED` + `STORAGE_UNIT_RESERVED`. Nếu unit đã mất: `Invoice=Paid` + `refund_status=Required`, order/unit không đổi, audit `INVOICE_REFUND_REQUIRED` |
| `DepositSupplement` | `Invoice=Paid` · `StorageUnit=Reserved` (khoang mới) · notify khách `DEPOSIT_SUPPLEMENT_PAYMENT_SUCCEEDED` · audit `STORAGE_UNIT_RESERVED` |

Cọc bổ sung **không** chuyển trạng thái đơn lần hai và **không** gửi lại "Đặt cọc thành công":
đơn đã `Deposited` từ lần cọc đầu (`booking/proposal-flow.md` › Ranh giới). Khách nhận xác nhận
riêng `DEPOSIT_SUPPLEMENT_PAYMENT_SUCCEEDED` kèm khoang mới đã giữ. Khoang mới chưa được reserve ở
luồng đổi khoang, nên bước thu tiền này mới là chỗ giữ nó (cùng file › Invariants).

**Thứ tự khóa: đơn → hóa đơn → khoang.** Luồng đề xuất khoang
(`booking/proposal-flow.md`) khóa row đơn ở mọi đường ghi, nên luồng thu cọc phải khóa cùng thứ
tự; khác thứ tự là deadlock, không khóa thì luồng kia đọc được hóa đơn nửa chừng. `startDeposit`
lấy `order_id` bằng projection (`findOrderIdByIdAndCustomerId`), khoá đơn trước, rồi khoá hoá đơn
và đọc trạng thái, rồi mới khoá khoang. Listener IPN dùng `findOrderIdById` cùng mục đích: khoá
đơn mà chưa nạp entity hoá đơn.

Hạn đơn **không đổi**: `expires_at` set một lần khi tạo đơn (`specs/db-table-draft.md` ›
RentalOrder), và hạn giữ kho là phần còn lại của chính hạn đó.

Khoang chỉ chuyển `Reserved` khi vẫn còn `Available` tại thời điểm xác nhận — cổng có thể gọi lại
tới 10 lần trong 50 phút, đủ lâu để khách khác cọc trước. Nếu khoang đã đổi chủ:

- `Invoice` vẫn chuyển `Paid` — tiền đã vào.
- `Invoice.refund_status` chuyển `Required`, `refund_reason=UNIT_UNAVAILABLE`.
- Khoang giữ nguyên; `RentalOrder` không chuyển `Deposited`.
- Ghi audit `INVOICE_REFUND_REQUIRED`; notify FM và khách.
- Khoản hoàn tiền hiển thị trong `GET /api/admin/payments/refunds` để Admin xử lý; hoàn tiền thuộc Flow 4.

Hóa đơn không còn `Unpaid` lúc IPN về: `Paid` là IPN lặp, chỉ log INFO; `Canceled`/`Expired` là
tiền vào một hóa đơn không còn hiệu lực — log ERROR để có người đối soát.

Listener bắt buộc `@TransactionalEventListener(AFTER_COMMIT)` **và**
`@Transactional(REQUIRES_NEW)` — xem `payment.md` › Quy tắc bất biến. `AuditRecorder` dùng
propagation `MANDATORY` nên không có transaction mới là lỗi wiring.

Nếu listener lỗi sau khi IPN đã commit, giao dịch vẫn `Success` nhưng invoice còn `Unpaid`.
`DepositStateRepairJob` quét invoice `Unpaid` + giao dịch `PAY/Success` và gọi lại listener
(idempotent); đây là local outbox, không phải querydr. Trường hợp IPN mất hẳn (transaction còn
`Pending`) do ops tra portal rồi chốt qua `POST /api/admin/payments/{txnRef}/reconcile`.

## Ghi chú nghiệp vụ

- **Số tiền đọc từ `Invoice` trong DB**, không nhận từ client — cổng đối chiếu `vnp_Amount` với
  chính con số đó khi xác nhận.
- `vnp_TxnRef` dạng `INV{invoiceId}T{yyyyMMddHHmmss}` GMT+7 kèm hậu tố ngẫu nhiên: mốc thời gian
  chỉ tới giây nên hai lần bấm trong cùng một giây sẽ trùng mã và vỡ unique constraint.
- Phiên đang mở **của chính hóa đơn này**: `/pay` trả lại đúng URL cũ nếu cache Redis còn; cache
  mất thì dựng lại URL từ `PaymentTransaction(Pending)` — cùng `vnp_TxnRef`, `vnp_CreateDate`/
  `vnp_ExpireDate` gốc, không tạo giao dịch mới. Không còn phiên Pending trong 15 phút thì 409
  "Phiên thanh toán trước đã hết hiệu lực…".
- Hóa đơn không tồn tại và hóa đơn của người khác trả **cùng một** 404.
- Cơ sở chưa gán FM thì vẫn hủy hóa đơn, chỉ bỏ qua thông báo — thiếu người nhận không được chặn
  nghiệp vụ.
- `PAYMENT_SUCCEEDED` audit thuộc module `payment` (entity `PaymentTransaction`), không ghi ở đây.

## Chưa có trong luồng này

- Cron hủy đơn quá hạn thanh toán; đối soát `Pending` bằng endpoint ADMIN thủ công (không querydr).
- Hoàn tiền khi tiền đã vào mà khoang đã mất: thuộc Flow 4.

## Liên quan

- [payment.md](../../payment.md) · [vnpay-ipn.md](../payment/vnpay-ipn.md) ·
  [proposal-flow.md](../../booking/proposal-flow.md) · [routes.md](../../routes.md)
