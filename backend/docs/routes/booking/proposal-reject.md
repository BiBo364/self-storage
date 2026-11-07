---
auth-model: ownership
purpose: Khách từ chối đề xuất khoang kèm lý do; lần thứ max_rejection_count thì huỷ đơn.
---
# POST /api/proposals/{proposalId}/reject

Khách bấm "Từ chối" trên một proposal `Pending`, body `{ "note": "..." }` (bắt buộc).

- **Actor / quyền:** bearer token + `ACCOUNT_ACTIVE` + ownership.
- **Contract chi tiết:** annotation tại `booking/controller/ProposalController.java`; Swagger UI —
  `http://localhost:8080/swagger-ui/index.html`.

## Luồng / hành vi

- Thiếu / trống `note` → 400, không đổi gì.
- Đơn đã `Canceled`/`Done` → 409, không đổi gì.
- Khoá proposal + khoá đơn, đếm số proposal `Rejected` của đơn **trước khi** ghi.
- Chưa chạm ngưỡng: proposal `Rejected` + note, AuditLog `PROPOSAL_REJECTED`, FM nhận `PROPOSAL_REJECTED`;
  `orderCanceled = false`. Đơn đã cọc: bật `needs_reproposal` để FM re-propose tiếp.
- Chạm ngưỡng (`số Rejected + 1 >= proposal.max_rejection_count`): ghi `Rejected` rồi chạy
  Cancellation Cascade — đơn `Canceled` với lý do "Từ chối quá số khoang cho phép", proposal
  `Pending`/`Agreed` → `Canceled`, hoá đơn `Unpaid` → `Canceled`, khoang `Reserved` → `Available` **chỉ khi
  đơn bị huỷ đang giữ khoang** (`Deposited`/`Scheduled`/`InProgress` — khoang chỉ bị giữ sau khi trả cọc),
  AuditLog từng entity, khách + FM nhận `RENTAL_ORDER_CANCELED`; `orderCanceled = true`. Notification của
  khách nêu rõ "Tiền cọc không được hoàn" vì huỷ do quá ngưỡng từ chối.

## Ghi chú nghiệp vụ

- Cascade đủ bước (lịch hẹn, biên bản bàn giao, hợp đồng, phiên cổng) xem
  [cancellation-cascade.md](../../flows/cancellation-cascade.md). Hệ thống không hoàn cọc tự động; huỷ do quá ngưỡng
  từ chối thì cọc không được hoàn.

## Ví dụ

```bash
TOKEN='<token của customer1@lemar.vn>'

curl -s -X POST -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/proposals/4/reject
# {"message":"Vui lòng nhập lý do từ chối","timestamp":"2026-10-15T15:04:27.286053200Z"}

# Body UTF-8 lấy từ file (Git Bash trên Windows gửi chữ có dấu qua -d không đúng UTF-8)
curl -s -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json; charset=utf-8" \
  --data-binary @note.json http://localhost:8080/api/proposals/4/reject
# {"message":"Đã từ chối đề xuất khoang","data":{"proposal":{"id":4,"orderId":3,"customerId":6,"unitId":3,"status":"Rejected","note":"Khoang tầng cao, khó chuyển đồ","createdAt":"2026-10-15T15:04:26.551842Z"},"orderCanceled":false}}

# Lần từ chối thứ 3 của một đơn (lần chạy 13/10, path cũ /api/customer/proposals — body response không đổi)
# {"message":"Đã từ chối đề xuất khoang. Đơn đã bị huỷ do từ chối quá số khoang cho phép","data":{"proposal":{"id":5,"orderId":3,"customerId":6,"unitId":2,"status":"Rejected","note":"Không còn nhu cầu","createdAt":"2026-10-13T09:33:22.115930Z"},"orderCanceled":true}}
```

## Liên quan

- [rental-order-proposals-create.md](rental-order-proposals-create.md) · [routes.md](../../routes.md) ·
  [index.md](../../index.md)
