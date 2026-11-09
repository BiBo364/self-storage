---
auth-model: ownership
purpose: Mở phiên thanh toán hoá đơn — năm nhánh kiểm của Flow 1.4 cho hoá đơn cọc, kiểm trạng thái cho hoá đơn gắn hợp đồng, và nhánh chốt khi cổng xác nhận.
---
# POST /api/invoices/{invoiceId}/pay

Khách bấm "Tiến hành thanh toán" trên hoá đơn `Unpaid` của mình (cọc, cọc bổ sung, tháng đầu, gia hạn, tiền thuê,
phạt, quyết toán trả kho). Endpoint kiểm điều kiện, mở một phiên thanh toán và trả URL của cổng đang dùng (VNPay mặc
định, payOS khi `payment.provider=payos` — [payment.md](../../payment.md)) để FE chuyển hướng.

- **Actor / quyền:** bearer + `ACCOUNT_ACTIVE`, chủ hoá đơn (hoá đơn của người khác → 404). Hoá đơn cọc còn đòi
  **đã xác minh email** (403).
- **Contract chi tiết:** annotation tại `booking/controller/InvoicePaymentController.java`; Swagger UI —
  `http://localhost:8080/swagger-ui/index.html`.
- **Điều phối:** `InvoicePaymentService.start` — hoá đơn `Deposit`/`DepositSupplement` giao cho
  `DepositPaymentService.startDeposit`; hoá đơn gắn hợp đồng đi nhánh riêng bên dưới. Từ chối trả 409
  `{message, invoiceCanceled, timestamp}` — `invoiceCanceled = true` khi nhánh 2/3 vừa huỷ hoá đơn; lỗi trạng thái
  hoá đơn của nhánh hợp đồng trả 409 `{message, timestamp}`.

## Hoá đơn cọc — năm nhánh kiểm, đúng thứ tự này

| # | Điều kiện | Kết quả |
|---|---|---|
| 1 | `RentalOrder.expires_at < now` | 409 "Đơn thuê kho đã hết hạn…" — không đổi gì |
| 2 | khoang `Reserved`/`Rented` | 409 + `Invoice=Canceled` + audit `INVOICE_CANCELED` + notify FM `PROPOSAL_REPROPOSAL_REQUIRED`; đơn đang giữ khoang thì bật `needs_reproposal` |
| 3 | khoang `Maintenance` | 409 + `Invoice=Canceled` + audit `INVOICE_CANCELED` + notify FM `PROPOSAL_REPROPOSAL_REQUIRED`; đơn đang giữ khoang thì bật `needs_reproposal` |
| 4 | khoang đang có phiên thanh toán khác | 409 "…đang trong quá trình xử lý thanh toán…" — không đổi gì |
| 5 | không vướng gì | `PaymentTransaction(Pending)` + trả `paymentUrl` |

Trước năm nhánh, hoá đơn không còn `Unpaid` hoặc không phải `Deposit`/`DepositSupplement` cũng trả
409 "Hóa đơn này không ở trạng thái chờ thanh toán cọc."

Hoá đơn còn `Unpaid` nhưng đã có `PaymentTransaction(PAY/Success)` (cổng đã xác nhận, listener chưa
chạy/lỗi) cũng trả 409 "Hóa đơn này đã được thanh toán thành công. Vui lòng chờ hệ thống xác nhận."
— không mở phiên mới và không huỷ hoá đơn; đối soát xử lý tiếp.

Thứ tự quan trọng: đơn quá hạn chặn **trước** khi đụng tới khoang (không vô hiệu hóa đơn của một
khoang vẫn còn tốt), còn khóa phiên kiểm **sau cùng** (không báo "đang xử lý" cho khoang thật ra
đã `Reserved`).

Việc huỷ hoá đơn ở nhánh 2/3 phải commit dù endpoint trả 409. `startDeposit` khai
`noRollbackFor = DepositNotAllowedException.class`; `InvoiceVoidingService` chạy trong cùng
transaction (`MANDATORY`) nên `Canceled` + `needs_reproposal` được ghi trước khi exception ra
`InvoicePaymentService`. Nó khoá row hoá đơn và bỏ qua nếu hoá đơn không còn `Unpaid` — xác nhận thanh toán có thể
đang đánh `Paid` cùng lúc.

## Hoá đơn gắn hợp đồng

Một transaction, khoá đơn (của hợp đồng) → hoá đơn:

