---
auth-model: permission-bound
purpose: FM xem đơn thuê để đề xuất lại — lịch sử proposal, số lần từ chối, khoang trống cùng loại.
---
# GET /api/fm/rental-orders/{orderId}

Dữ liệu cho màn "Đề xuất lại khoang" (`/fm/rental-orders/:id/re-propose`).

- **Actor / quyền:** role-bound `FM` (`/api/fm/**`) + permission `rental_order.read` theo cơ sở của đơn
  (`Access.can`), FM cơ sở khác → 403 — cùng cách hàng đợi yêu cầu đặt kho của FM.
- **Contract chi tiết:** annotation tại `booking/controller/FmRentalOrderController.java`; Swagger UI —
  `http://localhost:8080/swagger-ui/index.html`.

## Luồng / hành vi

- Đơn + thông tin khách và nhu cầu lấy từ `rental_requests` (tên, email, SĐT, loại khoang, ngày bắt đầu,
  số tháng).
- `requestId`, `requestStatus`: request gốc của đơn.
- `needsReproposal`: flag trên order, chỉ bật khi order đã giữ khoang (`Deposited`/`Scheduled`/`InProgress`).
  Order còn `Pending` sau khi khách từ chối proposal có flag `false` nhưng vẫn được đề xuất lại — điều kiện
  thật là `!closed && (Pending || needsReproposal) && không có proposal Pending`.
- `proposals`: toàn bộ lịch sử proposal của đơn, mới nhất trước, kèm `note` lý do từ chối.
- `availableUnits`: khoang `Available` cùng cơ sở, cùng loại khách cần; `previouslyRejected = true` nghĩa là
  chọn lại cần `overrideReason`.
- Chỉ đọc, không ghi gì.

## Ví dụ

```bash
TOKEN='<token của fm1@lemar.vn>'

curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/fm/rental-orders/3
# {"message":"Lấy chi tiết đơn thuê thành công","data":{"id":3,"status":"Pending","cancelReason":null,"expiresAt":"2026-12-02T13:21:46.848787Z","unitId":null,"facilityId":1,"requestId":4,"requestStatus":"Converted","needsReproposal":false,"customer":{"accountId":9,"name":"phamquochung170505","email":"phamquochung170505@gmail.com","phone":"0123467891"},"requested":{"unitTypeId":2,"unitTypeName":"Medium","startDate":"2026-11-24","period":2},"rejectionCount":1,"maxRejectionCount":3,"proposals":[{"id":3,"unitId":6,"unitCode":"B-02","status":"Rejected","note":"Không thích khoang này","createdAt":"2026-11-02T13:21:46.853749Z"}],"availableUnits":[{"id":5,"code":"B-01","size":null,"monthlyPrice":900000.00,"previouslyRejected":false},{"id":6,"code":"B-02","size":null,"monthlyPrice":900000.00,"previouslyRejected":true},{"id":7,"code":"B-03","size":null,"monthlyPrice":900000.00,"previouslyRejected":false}]}}

# fm2@lemar.vn (cơ sở TD) gọi đơn của Q7
# {"message":"Bạn không được gán vào cơ sở này","timestamp":"2026-10-15T15:04:26.316557800Z"}
```

## Liên quan

- [rental-order-proposals-create.md](rental-order-proposals-create.md) · [routes.md](../../routes.md) · [index.md](../../index.md)
