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
    A4 --> A4H{"Đơn đã cọc?"}
    A4H -- có --> A4F["bật needs_reproposal<br/>(đơn đã qua Pending)"]
    A4H -- không --> AX
    A4F --> AX
    A3 -- có --> A5["Proposal Agreed<br/>order.unit_id = khoang mới<br/>audit PROPOSAL_AGREED"]
    A5 --> A6{"Đơn đã cọc?"}
    A6 -- chưa --> A7["Invoice Deposit / Unpaid<br/>amount = giá × deposit_months<br/>due = min(now + 3d, expires_at)"]
    A7 --> A8["200 + invoice"]
    A6 -- rồi --> A9["Nhả khoang cũ nếu Reserved<br/>audit UNIT_CHANGE_RELEASED"]
    A9 --> A10["delta = giá mới × months + fee<br/>− tổng Paid (Deposit + DepositSupplement)"]
    A10 --> A11{"delta"}
    A11 -- "> 0" --> A12["Invoice DepositSupplement / Unpaid<br/>notify khách UNIT_CHANGE_PAYMENT_REQUIRED"]
    A12 --> A13["200 + invoice<br/>khoang mới vẫn Available<br/>(thanh toán cọc/follow-up reserve)"]
    A11 -- "< 0" --> A14["audit DEPOSIT_REFUND_REQUIRED<br/>notify FM + khách"]
    A14 --> A15["200, invoice = null"]
    A11 -- "= 0" --> A15
```

> `đơn đã cọc?` xuất hiện hai lần vì cùng điều kiện `holdsUnit` dùng cho hai quyết định:
> nhánh khoang không Available cần bật `needs_reproposal`; nhánh thành công chọn hoá đơn đầy đủ
> hay chênh lệch.

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
    P4 -- không --> P6["Huỷ hoá đơn Deposit/DepositSupplement Unpaid<br/>audit INVOICE_CANCELED"]
    P5 --> P6
    P6 --> P7["Tạo Proposal Pending<br/>clear needs_reproposal<br/>audit PROPOSAL_REPROPOSED<br/>notify khách"]
    P7 --> P8["200"]
```

## State machines

| Entity | Transition |
|---|---|
| `ProposalFeedback` | `Pending → Agreed` · `Pending → Rejected` · `Pending → Canceled` (unit unavailable) · `Agreed → Canceled` (cascade) |
| `RentalOrder` | `Pending → Deposited` (thanh toán cọc) · `Pending/Deposited/Scheduled/InProgress → Canceled` (cascade) · `Deposited → Scheduled → InProgress → Done` (Flow 1.5/2) |
| `StorageUnit` | `Available ↔ Reserved` (thanh toán cọc / release) · `Available ↔ Maintenance` (Flow 2.5) |
| `Invoice` | `Unpaid → Paid` (thanh toán cọc) · `Unpaid → Canceled` (cascade / re-propose) · `Unpaid → Expired` (cron) |

## Invariants

- Một `Deposit` active (`Unpaid`/`Paid`) + một `DepositSupplement` `Unpaid` trên mỗi đơn — partial unique index của V11.
- Cọc = giá hiệu lực × `invoice.deposit_months`; hạn = `min(now + deposit_due_days, expires_at)`.
- Delta = cọc khoang mới + `fee.unit_change` − tổng `Paid` của `Deposit` + `DepositSupplement`.
- Chỉ nhả khoang khi đơn bị huỷ thực sự giữ khoang (`Deposited`/`Scheduled`/`InProgress`).
- Khoang mới **chưa reserve** ở luồng này — thanh toán cọc/follow-up reserve sau khi thu đủ.
- Audit một bản ghi mỗi entity thay đổi.

## Hàng đợi FM — `nextAction`

`GET /api/fm/rental-requests` trả `nextAction` cho từng request đã convert:

- `APPROVE_REQUEST` — request `Pending`, chưa có order.
- `WAIT_CUSTOMER` — order có proposal `Pending`.
- `REPROPOSE_UNIT` — order chưa đóng, không có proposal `Pending`, `(order.status = Pending ||
  needs_reproposal)`, proposal gần nhất không phải `Agreed`. Áp dụng cả order `Pending` sau khi
  khách từ chối (không set `needs_reproposal`) và order đã cọc có `needs_reproposal = true`.
- `VIEW` — không có action: order closed/`Done`, proposal gần nhất `Agreed` (chờ thanh toán/lịch),
  order `Scheduled`/`InProgress`, hoặc request không còn xử lý được. `VIEW` chỉ là trạng thái
  "FM không cần làm gì"; FE mở detail, không hiện nút nghiệp vụ.

`lastProposal`, `rejectionCount`, `maxRejectionCount` đi kèm để FM thấy lần đề xuất gần nhất và
ngưỡng từ chối trước khi quyết định.

## Ranh giới

- **Flow 2** set `needs_reproposal` + gửi `HANDOVER_REJECTED` khi khách từ chối khoang tại check-in; chưa nối.
- **Thanh toán cọc** phải phân biệt `Deposit` và `DepositSupplement`; không mark `Deposited` lần hai cho hoá đơn bổ sung.
- **Flow 4** thực thi hoàn tiền; luồng này chỉ notify + audit.
- **FE** nối endpoint + cảnh báo trước lần từ chối cuối bằng `rejectionCount`/`maxRejectionCount`.
