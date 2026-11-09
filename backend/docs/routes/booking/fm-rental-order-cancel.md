---
auth-model: permission-bound
purpose: FM huỷ đơn thuê — Cancellation Cascade.
---
# POST /api/fm/rental-orders/{orderId}/cancel

Spec 1.3 "Khách không phản hồi proposal" → FM chọn Hủy đơn; hoặc FM huỷ đơn theo yêu cầu khách.

- **Actor / quyền:** role `FM` + `rental_order.cancel` theo cơ sở của đơn (cơ sở khác → 403).
- **Contract chi tiết:** annotation tại `booking/controller/FmRentalOrderActionController.java`; Swagger UI —
  `http://localhost:8080/swagger-ui/index.html`.

## Luồng / hành vi

- Body tuỳ chọn `{reason}` (≤ 500 ký tự); bỏ trống = "Khách không còn nhu cầu".
- Đơn đã đóng → 409. Còn lại chạy [Cancellation Cascade](../../flows/cancellation-cascade.md), actor = FM.

## Ghi chú nghiệp vụ

- Spec 1.3 › Khách không phản hồi proposal.

## Liên quan

- Xem thêm: [routes.md](../../routes.md) · [index.md](../../index.md) · [cancellation-cascade.md](../../flows/cancellation-cascade.md)
