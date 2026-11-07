---
auth-model: ownership
purpose: Danh sách / chi tiết đơn thuê của khách kèm availableActions.
---
# GET /api/rental-orders · GET /api/rental-orders/{orderId}

Trang "Kho của tôi": mỗi đơn gom nhu cầu, khoang, proposal / hoá đơn / lịch hẹn / biên bản / hợp đồng mới nhất.

- **Actor / quyền:** bearer + `ACCOUNT_ACTIVE` + ownership (đơn người khác → 404).
- **Contract chi tiết:** annotation tại `booking/controller/RentalOrderController.java`; Swagger UI —
  `http://localhost:8080/swagger-ui/index.html`.

## Luồng / hành vi

- Read model JDBC (`OrderOverviewReadRepository`) — lateral join lấy bản ghi mới nhất của từng bảng liên quan.
- Hoá đơn hiển thị = hoá đơn `Unpaid` mới nhất của đơn, không có thì hoá đơn mới nhất.
- `availableActions` (backend tính, FE chỉ render):

| Action | Điều kiện |
|---|---|
| `RESPOND_PROPOSAL` | proposal mới nhất `Pending`, đơn chưa đóng |
| `PAY_INVOICE` | hoá đơn hiển thị `Unpaid`, đơn chưa đóng |
| `BOOK_CHECKIN` | đơn `Deposited` và không còn hoá đơn `Unpaid` |
| `HANDOVER` | đơn `InProgress`, lịch mới nhất `Done` (khách đã đến), biên bản `IN_PROGRESS` |
| `VIEW_CONTRACT` | có hợp đồng `Active`/`Ended` |
| `CANCEL` | đơn chưa `Canceled`/`Done` |

## Ghi chú nghiệp vụ

- Huỷ sau khi cọc thì mất cọc — FE phải cảnh báo trước khi gọi `POST …/cancel`.

## Liên quan

- Xem thêm: [routes.md](../../routes.md) · [index.md](../../index.md) · [rental-order-cancel.md](rental-order-cancel.md)
