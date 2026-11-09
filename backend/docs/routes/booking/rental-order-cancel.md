---
auth-model: ownership
purpose: Khách huỷ đơn thuê — Cancellation Cascade lý do "Khách hủy đơn".
---
# POST /api/rental-orders/{orderId}/cancel

Khách chủ động huỷ đơn chưa đóng (trước cọc, sau cọc, hoặc trong lúc bàn giao).

- **Actor / quyền:** bearer + `ACCOUNT_ACTIVE` + ownership (→ 404).
- **Contract chi tiết:** annotation tại `booking/controller/RentalOrderController.java`; Swagger UI —
  `http://localhost:8080/swagger-ui/index.html`.

## Luồng / hành vi

- Khoá đơn; đơn `Canceled`/`Done` → 409.
- Chạy [Cancellation Cascade](../../flows/cancellation-cascade.md) với lý do "Khách hủy đơn": proposal / hoá đơn
  `Unpaid` / lịch `Pending` huỷ, nhả khoang nếu đơn đang giữ, biên bản đang mở + hợp đồng chưa kích hoạt huỷ (qua
  event sang handover), khách + FM nhận `RENTAL_ORDER_CANCELED`.

## Ghi chú nghiệp vụ

- Spec 1.5 NOTES: huỷ sau khi đã cọc do khách chủ động thì mất cọc; huỷ do lỗi cơ sở thì hoàn thủ công.

## Liên quan

- Xem thêm: [routes.md](../../routes.md) · [index.md](../../index.md) · [cancellation-cascade.md](../../flows/cancellation-cascade.md)
