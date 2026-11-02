---
auth-model: role-bound
purpose: Hàng đợi yêu cầu đặt kho cho FM — chỉ thấy cơ sở của mình, kèm bước tiếp theo của đơn.
---

# GET /api/fm/rental-requests

Hàng đợi `RentalRequest` cho FM xử lý: lọc theo facility + status, phân trang, mới nhất trước.
Mỗi dòng kèm trạng thái đơn/proposal để FM biết bước tiếp theo.

- **Actor / quyền:** role-bound `FM` (`/api/fm/**` trong `SecurityConfig`) + permission-bound
  `rental_request.read` theo facility qua `Access.can(permission, facilityId)`.
- **Contract chi tiết:** annotation tại `booking/controller/FmRentalRequestController.java`;
  Swagger UI — `http://localhost:8080/swagger-ui/index.html`.

## Luồng / hành vi

- Query: `facilityId` bắt buộc; `status` tùy chọn (`Pending/Rejected/Approved/Converted/Expired`);
  `page` mặc định 0, `size` mặc định 20 tối đa 100; sắp xếp `createdAt` giảm dần.
- Guard: thiếu permission → 403 "Bạn không có quyền thực hiện thao tác này"; ngoài facility
  → 403 "Bạn không được gán vào cơ sở này"; facility không tồn tại → 404.
- Trả envelope `{message, data: {items, page, size, total}}`; từng entry không có field facility
  (spec draft.md NOTES §1.1).
- Mỗi entry có thêm: `orderId` (null nếu chưa convert), `orderStatus` (null nếu chưa convert),
  `nextAction`, `lastProposal` (proposal gần nhất), `rejectionCount`, `maxRejectionCount`.
  `nextAction` do BE tính từ trạng thái request + order + proposal; FE không tự suy luận.

## `nextAction`

| Giá trị | Khi nào | FE |
|---|---|---|
| `APPROVE_REQUEST` | Request `Pending`, chưa có order | Nút "Đề xuất" (mở form duyệt/từ chối) |
| `WAIT_CUSTOMER` | Order có proposal `Pending` | Nút "Xem"; chờ khách phản hồi |
| `REPROPOSE_UNIT` | Order chưa đóng, không có proposal `Pending`, `(order.status = Pending || order.needs_reproposal)`, proposal gần nhất không phải `Agreed` | Nút **"Đề xuất lại"** → `/fm/rental-orders/{orderId}/re-propose` |
| `VIEW` | Không có action nghiệp vụ: order closed/`Done`, proposal gần nhất `Agreed` (chờ thanh toán/lịch), order `Scheduled`/`InProgress`, request `Rejected`/`Expired`/`Approved` chưa có account | Chỉ mở detail |

`VIEW` **không phải** action — nó là trạng thái "FM không cần làm gì". Row vẫn hiển thị status gốc
của request; FE chỉ mở detail, không hiện nút nghiệp vụ.

`REPROPOSE_UNIT` áp dụng cho cả hai ca:

- order còn `Pending` sau khi khách từ chối proposal (không set `needs_reproposal`);
- order `Deposited`/`Scheduled`/`InProgress` có `needs_reproposal = true`.

## Ghi chú nghiệp vụ

- Spec: `specs/draft.md §1.1`; permission `rental_request.read` (FACILITY) đã seed V3.
- `lastProposal` là proposal mới nhất của order, kèm `unitCode`, `status`, `note`, `createdAt`;
  `rejectionCount` đếm proposal `Rejected` của order; `maxRejectionCount` lấy từ policy
  `proposal.max_rejection_count`.
- Request `Converted` vẫn nằm trong hàng đợi; trạng thái request không đổi khi khách từ chối
  proposal. Bước tiếp theo nằm trên order và được phản ánh qua `nextAction`.

## Ví dụ

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"fm1@lemar.vn","password":"Test@1234"}' | jq -r .data.token)

# 200 — request Converted, order Pending, proposal gần nhất Rejected → cần đề xuất lại
curl -s "http://localhost:8080/api/fm/rental-requests?facilityId=1&status=Converted&page=0&size=1" \
  -H "Authorization: Bearer $TOKEN"
# {"message":"Lấy danh sách yêu cầu đặt kho thành công","data":{"items":[{"id":4,"customerName":"phamquochung170505","customerEmail":"phamquochung170505@gmail.com","customerPhone":"0123467891","unitTypeId":2,"startDate":"2026-11-24","period":2,"status":"Converted","createdAt":"2026-11-02T13:21:13.352906Z","expiresAt":"2026-11-09T13:21:46.842322Z","orderId":3,"orderStatus":"Pending","nextAction":"REPROPOSE_UNIT","lastProposal":{"id":3,"unitCode":"B-02","status":"Rejected","note":"Không thích khoang này","createdAt":"2026-11-02T13:21:46.853749Z"},"rejectionCount":1,"maxRejectionCount":3}],"page":0,"size":1,"total":3}}

# 403 — FM Q7 hỏi hàng đợi của cơ sở TD
curl -s "http://localhost:8080/api/fm/rental-requests?facilityId=2" -H "Authorization: Bearer $TOKEN"
# {"message":"Bạn không được gán vào cơ sở này","timestamp":"2026-10-16T14:51:43.435261665Z"}
```

## Liên quan

- Xem thêm: [routes.md](../routes.md) · [index.md](../index.md) · [proposal-flow.md](../../booking/proposal-flow.md)
