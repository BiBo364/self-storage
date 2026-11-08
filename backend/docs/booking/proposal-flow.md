---
purpose: Luồng Flow 1.3 — khách đồng ý / từ chối đề xuất khoang, FM đề xuất lại, đổi khoang sau khi đã cọc.
---
# Đề xuất khoang — đồng ý, từ chối, đề xuất lại

Behavior as-built của Flow 1.3. Contract từng endpoint nằm trong `routes/booking/*`; trang này giữ
overview, state machine và invariant.

## Khách đồng ý — `POST /api/proposals/{id}/agree`

```mermaid
flowchart TB
    A0["Lock proposal → lock order"] --> A1{"Đơn closed / quá expires_at /<br/>có hoá đơn cọc-bổ sung Unpaid?"}
    A1 -- có --> AX["409"]
    A1 -- không --> A2["Lock khoang cũ + mới<br/>(thứ tự id tăng dần)"]
    A2 --> A3{"Khoang mới Available?"}
    A3 -- không --> A4["Proposal Canceled<br/>audit PROPOSAL_CANCELED<br/>notify FM"]
    A4 --> A4H{"Đơn giữ khoang?"}
    A4H -- có --> A4F["bật needs_reproposal<br/>(đơn đã qua Pending)"]
    A4H -- không --> AX
    A4F --> AX
    A3 -- có --> A5["Proposal Agreed<br/>order.unit_id = khoang mới<br/>audit PROPOSAL_AGREED"]
    A5 --> A6{"Đơn giữ khoang<br/>hoặc đã có cọc Paid?"}
    A6 -- không --> A7["Invoice Deposit / Unpaid<br/>amount = giá × deposit_months<br/>due = min(now + 3d, expires_at)"]
    A7 --> A8["200 + invoice"]
    A6 -- có --> A6H{"Đơn giữ khoang?"}
    A6H -- "có (đổi khoang sau khi cọc)" --> A9["Nhả khoang cũ nếu Reserved<br/>audit UNIT_CHANGE_RELEASED<br/>fee = fee.unit_change"]
    A6H -- "không (cọc Paid, khoang cũ bị<br/>đơn khác giữ trước — đơn Pending)" --> A9P["Không nhả khoang cũ<br/>fee = 0"]
    A9 --> A10["delta = giá mới × months + fee<br/>− cọc thực giữ (Paid − đã hoàn)"]
    A9P --> A10
    A10 --> A11{"delta"}
    A11 -- "> 0" --> A12["Invoice DepositSupplement / Unpaid<br/>notify khách UNIT_CHANGE_PAYMENT_REQUIRED"]
    A12 --> A13["200 + invoice<br/>khoang mới Available tới khi hoá đơn bổ sung Paid<br/>→ Reserved (đơn Pending → Deposited)"]
    A11 -- "≤ 0" --> A16["Khoang mới Available → Reserved<br/>audit STORAGE_UNIT_RESERVED"]
    A16 --> A16P{"Đơn Pending?"}
    A16P -- có --> A16D["Đơn → Deposited<br/>audit RENTAL_ORDER_DEPOSITED<br/>notify khách DEPOSIT_PAYMENT_SUCCEEDED"]
    A16P -- không --> A17{"delta < 0?"}
    A16D --> A17
    A17 -- có --> A14["audit DEPOSIT_REFUND_REQUIRED<br/>notify FM + khách"]
    A14 --> A15["200, invoice = null"]
    A17 -- không --> A15
```

> `Đơn giữ khoang?` là `RentalOrder.holdsUnit()` — đơn `Deposited`/`Scheduled`/`InProgress`. Nhánh khoang không
> Available dùng nó để bật `needs_reproposal`; nhánh thành công dùng nó để quyết định có nhả khoang cũ và có tính
> `fee.unit_change` hay không.
>
> Đơn đã trả cọc nhưng khoang bị đơn khác giữ trước (thanh toán chốt cọc thấy khoang không còn `Available`, đơn ở lại
> `Pending` với `Deposit` `Paid`) đi nhánh "không giữ khoang": khách không gây ra việc đổi khoang nên không thu phí
> đổi khoang, khoang cũ không phải của đơn nên không nhả. Cọc đã đủ → giữ khoang thay thế và chốt đơn `Deposited`
> ngay; còn thiếu → hoá đơn `DepositSupplement`, khi trả xong `InvoicePaymentSettlement` thấy đơn còn `Pending` thì chốt
> cọc như hoá đơn `Deposit` (giữ khoang, đơn `Deposited`, audit `RENTAL_ORDER_DEPOSITED`, khách nhận
> `DEPOSIT_PAYMENT_SUCCEEDED`).

## Khách từ chối — `POST /api/proposals/{id}/reject`

```mermaid
flowchart TB
    R0["Lock proposal → lock order"] --> R1{"note trống?"}
    R1 -- có --> R400["400"]
    R1 -- không --> R2{"Đơn closed?"}
    R2 -- có --> R409["409"]
    R2 -- không --> R3{"Rejected + 1 >= max?"}
    R3 -- không --> R4["Proposal Rejected + audit<br/>notify FM PROPOSAL_REJECTED<br/>đơn đã cọc → bật needs_reproposal"]
    R4 --> R5["200 orderCanceled = false"]
    R3 -- có --> R6["Cascade:<br/>order Canceled<br/>proposal Pending/Agreed → Canceled<br/>invoice Unpaid → Canceled<br/>release unit nếu đơn giữ<br/>audit từng entity<br/>notify khách + FM"]
    R6 --> R7["200 orderCanceled = true"]
```