- hoá đơn không `Unpaid` → 409 "Hoá đơn không còn ở trạng thái chờ thanh toán"; quá `due_date` → 409;
- đơn chưa bàn giao xong: đơn đã đóng → 409; đơn quá `expires_at` → 409 "Đơn thuê kho đã hết hạn…". Hoá đơn sau bàn
  giao trên đơn `Done` (gia hạn, tiền thuê, phạt…) bỏ qua kiểm đơn;
- đã có `PAY/Success` → 409 như hoá đơn cọc;
- còn phiên `Pending` của chính hoá đơn → trả lại URL cũ (như dưới), không thì mở phiên mới.

## Khóa giao dịch ngắn hạn

Không có bảng khóa; khóa suy từ `PaymentTransaction(Pending)` trong Postgres. Redis chỉ cache
URL của phiên (`payment:url:{invoiceId}`, TTL 15 phút), không phải khóa. Một khoang coi là đang bận
khi tồn tại `PaymentTransaction` còn `Pending` thuộc đơn của khoang đó, tạo chưa quá
`PaymentSession.WINDOW` (15 phút) — đúng bằng `vnp_ExpireDate` / `expiredAt` (payOS) mà gateway đặt, nên khóa và URL
hết hạn cùng lúc.

Kiểm **theo khoang, không theo hóa đơn** (hoá đơn cọc): hai khách khác nhau trả cho hai hóa đơn khác nhau vẫn
tranh cùng một khoang.

Thanh toán thất bại không cần gỡ khóa: giao dịch chuyển `Failed` nên tự hết `Pending`.

## Nhánh thành công — chốt khi cổng xác nhận, không chốt ở đây

Endpoint này **không bao giờ** ghi nhận đã thu tiền. Cổng xác nhận qua IPN VNPay, webhook payOS,
`payment-check` hoặc đối soát thủ công; `payment` phát `PaymentSucceeded`. Hoá đơn cọc do `DepositPaymentListener`
chốt, hoá đơn gắn hợp đồng do `InvoicePaymentSettlement` chốt (`Invoice=Paid` + audit `INVOICE_PAID`, phát
`ContractInvoicePaid` cho module sở hữu hợp đồng):

| Loại | Ghi gì |
|---|---|
| `Deposit` | `Invoice=Paid` · `RentalOrder=Deposited` · `StorageUnit=Reserved` (ghi `reserved_order_id`) · notify khách `DEPOSIT_PAYMENT_SUCCEEDED` · audit `INVOICE_PAID` + `RENTAL_ORDER_DEPOSITED` + `STORAGE_UNIT_RESERVED`. Nếu unit đã mất: `Invoice=Paid` + `refund_status=Required`, order/unit không đổi, audit `INVOICE_PAID` + `INVOICE_REFUND_REQUIRED` |
| `DepositSupplement` | `Invoice=Paid` · `StorageUnit=Reserved` (khoang mới) · notify khách `DEPOSIT_SUPPLEMENT_PAYMENT_SUCCEEDED` · audit `INVOICE_PAID` + `STORAGE_UNIT_RESERVED`. Đơn còn `Pending` (cọc đầu đã trả nhưng khoang cũ bị giữ trước) → chốt cọc như `Deposit` |

Khoang vừa chuyển `Reserved` → sau commit, mọi hoá đơn cọc `Unpaid` khác trỏ khoang đó bị huỷ ngay
(`CompetingInvoiceCanceller`, xem [deposit-payment.md](../../booking/deposit-payment.md)).

Cọc bổ sung trên đơn đã `Deposited` **không** chuyển trạng thái đơn lần hai và **không** gửi lại "Đặt cọc thành
công" (`booking/proposal-flow.md` › Ranh giới). Khách nhận xác nhận riêng `DEPOSIT_SUPPLEMENT_PAYMENT_SUCCEEDED` kèm
khoang mới đã giữ.

**Thứ tự khóa: đơn → hóa đơn → khoang.** Luồng đề xuất khoang
(`booking/proposal-flow.md`) khóa row đơn ở mọi đường ghi, nên luồng thu cọc phải khóa cùng thứ
tự; khác thứ tự là deadlock, không khóa thì luồng kia đọc được hóa đơn nửa chừng. `startDeposit`
lấy `order_id` bằng projection (`findOrderIdByIdAndCustomerId`), khoá đơn trước, rồi khoá hoá đơn
và đọc trạng thái, rồi mới khoá khoang. Listener dùng `findOrderIdById` (hoá đơn gắn hợp đồng lấy đơn qua
`rental_contracts.order_id`) cùng mục đích: khoá đơn mà chưa nạp entity hoá đơn.

