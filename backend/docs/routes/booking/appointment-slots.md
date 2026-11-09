---
auth-model: ownership
purpose: Khung giờ check-in còn trống của một đơn đã cọc.
---
# GET /api/rental-orders/{orderId}/appointment-slots

Màn chọn lịch check-in (spec 1.5) — hiển thị cửa sổ ngày + 3 khung/ngày, đánh dấu khung còn trống.

- **Actor / quyền:** bearer + `ACCOUNT_ACTIVE` + ownership (→ 404). Không dùng `Access.can` — `appointment.create` là ownership.
- **Contract chi tiết:** annotation tại `booking/controller/RentalOrderController.java`; Swagger UI —
  `http://localhost:8080/swagger-ui/index.html`.

## Luồng / hành vi

- Xem [booking/appointment-booking.md](../../booking/appointment-booking.md) › Cửa sổ ngày và khung giờ.

## Ghi chú nghiệp vụ

- Spec 1.5; khung giờ / cửa sổ ngày theo chốt behavior-gap đặt lịch check-in.

## Liên quan

- Xem thêm: [routes.md](../../routes.md) · [index.md](../../index.md) · [appointment-booking.md](../../booking/appointment-booking.md)