Cascade chi tiết: [flows/cancellation-cascade.md](../flows/cancellation-cascade.md).

## FM đề xuất lại — `POST /api/rental-orders/{id}/proposals`

```mermaid
flowchart TB
    P0["Permission repropose + facility"] --> P1["Lock order"]
    P1 --> P2{"closed / quá expires_at /<br/>có Pending /<br/>không (Pending hoặc needs_reproposal)?"}
    P2 -- có --> P409["409"]
    P2 -- không --> P3["Lock khoang mới<br/>cùng cơ sở + Available?"]
    P3 -- không --> P44["404 / 409"]
    P3 -- có --> P4{"Khoang đã từng bị từ chối?"}
    P4 -- có --> P5["Bắt buộc overrideReason<br/>+ permission override<br/>+ audit lý do"]
    P4 -- không --> P6["Huỷ hoá đơn Deposit/DepositSupplement Unpaid<br/>audit INVOICE_CANCELED<br/>huỷ phiên cổng sau commit"]
    P5 --> P6
    P6 --> P7["Tạo Proposal Pending<br/>clear needs_reproposal<br/>audit PROPOSAL_REPROPOSED<br/>notify khách"]
    P7 --> P8["200"]
```

## State machines

| Entity | Transition |
|---|---|
| `ProposalFeedback` | `Pending → Agreed` · `Pending → Rejected` · `Pending → Canceled` (unit unavailable) · `Agreed → Canceled` (cascade) |
| `RentalOrder` | `Pending → Deposited` (thanh toán cọc; hoặc cọc đã `Paid` mà khoang bị giữ trước — đồng ý khoang thay thế khi cọc đủ / trả xong hoá đơn bổ sung) · `Pending/Deposited/Scheduled/InProgress → Canceled` (cascade) · `Deposited → Scheduled → InProgress → Done` (Flow 1.5/2) |
| `StorageUnit` | `Available ↔ Reserved` (thanh toán cọc / release) · `Available ↔ Maintenance` (Flow 2.5) |
| `Invoice` | `Unpaid → Paid` (thanh toán cọc) · `Unpaid → Canceled` (cascade / re-propose) · `Unpaid → Expired` (cron) |

## Invariants

- Một `Deposit` active (`Unpaid`/`Paid`) + một `DepositSupplement` `Unpaid` trên mỗi đơn — partial unique index của V11.
- Cọc = giá hiệu lực × `invoice.deposit_months`; hạn = `min(now + deposit_due_days, expires_at)`.
- Delta = cọc khoang mới + `fee.unit_change` (chỉ khi đơn giữ khoang) − cọc thực đang giữ (tổng `Paid` của `Deposit` + `DepositSupplement` trừ các khoản hoàn thủ công đã ghi nhận trên các
  hoá đơn đó — đổi sang khoang rẻ hơn rồi đổi tiếp thì không tính lại phần đã hoàn).
- Chỉ nhả khoang khi đơn thực sự giữ khoang (`Deposited`/`Scheduled`/`InProgress`) — cả khi huỷ đơn lẫn khi đổi
  khoang (`releaseReplacedUnit`).
- FM đề xuất lại huỷ hoá đơn `Deposit`/`DepositSupplement` `Unpaid` của đơn và **huỷ phiên payOS/VNPay của các hoá đơn
  đó sau commit** (`PaymentSessionCloser.closeAfterCommit`, lý do "FM đề xuất lại khoang khác") — khách không còn trả
  được vào link của hoá đơn đã huỷ.
- Khoang mới: còn thiếu cọc → giữ khoang khi hoá đơn bổ sung `Paid`; cọc đã đủ (`delta ≤ 0`, không có hoá đơn
  bổ sung) → `Available → Reserved` ngay trong bước đồng ý, audit `STORAGE_UNIT_RESERVED` (lý do "Đổi khoang sau
  khi đã cọc"). Đơn đã cọc luôn giữ khoang của mình — đặt lịch check-in và bàn giao đều cần khoang `Reserved`.
- Audit một bản ghi mỗi entity thay đổi.

## Ranh giới

- **Flow 2** bật `needs_reproposal`, trả đơn về `Deposited` và gửi FM `HANDOVER_REJECTED` khi khách từ chối khoang tại
  check-in chưa chạm ngưỡng (`HandoverOrderService.rejectUnit`); khoang cũ vẫn `Reserved` tới khi khách đồng ý
  khoang mới.
- **FM không đề xuất lại khi còn proposal `Pending`** (quyết định của chủ dự án): mọi lần gọi khi khách chưa phản hồi
  trả 409; FM liên hệ khách hoặc huỷ đơn.
- **Thanh toán cọc** phải phân biệt `Deposit` và `DepositSupplement`; không mark `Deposited` lần hai cho hoá đơn bổ sung.
- Hoàn tiền do FM chuyển khoản thủ công ngoài hệ thống rồi ghi nhận qua `POST /api/fm/invoices/{invoiceId}/refunds`
  ([payment.md › Hoàn tiền thủ công](../payment.md#hoàn-tiền-thủ-công)); luồng này chỉ notify + audit.
- **FE** nối endpoint + cảnh báo trước lần từ chối cuối bằng `rejectionCount`/`maxRejectionCount`.
