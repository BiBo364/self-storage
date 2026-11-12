---
auth-model: role-bound
purpose: FS — "Đơn đang bàn giao" và các thao tác Flow 2.2–2.5 trên phiên được phân công.
---

# /api/staff/handovers, /api/staff/contracts

- **Actor / quyền:** role-bound `FS` (`/api/staff/**`) + permission theo cơ sở; thao tác ghi chỉ trên phiên có
  `appointments.staff_id` = FS đang đăng nhập (sai → 403 "Bạn không phải FS phụ trách lịch này").
- **Contract chi tiết:** annotation tại `handover/controller/StaffHandoverController.java`; behavior tại
  [handover/records.md](../../handover/records.md).

| Method | Path | Permission | Body | Kết quả |
|---|---|---|---|---|
| GET | `/api/staff/handovers` | — | — | Phiên `IN_PROGRESS`, khách đã đến, lịch phân cho FS |
| GET | `/api/staff/handovers/{id}` | `rental_order.read` | — | `HandoverView` |
| POST | `/api/staff/handovers/{id}/identity` | `handover_record.verify_identity` | `{verified, note?, photos?[]}` — `verified=true` cần 1–4 ảnh CCCD/hộ chiếu (upload của FS này) | `Verified` / `Failed`; ảnh lưu `identity_photos`, trả trong `identityPhotos` |
| PUT | `/api/staff/handovers/{id}/inspection` | `handover_record.inspect` | `{notes?, photos?[]}` | Lưu hiện trạng (sửa được tới khi khách chốt); ảnh phải là upload của FS này |
| POST | `/api/staff/handovers/{id}/inspection-correction` | `handover_record.inspect` | `{notes?, photos?[], reason}` | Sửa hiện trạng sau khi khách đã đồng ý; audit `INSPECTION_CORRECTED`; hợp đồng đã ký không đổi |
| POST | `/api/staff/contracts/{contractId}/start-date-override` | `rental_contract.start_date_override.request` | `{requestedDate, reason}` | Đề nghị `Pending`, khoá bước ký |
| POST | `/api/staff/contracts/{contractId}/sign` | `rental_contract.sign` | `{pdfUrl}` (upload của FS này) | Hợp đồng `Signed`, chốt bản in `document_html` + hoá đơn `INV-RNT-…` (trả trong `data`) |
| POST | `/api/staff/handovers/{id}/not-signed` | `rental_contract.sign` | — | Cascade "Khách không ký hợp đồng" (chỉ khi hợp đồng `Draft`) |
| POST | `/api/staff/handovers/{id}/keys` | `unit_access_key.issue` | `{quantity}` (1–10) | Chốt bàn giao (khách đã chọn khóa cơ / cả hai) |

Mutation trên `/handovers/{id}` trả `HandoverView` mới (`step` = bước kế tiếp). Lỗi:
- 400 — body sai; ảnh hiện trạng / ảnh giấy tờ / `pdfUrl` không phải file FS này đã upload qua `/api/media` (upload
  `Pending` / `Attached`); xác minh đạt mà không có ảnh giấy tờ, hoặc > 4 ảnh giấy tờ; sửa hiện trạng thiếu `reason`, `reason` > 500 ký tự, > 20 ảnh, hoặc sau khi sửa không còn ghi chú lẫn
  ảnh;
- 403 — không đúng FS / thiếu quyền;
- 404 — không có phiên;
- 409 — sai thứ tự bước, phiên đã đóng (kể cả sửa hiện trạng sau khi đã chốt bàn giao), đơn không `InProgress`; sửa
  hiện trạng khi khách chưa đồng ý hiện trạng.

`HandoverView.step` (`HandoverQueryService.step`, cùng giá trị cho khách và FS) — kiểm theo thứ tự, gặp điều kiện
đầu tiên chưa đạt thì dừng:

| `step` | Khi |
|---|---|
| `COMPLETED` / `REJECTED` / `CANCELED` | phiên đã đóng — trả nguyên `result` |
| `WAITING_ARRIVAL` | FS chưa xác nhận khách đến (`arrived_at` null) |
| `VERIFY_IDENTITY` | danh tính chưa `Verified` |
| `INSPECTION` | hiện trạng chưa `Agreed` hoặc chưa sinh hợp đồng |
| `OVERRIDE_PENDING` | hợp đồng `Draft`, đang có đề nghị đổi mốc tính tiền chờ FM |
| `SIGN_CONTRACT` | hợp đồng `Draft`, không có đề nghị chờ |
| `PAY_FIRST_MONTH` | hoá đơn tháng đầu chưa có hoặc chưa `Paid` |
| `CUSTOMER_CONFIRM` | khách chưa xác nhận biên bản / chọn hình thức truy cập |
| `HAND_OVER_KEYS` | khách đã chọn hình thức truy cập — chờ FS giao chìa / chốt |

## Ví dụ

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"fs1@lemar.vn","password":"Test@1234"}' | jq -r .data.token)

curl -s -X POST http://localhost:8080/api/staff/handovers/3/identity -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"verified":true}' | jq '.message'
# "Cần ít nhất một ảnh CCCD/hộ chiếu khi xác minh đạt"   (400)

curl -s -X POST http://localhost:8080/api/staff/handovers/3/identity -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"verified":true,"photos":["https://res.cloudinary.com/x/cccd-front.jpg"]}' | jq '.data.step'
# "INSPECTION"

curl -s -X POST http://localhost:8080/api/staff/contracts/2/sign -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"pdfUrl":"https://res.cloudinary.com/x/signed.pdf"}'
# {"message":"Đã ký hợp đồng, tạo hoá đơn tháng đầu","data":{"invoiceId":12,"code":"INV-RNT-Q7-261106-K3M9QA",
#  "status":"Unpaid","amount":500000.00,"dueDate":"2026-11-07T08:00:00Z"}}
```

## Liên quan

- [customer-handovers.md](customer-handovers.md) · [contracts.md](contracts.md) · [routes.md](../../routes.md)
