---
purpose: Luồng thanh toán hoá đơn (cọc, cọc bổ sung, hoá đơn gắn hợp đồng) từ nút "Thanh toán" tới khi hoá đơn Paid — as-built.
---
# Thanh toán hoá đơn & chốt cọc (Flow 1.4)

Code: `booking/application/InvoicePaymentService.java` (bấm thanh toán), `InvoicePaymentSettlement.java` (chốt khi
cổng xác nhận), `payment/application/PaymentService.java` (giao dịch, IPN, `querydr`).

## Sơ đồ

```mermaid
sequenceDiagram
    actor K as Khách
    participant B as booking (InvoicePaymentService)
    participant P as payment (PaymentService)
    participant V as Cổng (payOS)
    participant S as booking (InvoicePaymentSettlement)
    K->>B: POST /api/invoices/{id}/pay
    B->>B: khoá đơn → hoá đơn → khoang, kiểm 5 nhánh spec 1.4
    B->>P: startPayment (ghi PaymentTransaction Pending)
    P-->>K: paymentUrl
    K->>V: thanh toán
    alt Webhook về
        V->>P: POST /api/payments/payos/webhook
    else Khách quay về, webhook chưa tới
        K->>B: POST /api/invoices/{id}/payment-check
        B->>P: reconcileLatest → tra cứu link
    end
    P->>P: Success + PaymentSucceeded (commit)
    P-->>S: AFTER_COMMIT, REQUIRES_NEW
    S->>S: hoá đơn Paid; Deposit → khoang Reserved + đơn Deposited
```

## Trạng thái

