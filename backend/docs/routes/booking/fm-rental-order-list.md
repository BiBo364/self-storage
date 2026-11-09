---
auth-model: role-bound
purpose: Danh sách đơn thuê của một cơ sở cho FM — lọc trạng thái, trace proposal, cờ đề xuất lại.
---

# GET /api/fm/rental-orders

Danh sách `RentalOrder` của cơ sở, sort `expires_at` tăng dần (null cuối), rồi `id` tăng dần.

- **Actor / quyền:** role-bound `FM` (`/api/fm/**`) + permission `rental_order.read` theo cơ sở
  qua `Access.can(permission, facilityId)`.
- **Contract chi tiết:** annotation tại `booking/controller/FmRentalOrderController.java`.

## Query

- `facilityId` bắt buộc.
- `status` tùy chọn: `Pending/Deposited/Scheduled/InProgress/Done/Canceled`.
- `page` mặc định 0; `size` mặc định 20, tối đa 100.
- Sai trạng thái/paging → 400; ngoài cơ sở → 403; cơ sở không tồn tại → 404.

## Response

`{items, page, size, total}`; mỗi item:

- `id`, `status`, `cancelReason`, `expiresAt`, `unitId`, `unitCode`
- `requestId`, `customerName`, `customerEmail`, `customerPhone`
- `needsReproposal` — flag raw trên order.
- `needsRepropose` — BE tính: order chưa đóng, không proposal `Pending`, proposal gần nhất không
  `Agreed`, `(status = Pending || needs_reproposal)`, proposal gần nhất `null/Rejected/Canceled`.
- `rejectionCount`, `maxRejectionCount`
- `lastProposal`: `{id, unitCode, status, note, createdAt}` hoặc null.
- `availableActions` — thao tác FM được làm, BE tính: `REPROPOSE` khi `needsRepropose`, `CANCEL` khi đơn chưa
  `Canceled`/`Done` (huỷ qua [fm-rental-order-cancel.md](fm-rental-order-cancel.md)). FE chỉ hiện nút theo danh sách này.

## Ví dụ

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"fm1@lemar.vn","password":"Test@1234"}' | jq -r .data.token)

curl -s "http://localhost:8080/api/fm/rental-orders?facilityId=1&status=Pending&page=0&size=20" \
  -H "Authorization: Bearer $TOKEN"
# {"message":"Lấy danh sách đơn thuê thành công","data":{"items":[
#   {"id":3,"status":"Pending","cancelReason":null,"expiresAt":"2026-12-09T...","unitId":1,
#    "unitCode":"A-01","requestId":4,"customerName":"Khách Demo","customerEmail":"...",
#    "customerPhone":"...","needsReproposal":false,"needsRepropose":true,
#    "rejectionCount":1,"maxRejectionCount":3,
#    "lastProposal":{"id":3,"unitCode":"A-01","status":"Rejected","note":"...","createdAt":"..."}}
# ],"page":0,"size":20,"total":1}}
```

## Liên quan

- [fm-rental-order-detail.md](fm-rental-order-detail.md) · [fm-rental-request-queue.md](fm-rental-request-queue.md) ·
  [routes.md](../../routes.md)