Hạn đơn **không đổi**: `expires_at` set một lần khi tạo đơn (`specs/db-table-draft.md` ›
RentalOrder), và hạn giữ kho là phần còn lại của chính hạn đó.

Khoang chỉ chuyển `Reserved` khi vẫn còn `Available` tại thời điểm xác nhận — cổng có thể gọi lại
tới 10 lần trong 50 phút, đủ lâu để khách khác cọc trước. Nếu khoang đã đổi chủ:

- `Invoice` vẫn chuyển `Paid` — tiền đã vào.
- `Invoice.refund_status` chuyển `Required`, `refund_reason=UNIT_UNAVAILABLE`.
- Khoang giữ nguyên; `RentalOrder` không chuyển `Deposited`.
- Ghi audit `INVOICE_REFUND_REQUIRED`; notify FM `PAYMENT_REFUND_REQUIRED` và khách `PAYMENT_REFUND_PENDING`.
- Khoản hoàn tiền hiện trong hàng đợi `GET /api/fm/invoices/refunds` (FM) và `GET /api/admin/payments/refunds`
  (ADMIN); FM chuyển khoản rồi ghi nhận qua `POST /api/fm/invoices/{invoiceId}/refunds`
  ([fm-invoice-refunds.md](fm-invoice-refunds.md)).

Hóa đơn không còn `Unpaid` lúc xác nhận về: `Paid` là xác nhận lặp, chỉ log INFO; `Canceled`/`Expired` là tiền vào
một hóa đơn không còn hiệu lực (vd khách quét QR cũ sau khi phiên đã huỷ) — log ERROR, hoá đơn
`refund_status = Required` (`INVOICE_CLOSED`) + FM/khách nhận như trên (hoá đơn gắn hợp đồng: FM nhận
`FIRST_MONTH_REFUND_REQUIRED`). Không tự mở lại đơn.

Listener bắt buộc `@TransactionalEventListener(AFTER_COMMIT)` **và**
`@Transactional(REQUIRES_NEW)` — xem `payment.md` › Quy tắc bất biến. `AuditRecorder` dùng
propagation `MANDATORY` nên không có transaction mới là lỗi wiring.

Nếu listener lỗi sau khi cổng đã xác nhận, giao dịch vẫn `Success` nhưng invoice còn `Unpaid`.
`DepositStateRepairJob` quét invoice cọc `Unpaid` + giao dịch `PAY/Success` và gọi lại listener
(idempotent); đây là local outbox. Trường hợp xác nhận mất hẳn (transaction còn `Pending`) khách bấm
`payment-check` khi quay về, hoặc ops tra portal rồi chốt qua `POST /api/admin/payments/{txnRef}/reconcile`.

## Ghi chú nghiệp vụ

- **Số tiền đọc từ `Invoice` trong DB**, không nhận từ client — cổng đối chiếu số tiền với chính con số đó khi xác
  nhận.
- Mã giao dịch: hoá đơn cọc `INV{invoiceId}T{yyyyMMddHHmmss}` GMT+7 kèm hậu tố ngẫu nhiên (mốc chỉ tới giây nên hai
  lần bấm cùng giây sẽ trùng mã); hoá đơn gắn hợp đồng do cổng sinh (`PaymentGateway.newTxnRef` — payOS `orderCode`
  số). Mốc tạo phiên = `created_at` của giao dịch.
- Phiên đang mở **của chính hóa đơn này**: `/pay` trả lại đúng URL cũ nếu cache Redis còn; cache
  mất thì dựng lại URL từ `PaymentTransaction(Pending)` — cùng mã giao dịch, mốc tạo/hết hạn gốc, không tạo giao dịch
  mới. Không còn phiên Pending trong 15 phút, hoặc cổng không dựng lại được link (payOS không tạo lại cùng
  `orderCode`) thì 409 "Phiên thanh toán trước đã hết hiệu lực…" (hoá đơn gắn hợp đồng: "Hoá đơn đang có phiên
  thanh toán chưa kết thúc…").
- Hóa đơn không tồn tại và hóa đơn của người khác trả **cùng một** 404.
- Cơ sở chưa gán FM thì vẫn hủy hóa đơn, chỉ bỏ qua thông báo — thiếu người nhận không được chặn
  nghiệp vụ.
- `PAYMENT_SUCCEEDED` audit thuộc module `payment` (entity `PaymentTransaction`), không ghi ở đây.

## Liên quan

- [invoice-payment-check.md](invoice-payment-check.md) · [payment.md](../../payment.md) ·
  [deposit-payment.md](../../booking/deposit-payment.md) · [routes.md](../../routes.md)
