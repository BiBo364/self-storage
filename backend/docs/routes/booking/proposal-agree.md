---
auth-model: ownership
purpose: Khách đồng ý đề xuất khoang — gán khoang vào đơn và tạo hoá đơn đặt cọc.
---
# POST /api/proposals/{proposalId}/agree

Khách bấm "Đồng ý" trên một proposal `Pending`.

- **Actor / quyền:** bearer token + `ACCOUNT_ACTIVE` + ownership (proposal phải của account đang đăng nhập,
  sai → 403).
- **Contract chi tiết:** annotation tại `booking/controller/ProposalController.java`; Swagger UI —
  `http://localhost:8080/swagger-ui/index.html`.

## Luồng / hành vi

- Một transaction: khoá row proposal (bấm đúp / 2 tab → request sau nhận 409), khoá row đơn
  (`findByIdForUpdate`, vì đọc/ghi tiền), khoá row khoang cũ + khoang mới theo thứ tự id.
- Guard trạng thái đơn: đơn `Canceled`/`Done` → 409; `expires_at < now` → 409.
- Khoang còn `Available`, đơn chưa cọc: proposal `Agreed`, `RentalOrder.unit_id` = khoang đó, tạo `Invoice`
  `Deposit`/`Unpaid`, AuditLog `PROPOSAL_AGREED` (payload có `unitId`) + `INVOICE_CREATED`.
- Khoang `Reserved`/`Maintenance`: proposal `Canceled`, AuditLog `PROPOSAL_CANCELED`, FM nhận
  `PROPOSAL_REPROPOSAL_REQUIRED`, response 409 — các thay đổi này **vẫn được commit** (service trả kết quả,
  không ném exception). Đơn đã cọc: bật lại `needs_reproposal` để FM re-propose tiếp.

### Đổi khoang sau khi đã cọc

Đơn đã giữ khoang (`Deposited`/`Scheduled`/`InProgress`) và đang có proposal `Pending` — proposal này chỉ
sinh ra từ nhánh FM đề xuất lại sau khi khách từ chối khoang tại check-in (Flow 2):

- Khoang cũ được nhả ngay (`Reserved → Available`, AuditLog `UNIT_CHANGE_RELEASED`); `order.unit_id` đổi
  sang khoang mới.
- Khoang mới **chưa được reserve** ở bước này — bước thu cọc bổ sung reserve sau khi thanh toán;
  trường hợp `delta ≤ 0` chưa có bước reserve (follow-up).

```text
paidDeposit = tổng Invoice Deposit + DepositSupplement đã Paid của đơn
required    = giá thuê hiệu lực(khoang mới) × invoice.deposit_months + fee.unit_change
delta       = required − paidDeposit
```

- `delta > 0`: tạo `Invoice` `DepositSupplement`/`Unpaid` cho phần chênh lệch, title "Bổ sung cọc khoang chứa {mã}",
  hạn như hoá đơn cọc thường; khách nhận `UNIT_CHANGE_PAYMENT_REQUIRED` ("thanh toán để giữ khoang mới").
- `delta < 0`: không tạo hoá đơn; AuditLog `DEPOSIT_REFUND_REQUIRED` + notification
  `DEPOSIT_REFUND_REQUIRED` cho FM, khách nhận `DEPOSIT_REFUND_PENDING`. Việc ghi nhận hoàn tiền thuộc
  Flow 2/4: hiện FM hoàn thủ công; hoàn tự động chưa có trong code.
- `delta = 0`: không tạo gì.
- Response 200: `invoice = null` khi `delta ≤ 0` (message "Đã cập nhật khoang mới cho đơn thuê"); khi
  `delta > 0` là hoá đơn bổ sung (message "Đã đồng ý khoang. Vui lòng thanh toán hoá đơn đặt cọc").

## Ghi chú nghiệp vụ

- Hoá đơn: code `INV-DEP-{facility_code}-{YYMMDD}-{rand 6 ký tự}`, title "Đặt cọc khoang chứa {mã}",
  amount = giá thuê hiệu lực × `invoice.deposit_months` (seed 1), `due_date` =
  min(now + `invoice.deposit_due_days`, `RentalOrder.expires_at`).
- `fee.unit_change` seed bằng V9, mặc định 0; Flow 4 chốt giá trị thật.
- Proposal bị huỷ dùng `Canceled` thay `Expired` của spec (proposal không có TTL).

## Ví dụ

```bash
TOKEN='<token của customer1@lemar.vn>'

curl -s -X POST -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/proposals/1/agree
# {"message":"Đã đồng ý khoang. Vui lòng thanh toán hoá đơn đặt cọc","data":{"proposal":{"id":1,"orderId":1,"customerId":6,"unitId":1,"status":"Agreed","note":null,"createdAt":"2026-10-15T15:04:11.166429Z"},"invoice":{"id":1,"orderId":1,"code":"INV-DEP-Q7-261015-FCGZ4F","title":"Đặt cọc khoang chứa A-01","description":"Thanh toán đặt cọc khoang chứa A-01 để đảm bảo giữ chỗ.","type":"Deposit","status":"Unpaid","amount":500000.00,"dueDate":"2026-10-18T15:04:25.787241600Z"}}}

# Gọi lại lần 2
# {"message":"Đề xuất này đã được xử lý","timestamp":"2026-10-15T15:04:25.912597300Z"}

# Proposal 2 trỏ khoang C-01 đang Maintenance
curl -s -X POST -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/proposals/2/agree
# {"message":"Khoang đã có người đặt cọc / không khả dụng","timestamp":"2026-10-15T15:04:25.656930900Z"}

# fm1@lemar.vn gọi vào proposal của khách
# {"message":"Bạn không phải chủ của đơn thuê này","timestamp":"2026-10-15T15:04:26.051956Z"}
```

## Liên quan

- [proposals-list.md](proposals-list.md) · [routes.md](../../routes.md) · [index.md](../../index.md)
