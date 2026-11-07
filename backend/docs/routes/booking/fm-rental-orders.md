---
auth-model: permission-bound
purpose: FM xem đơn thuê của cơ sở mình, lọc theo trạng thái.
---
# GET /api/fm/rental-orders

Màn "Theo dõi đơn thuê kho" của FM.

- **Actor / quyền:** role `FM` (`/api/fm/**`) + `rental_order.read` theo cơ sở FM đang phụ trách.
- **Contract chi tiết:** annotation tại `booking/controller/FmRentalOrderActionController.java`; Swagger UI —
  `http://localhost:8080/swagger-ui/index.html`.

## Luồng / hành vi

- Cơ sở = cơ sở FM đang phụ trách (`facilities.fm_account_id`); chưa gán → 404.
- `status` (tuỳ chọn): `Pending | Deposited | Scheduled | InProgress | Canceled | Done`; sai → 400.
- Cùng read model với [rental-orders-mine.md](rental-orders-mine.md); `availableActions` cho FM:
  `REPROPOSE` (đơn chưa đóng, không có proposal `Pending`, đơn `Pending` hoặc `needs_reproposal`),
  `ASSIGN_STAFF` (đơn `Scheduled`, lịch `Pending` chưa có FS), `CANCEL` (đơn chưa đóng).

## Ghi chú nghiệp vụ

- Màn FE dùng route này: danh sách đơn thuê của FM.

## Liên quan

- Xem thêm: [routes.md](../../routes.md) · [index.md](../../index.md) · [rental-orders-mine.md](rental-orders-mine.md)
