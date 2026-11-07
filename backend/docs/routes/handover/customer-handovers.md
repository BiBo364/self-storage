---
auth-model: role-bound
purpose: Khách — xem phiên bàn giao, đồng ý / từ chối khoang, xác nhận biên bản, lấy mã khóa số.
---

# /api/customer/handovers, /api/customer/contracts

- **Actor / quyền:** role-bound `CUSTOMER` (`/api/customer/**`); chỉ phiên của đơn thuộc khách (khác → 404).
- **Contract chi tiết:** annotation tại `handover/controller/CustomerHandoverController.java`; behavior tại
  [handover/records.md](../../handover/records.md). Huỷ đơn dùng `POST /api/rental-orders/{orderId}/cancel`.

| Method | Path | Body | Kết quả |
|---|---|---|---|
| GET | `/api/customer/handovers` | — | Phiên của tôi — đang mở trước |
| GET | `/api/customer/handovers/{id}` | — | `HandoverView` (hợp đồng, hoá đơn tháng đầu, khoá đã cấp, loại truy cập cơ sở bật) |
| POST | `/api/customer/handovers/{id}/agree` | `{note?}` (tuỳ chọn) | Hiện trạng `Agreed`, sinh hợp đồng `Draft` |
| POST | `/api/customer/handovers/{id}/reject` | `{reason, confirmCancel?}` | Từ chối khoang; chạm ngưỡng mà chưa xác nhận → `requiresConfirmation=true`, không ghi gì |
| POST | `/api/customer/handovers/{id}/confirm` | `{accessType}` = `AccessCode` \| `PhysicalKey` \| `Both` | `AccessCode` → chốt ngay, `data.accessCode` (hiện một lần); còn lại → chờ FS giao chìa |
| POST | `/api/customer/contracts/{contractId}/access-code` | — | Thu hồi mã cũ, cấp mã mới (`data.accessCode`) — hợp đồng `Active`, cơ sở bật mã số |

Lỗi 409 thường gặp:
- xác nhận khi hoá đơn tháng đầu chưa `Paid`;
- từ chối sau khi đã ký ("chỉ còn lựa chọn huỷ đơn");
- xác nhận lần hai.

Lỗi 400:
- `accessType` sai;
- loại truy cập cơ sở không bật.

## Ví dụ

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"customer1@lemar.vn","password":"Test@1234"}' | jq -r .data.token)

curl -s -X POST http://localhost:8080/api/customer/handovers/3/reject -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"reason":"Khoang ẩm"}'
# {"message":"Đã ghi nhận từ chối. Cơ sở sẽ đề xuất khoang khác cho bạn.",
#  "data":{"requiresConfirmation":false,"orderCanceled":false,"message":"..."}}

curl -s -X POST http://localhost:8080/api/customer/handovers/3/confirm -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"accessType":"AccessCode"}'
# {"message":"Bàn giao hoàn tất. Mã truy cập chỉ hiển thị một lần — hãy lưu lại.",
#  "data":{"completed":true,"accessCode":"482915","message":"..."}}
```

## Liên quan

- [staff-handovers.md](staff-handovers.md) · [contracts.md](contracts.md) · [routes.md](../../routes.md)
