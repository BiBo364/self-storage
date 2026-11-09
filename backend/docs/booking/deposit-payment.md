---
purpose: Luồng thanh toán hoá đơn (cọc, cọc bổ sung, hoá đơn gắn hợp đồng) từ nút "Thanh toán" tới khi hoá đơn Paid — as-built.
---
# Thanh toán hoá đơn & chốt cọc (Flow 1.4)

Code: `booking/application/InvoicePaymentService.java` (điều phối nút thanh toán), `DepositPaymentService.java` (năm
nhánh kiểm hoá đơn cọc), `DepositPaymentListener.java` (chốt hoá đơn cọc), `InvoicePaymentSettlement.java` (chốt hoá
đơn gắn hợp đồng), `CompetingInvoiceCanceller.java` (chặn hoá đơn cọc khác của khoang), `InvoiceRefundService.java`
(đánh dấu cần hoàn), `payment/application/PaymentService.java` (giao dịch, IPN/webhook, tra cứu cổng). Contract
endpoint: [routes/booking/invoice-pay.md](../routes/booking/invoice-pay.md).

## Sơ đồ

```mermaid
sequenceDiagram
    actor K as Khách
    participant B as booking (InvoicePaymentService / DepositPaymentService)
    participant P as payment (PaymentService)
    participant V as Cổng (VNPay / payOS)
    participant S as booking (DepositPaymentListener / InvoicePaymentSettlement)
    K->>B: POST /api/invoices/{id}/pay
    B->>B: hoá đơn cọc: khoá đơn → hoá đơn → khoang, kiểm 5 nhánh spec 1.4
    B->>P: startPayment (ghi PaymentTransaction Pending, cache URL)
    P-->>K: paymentUrl (bấm lại trong 15' → trả URL cũ)
    K->>V: thanh toán
    alt IPN / webhook về
        V->>P: GET /api/payments/vnpay/ipn · POST /api/payments/payos/webhook
    else Khách quay về, xác nhận chưa tới
        K->>B: POST /api/invoices/{id}/payment-check
        B->>P: reconcileLatest → hỏi cổng
    end
    P->>P: khoá invoice → Success (hoặc Duplicate) + PaymentSucceeded (commit)
    P-->>S: AFTER_COMMIT, REQUIRES_NEW
    S->>S: hoá đơn Paid; Deposit → khoang Reserved + đơn Deposited
```

## Trạng thái

