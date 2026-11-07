---
auth-model: ownership
purpose: Khách đặt lịch check-in — tạo Appointment + RentalAppointment + HandoverRecord.
---
# POST /api/rental-orders/{orderId}/appointments

Khách xác nhận một khung giờ sau khi đã đặt cọc.

- **Actor / quyền:** bearer + `ACCOUNT_ACTIVE` + ownership (→ 404).
- **Contract chi tiết:** annotation tại `booking/controller/RentalOrderController.java`; Swagger UI —
  `http://localhost:8080/swagger-ui/index.html`.

## Luồng / hành vi

- Xem [booking/appointment-booking.md](../../booking/appointment-booking.md) › Đặt lịch.

## Ghi chú nghiệp vụ

- Đơn đã có lịch `Pending` → 409; khung đã có người → 409; khung sai giờ / đã qua / ngày ngoài cửa sổ → 400.

## Liên quan

- Xem thêm: [routes.md](../../routes.md) · [index.md](../../index.md) · [appointment-booking.md](../../booking/appointment-booking.md)