| Hoá đơn | Khi | Hệ quả |
|---|---|---|
| `Deposit` `Unpaid → Paid` | cổng xác nhận | khoang `Available → Reserved`, đơn `Pending → Deposited`, khách nhận `DEPOSIT_PAYMENT_SUCCEEDED` |
| `Deposit` `Unpaid → Paid`, khoang đã bị đơn khác giữ | cổng xác nhận | hoá đơn vẫn `Paid`; đơn ở lại `Pending` (cọc đã thu, chưa giữ khoang); FM nhận `PROPOSAL_REPROPOSAL_REQUIRED` để đề xuất khoang thay thế — xem [proposal-agree.md › Cọc đã trả nhưng khoang bị giữ trước](../routes/booking/proposal-agree.md#cọc-đã-trả-nhưng-khoang-bị-giữ-trước) |
| `DepositSupplement` `Unpaid → Paid`, đơn đã giữ khoang | cổng xác nhận | reserve khoang mới; đơn không đổi trạng thái, không gửi lại "Đặt cọc thành công" |
| `DepositSupplement` `Unpaid → Paid`, đơn còn `Pending` (cọc đã trả, khoang cũ bị giữ trước) | cổng xác nhận | chốt cọc như `Deposit`: khoang thay thế `Reserved`, đơn `Pending → Deposited`, audit `RENTAL_ORDER_DEPOSITED`, khách nhận `DEPOSIT_PAYMENT_SUCCEEDED` |
| gắn hợp đồng `Unpaid → Paid` | cổng xác nhận | phát `ContractInvoicePaid(invoiceId, contractId, type, paidAt)` cho module sở hữu hợp đồng |
| gắn hợp đồng, đã đóng (`Canceled`/`Expired`) mà tiền vẫn vào | cổng xác nhận muộn | giao dịch `Success`, log ERROR, FM nhận `FIRST_MONTH_REFUND_REQUIRED` |
| `Unpaid → Canceled` | bấm thanh toán khi khoang `Reserved`/`Rented`/`Maintenance` | FM nhận `PROPOSAL_REPROPOSAL_REQUIRED`; đơn giữ trạng thái, đơn đã cọc (đổi khoang) bật `needs_reproposal` để FM đề xuất lại được và đơn không đặt lịch check-in trên khoang đã mất. Cổng xác nhận cọc khi khoang đã bị giữ, hay đổi khoang `delta ≤ 0` giữ khoang mới, cũng áp cùng quy tắc (đổi khoang giữ khoang → chặn hoá đơn cọc khác của khoang như cọc qua cổng) |
| `Unpaid → Expired` | cron quá `due_date` | xem [Cron Flow 1](#cron-flow-1) |

Hoá đơn gắn hợp đồng sau bàn giao (tiền thuê tháng, gia hạn, phí quá hạn, quyết toán trả kho) có đơn ở `Done`. Bấm
thanh toán bỏ qua các kiểm tra trạng thái / hạn của đơn và chỉ chặn khi hoá đơn còn phiên mở. Xem
[tenancy/rentals.md](../tenancy/rentals.md).

Audit nhánh thành công (actor = hệ thống): `PAYMENT_SUCCEEDED` (PaymentTransaction), `INVOICE_PAID` (Invoice),
`RENTAL_ORDER_DEPOSITED` (RentalOrder), `STORAGE_UNIT_RESERVED` (StorageUnit). Cọc bổ sung trên đơn đã giữ khoang ghi
`PAYMENT_SUCCEEDED`, `INVOICE_PAID`, `STORAGE_UNIT_RESERVED` (không có `RENTAL_ORDER_DEPOSITED`); trên đơn còn
`Pending` thì ghi đủ bốn bản như `Deposit`.

## Bất biến

- **Khoá phiên suy từ dữ liệu, giữ tới khi cổng đóng phiên:** khoang bận khi còn giao dịch `Pending` thuộc hoá đơn
  cọc `Unpaid` của bất kỳ đơn nào trỏ khoang — không tự hết sau 15' mà đợi cổng xác nhận đã trả / huỷ / hết hạn
  (webhook, `payment-check`, job đối soát), tối đa 24 giờ kể từ lúc tạo giao dịch (`OPEN_SESSION_LIMIT`, xem
  [payment.md › Khoá phiên](../payment.md#khoá-phiên--huỷ-phiên)). Khoá row khoang trước khi đọc → hai request song
  song không cùng mở được phiên.
- **Bấm thanh toán lại hoá đơn đang có phiên:** trước khi khoá, đối soát phiên cũ (đã trả → chốt luôn, request sau
  nhận 409 "không còn chờ thanh toán"); chưa trả → huỷ phiên cũ ở cổng (payOS cancel) rồi mở phiên mới; cổng không huỷ
  được → 409 "Hoá đơn đang có phiên thanh toán chưa kết thúc…". Áp cho mọi loại hoá đơn (kể cả tháng đầu trên đơn
  `InProgress`): bấm đúp hai request thì request sau thấy phiên của request trước → 409, không mở phiên thứ hai.
- **Khoang vừa có người trả cọc → chặn ngay các hoá đơn khác của khoang** (spec 1.1 "đã có người khác đặt cọc"):
  sau commit bước chốt cọc, `CompetingInvoiceCanceller` (transaction riêng, khoá đơn → hoá đơn) huỷ mọi hoá đơn cọc
  `Unpaid` khác trỏ khoang (audit `INVOICE_CANCELED` lý do "Khoang đã có người đặt cọc trước"), FM nhận
  `PROPOSAL_REPROPOSAL_REQUIRED`, khách nhận `PROPOSAL_UNIT_TAKEN` ("Khoang [mã] đã có người đặt cọc trước. Cơ sở
  đang tìm khoang khác phù hợp cho bạn."); đơn không huỷ (đơn đã cọc thì bật `needs_reproposal`); phiên còn mở của
  các hoá đơn đó bị huỷ ở cổng.
- **Thứ tự khoá:** đơn → hoá đơn → khoang, ở cả bấm thanh toán và chốt thanh toán — cùng thứ tự với đồng ý đề xuất
  và Cancellation Cascade, không deadlock ngược chiều. Hoá đơn gắn hợp đồng (tháng đầu) không có `order_id` (CHECK
  V1: đúng một nguồn) — đơn được tìm qua `rental_contracts.order_id` (`InvoiceRepository.findOrderIdById`) để vẫn
  khoá đơn trước; các kiểm tra đơn đã đóng / quá `expires_at` áp dụng như hoá đơn cọc, bỏ qua kiểm khoang.
- **Listener chốt chạy `AFTER_COMMIT` + `REQUIRES_NEW`:** lỗi ở bước chốt không rollback giao dịch của cổng (tiền
  đã vào, VNPay không retry vô ích).
- **Xác nhận muộn (lưới an toàn cuối):** mọi đường huỷ hoá đơn cọc `Unpaid` đều huỷ phiên cổng sau commit
  (`PaymentSessionCloser.closeAfterCommit`) — Cascade, khoang đã có người cọc trước, FM đề xuất lại khoang khác. Nhánh
  này còn lại khi: khách tự chuyển khoản tay theo QR cũ sau khi link đã huỷ; cổng không huỷ được phiên; hoặc giao dịch
  `Pending` quá 24 giờ (không còn tính là phiên mở nên cron đã hết hạn hoá đơn / huỷ đơn) mà sau đó cổng mới báo đã
  thu. Khi đó vẫn ghi `PAYMENT_SUCCEEDED` (hoặc webhook trả `ALREADY_SETTLED` nếu giao dịch đã `Failed`), log ERROR,
  FM nhận `PROPOSAL_REPROPOSAL_REQUIRED`, chuyển khoản hoàn rồi ghi nhận qua
  `POST /api/fm/invoices/{invoiceId}/refunds` ([payment.md › Hoàn tiền thủ công](../payment.md#hoàn-tiền-thủ-công)).
  Không tự mở lại đơn.
- **Đối soát với cổng là nguồn dự phòng duy nhất ngoài webhook/IPN:** dùng ở `payment-check` (khách quay về) và job
  đối soát (`PaymentReconcileJob`, 5'); return URL không bao giờ ghi dữ liệu.

## Mã hoá đơn

`INV-{loại}-{mã cơ sở}-{YYMMDD}-{6 ký tự ngẫu nhiên}` (`DepositInvoiceIssuer.code`, ngày theo giờ cơ sở). Loại:

| Tiền tố | `type` | Sinh ở |
|---|---|---|
| `INV-DEP` | `Deposit`, `DepositSupplement` | khách đồng ý proposal (cọc / cọc bổ sung khi đổi khoang) |
| `INV-RNT` | `Rental` | FS ký hợp đồng — tiền thuê tháng đầu; cron tiền thuê hằng tháng — các tháng sau ([tenancy/rentals.md](../tenancy/rentals.md#thu-tiền-thuê-hằng-tháng)) |
| `INV-EXT` | `Extension` | khách gia hạn ([tenancy/rentals.md](../tenancy/rentals.md)) |
| `INV-OVD` | `Penalty` | cron phí quá hạn ([tenancy/rentals.md](../tenancy/rentals.md#quá-hạn-flow-6)) |
| `INV-CLN` | `Penalty` | quyết toán trả kho — phí vệ sinh ([checkout/return.md](../checkout/return.md)) |
| `INV-DMG` | `Penalty` | quyết toán trả kho — phí hư hỏng |
| `INV-KEY` | `Penalty` | quyết toán trả kho — phí mất chìa (`fee.lost_key × số chìa thiếu`) |
| `INV-PEN` | `Penalty` | quyết toán trả kho — hoá đơn `Unpaid` của hợp đồng gộp vào, và phần còn thiếu khi phí vượt cọc |
| `INV-SVC` | `Service` | FS / FM xử lý yêu cầu hỗ trợ có thu phí ([support/requests.md](../support/requests.md)) |

`Deposit`/`DepositSupplement` gắn `order_id`; các loại còn lại gắn `contract_id` (CHECK V1: mỗi hoá đơn đúng một
nguồn).

## Cron Flow 1

`BookingExpiryJob` (5', `booking.expiry.fixed-delay-ms`) gọi `BookingExpiryService`, mỗi bản ghi một transaction:

| Bước | Điều kiện | Kết quả |
|---|---|---|
| Request hết hạn | `RentalRequest` `Pending`/`Approved`, `expires_at < now` | `Expired` (update có điều kiện), audit `RENTAL_REQUEST_EXPIRED` |
| Hoá đơn quá hạn | `Unpaid`, `due_date < now`, **không** còn phiên thanh toán mở | `Expired` + audit `INVOICE_EXPIRED`; `Deposit` → cascade "Quá hạn thanh toán cọc"; `DepositSupplement` → bật `needs_reproposal` + FM nhận `PROPOSAL_REPROPOSAL_REQUIRED`; gắn hợp đồng → phát `ContractInvoiceExpired` |
| Catch-all đơn | `expires_at < now`, chưa `Canceled`/`Done`, không còn phiên mở | cascade, lý do: `Pending` + proposal chưa `Agreed` → "Quá hạn xác nhận đề xuất"; `Pending` + `Agreed` → "Quá hạn thanh toán cọc"; `Pending` mà đã thu cọc (khoang bị giữ trước, chưa xếp được khoang khác) → "Hết hạn chờ xếp khoang sau khi đã cọc" + audit `DEPOSIT_REFUND_REQUIRED`, FM nhận `DEPOSIT_REFUND_REQUIRED`, khách nhận `DEPOSIT_REFUND_PENDING` (cọc thực giữ = đã thu − đã hoàn); `Deposited` → "Quá hạn đặt lịch check-in"; `Scheduled`/`InProgress` → "Không đến nhận kho quá hạn giữ" (chưa đến) / "Chưa hoàn tất bàn giao trong hạn" (đã đến) |
| Nhắc sắp hết hạn | `expires_at` trong `order.expiry_reminder_days` (3) tới, chưa đóng | `RENTAL_ORDER_EXPIRING_SOON` cho khách, mỗi đơn một lần (dedup qua `OrderNotification` theo đơn + type) |

Hoá đơn gắn hợp đồng (`Rental`, `Extension`, `Penalty`, `Service`) đi chung nhánh "gắn hợp đồng" ở trên: `Expired` +
`INVOICE_EXPIRED` + `ContractInvoiceExpired(invoiceId, contractId, invoiceType)`; module sở hữu hợp đồng tự quyết theo
loại:
- `Rental` (`INV-RNT`) — tháng đầu (hợp đồng còn `Signed`): handover đóng phiên bàn giao
  ([handover/records.md](../handover/records.md)); tháng sau (hợp đồng `Active`): FM nhận `RENT_UNPAID`, nợ giữ trên
  hoá đơn `Expired` ([tenancy/rentals.md](../tenancy/rentals.md#thu-tiền-thuê-hằng-tháng));
- `Extension` — hợp đồng không đổi, khách tạo hoá đơn gia hạn mới ([tenancy/rentals.md](../tenancy/rentals.md));
- `Penalty` `INV-OVD` — không listener riêng; lượt `OverdueJob` sau thấy không còn hoá đơn mở thì tạo hoá đơn phí quá
  hạn mới cho phần chưa trả;
- `Penalty` `INV-PEN` phần còn thiếu khi trả kho — trả kho vẫn hoàn tất, hoá đơn giữ `Expired` làm công nợ, FM nhận
  `CHECKOUT_SHORTFALL_UNPAID` ([checkout/return.md](../checkout/return.md));
- `Service` (`INV-SVC`) — chỉ `Expired`, không có xử lý thêm.

`PaymentReconcileJob` (5', `payment.reconcile.fixed-delay-ms`): giao dịch `Pending` tạo quá 20' (cửa sổ 15' + đệm
5') → hỏi cổng; payOS báo `EXPIRED`/`CANCELLED` (hoặc VNPay không có giao dịch) → `Failed`, khoá phiên nhả.