| Hoá đơn | Khi | Hệ quả |
|---|---|---|
| `Deposit` `Unpaid → Paid` | cổng xác nhận | khoang `Available → Reserved` (`reserved_order_id` = đơn), đơn `Pending → Deposited`, khách nhận `DEPOSIT_PAYMENT_SUCCEEDED` |
| `Deposit` `Unpaid → Paid`, khoang đã bị đơn khác giữ | cổng xác nhận | hoá đơn vẫn `Paid` + `refund_status = Required` (`UNIT_UNAVAILABLE`); đơn ở lại `Pending`; FM nhận `PAYMENT_REFUND_REQUIRED`, khách `PAYMENT_REFUND_PENDING`. FM vẫn đề xuất được khoang thay thế — xem [proposal-agree.md › Cọc đã trả nhưng khoang bị giữ trước](../routes/booking/proposal-agree.md#cọc-đã-trả-nhưng-khoang-bị-giữ-trước) |
| `DepositSupplement` `Unpaid → Paid`, đơn đã giữ khoang | cổng xác nhận | reserve khoang mới; đơn không đổi trạng thái, khách nhận `DEPOSIT_SUPPLEMENT_PAYMENT_SUCCEEDED` (không gửi lại "Đặt cọc thành công"); khoang mới đã bị giữ → như dòng trên |
| `DepositSupplement` `Unpaid → Paid`, đơn còn `Pending` (cọc đã trả, khoang cũ bị giữ trước) | cổng xác nhận | chốt cọc như `Deposit`: khoang thay thế `Reserved`, đơn `Pending → Deposited`, audit `RENTAL_ORDER_DEPOSITED`, khách nhận `DEPOSIT_PAYMENT_SUCCEEDED` |
| gắn hợp đồng `Unpaid → Paid` | cổng xác nhận | phát `ContractInvoicePaid(invoiceId, contractId, type, paidAt)` cho module sở hữu hợp đồng |
| đã đóng (`Canceled`/`Expired`) mà tiền vẫn vào | cổng xác nhận muộn | giao dịch `Success` (hoặc `Duplicate`), log ERROR, hoá đơn `refund_status = Required` (`INVOICE_CLOSED`); hoá đơn cọc: FM nhận `PAYMENT_REFUND_REQUIRED`, khách `PAYMENT_REFUND_PENDING`; gắn hợp đồng: FM nhận `FIRST_MONTH_REFUND_REQUIRED` |
| `Unpaid → Canceled` | bấm thanh toán khi khoang `Reserved`/`Rented`/`Maintenance` | FM nhận `PROPOSAL_REPROPOSAL_REQUIRED`; đơn giữ trạng thái, đơn đã cọc (đổi khoang) bật `needs_reproposal` để FM đề xuất lại được và đơn không đặt lịch check-in trên khoang đã mất |
| `Unpaid → Canceled` | khoang vừa có người cọc / đổi khoang `delta ≤ 0` giữ khoang | `CompetingInvoiceCanceller` — xem Bất biến |
| `Unpaid → Expired` | cron quá `due_date` | xem [Cron Flow 1](#cron-flow-1) |

Hoá đơn gắn hợp đồng sau bàn giao (tiền thuê tháng, gia hạn, phí quá hạn, quyết toán trả kho) có đơn ở `Done`. Bấm
thanh toán bỏ qua các kiểm tra trạng thái / hạn của đơn. Xem [tenancy/rentals.md](../tenancy/rentals.md).

Audit nhánh thành công (actor = hệ thống): `PAYMENT_SUCCEEDED` (PaymentTransaction, module `payment`),
`INVOICE_PAID` (Invoice), `RENTAL_ORDER_DEPOSITED` (RentalOrder), `STORAGE_UNIT_RESERVED` (StorageUnit). Cọc bổ sung
trên đơn đã giữ khoang không có `RENTAL_ORDER_DEPOSITED`; trên đơn còn `Pending` thì ghi đủ bốn bản như `Deposit`.

## Bất biến

- **Khoá phiên suy từ dữ liệu:** khoang bận khi còn giao dịch `Pending` (tạo trong cửa sổ 15') thuộc hoá đơn của bất
  kỳ đơn nào trỏ khoang — hết cửa sổ thì cổng không nhận tiền cho phiên đó nữa. Khoá row khoang trước khi kiểm → hai
  request song song không cùng mở được phiên.
- **Một hoá đơn tối đa một phiên mở:** bấm lại khi phiên cũ còn trong cửa sổ → trả lại URL cũ (cache Redis, hoặc dựng
  lại từ giao dịch `Pending`); không dựng lại được → 409. Không huỷ phiên cũ để mở phiên mới. Đã có `PAY/Success` →
  409, không thu lần hai.
- **Khoang vừa có người trả cọc → chặn ngay các hoá đơn khác của khoang** (spec 1.1 "đã có người khác đặt cọc"):
  sau commit bước chốt cọc, `CompetingInvoiceCanceller` (transaction riêng, khoá đơn → hoá đơn) huỷ mọi hoá đơn cọc
  `Unpaid` khác trỏ khoang (audit `INVOICE_CANCELED` lý do "Khoang đã có người đặt cọc trước"), FM nhận
  `PROPOSAL_REPROPOSAL_REQUIRED`, khách nhận `PROPOSAL_UNIT_TAKEN` ("Khoang [mã] đã có người đặt cọc trước. Cơ sở
  đang tìm khoang khác phù hợp cho bạn."); đơn không huỷ (đơn đã cọc thì bật `needs_reproposal`); phiên còn mở của
  các hoá đơn đó bị huỷ ở cổng (payOS; VNPay không hỗ trợ huỷ).
- **Thứ tự khoá:** đơn → hoá đơn → khoang, ở cả bấm thanh toán và chốt thanh toán — cùng thứ tự với đồng ý đề xuất
  và Cancellation Cascade, không deadlock ngược chiều. Hoá đơn gắn hợp đồng (tháng đầu) không có `order_id` (CHECK
  V1: đúng một nguồn) — đơn được tìm qua `rental_contracts.order_id` (`InvoiceRepository.findOrderIdById`) để vẫn
  khoá đơn trước.
- **Listener chốt chạy `AFTER_COMMIT` + `REQUIRES_NEW`:** lỗi ở bước chốt không rollback giao dịch của cổng (tiền
  đã vào, cổng không retry vô ích). Hoá đơn cọc còn `Unpaid` sau khi giao dịch đã `Success` được
  `DepositStateRepairJob` sửa lại ([payment.md](../payment.md#các-lớp-xác-nhận--đối-soát)).
- **Xác nhận muộn (lưới an toàn cuối):** mọi đường huỷ hoá đơn `Unpaid` đều huỷ phiên cổng sau commit
  (`PaymentSessionCloser.closeAfterCommit`) — Cascade, khoang đã có người cọc trước, FM đề xuất lại khoang khác, hợp
  đồng huỷ. Nhánh này còn lại khi: khách tự chuyển khoản theo QR cũ sau khi link đã huỷ; cổng không huỷ được phiên
  (VNPay); hoặc xác nhận về sau khi cron đã hết hạn hoá đơn / huỷ đơn. Khi đó tiền vẫn được ghi (`Success`, hoặc
  `Duplicate` nếu hoá đơn đã có giao dịch thành công khác), log ERROR, hoá đơn vào hàng đợi hoàn tiền
  (`refund_status = Required`); FM chuyển khoản hoàn rồi ghi nhận qua `POST /api/fm/invoices/{invoiceId}/refunds`
  ([payment.md › Hoàn tiền thủ công](../payment.md#hoàn-tiền-thủ-công)). Không tự mở lại đơn.
- **Không có job tự hỏi cổng:** nguồn dự phòng ngoài IPN/webhook là `payment-check` (khách quay về) và đối soát thủ
  công của ADMIN; return URL không bao giờ ghi dữ liệu.

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
| Catch-all đơn | `expires_at < now`, chưa `Canceled`/`Done`, không còn phiên mở | cascade, lý do: `Pending` + proposal chưa `Agreed` → "Quá hạn xác nhận đề xuất"; `Pending` + `Agreed` → "Quá hạn thanh toán cọc"; `Pending` mà đã thu cọc (khoang bị giữ trước, chưa xếp được khoang khác) → "Hết hạn chờ xếp khoang sau khi đã cọc" + audit `DEPOSIT_REFUND_REQUIRED`, FM nhận `DEPOSIT_REFUND_REQUIRED`, khách nhận `DEPOSIT_REFUND_PENDING` (cọc thực giữ = đã thu − đã hoàn), hoá đơn cọc vào hàng đợi hoàn tiền (`DEPOSIT_UNSERVED`); `Deposited` → "Quá hạn đặt lịch check-in"; `Scheduled`/`InProgress` → "Không đến nhận kho quá hạn giữ" (chưa đến) / "Chưa hoàn tất bàn giao trong hạn" (đã đến) |
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

