---
auth-model: role-bound
purpose: Lịch làm việc của FS trong ngày — các lịch hẹn được FM phân công cho chính FS đó.
---

# GET /api/staff/appointments

Lịch hẹn đã gán cho FS đang đăng nhập trong một ngày, kèm khách, khoang, cơ sở và trạng thái.

- **Actor / quyền:** role-bound `FS` (`/api/staff/**`) + permission `appointment.read` theo cơ sở; chỉ
  trả lịch có `staff_id` = FS hiện tại.
- **Contract chi tiết:** annotation tại `booking/controller/StaffAppointmentController.java`; Swagger UI —
  `http://localhost:8080/swagger-ui/index.html` (bấm **Authorize** và dán JWT).

## Luồng / hành vi

- Query `date` (ISO `yyyy-MM-dd`) optional; thiếu → hôm nay theo `Asia/Ho_Chi_Minh`.
- Chỉ trả lịch của FS hiện tại; FS khác / cơ sở ngoài phạm vi không thấy. Không lộ lịch của FS khác.
- Mỗi dòng: `id`, `type`, `status`, `date`, `startedAt`, `endAt`, `arrivedAt` (null khi chưa đến),
  `orderId`, `unitCode`, `facility {id, name}`, `customer {name, email, phone}`, `noShowDue` (check-in
  `Pending` đã quá `end_at` — cron sẽ huỷ; FE ẩn action), `cancelReason` (lý do huỷ; `null` khi chưa huỷ;
  cron no-show ghi "Khách không đến"; FE hiển thị raw).
- Action: [arrive](staff-appointment-arrive.md) (`Pending → Done`) và [cancel](staff-appointment-cancel.md) (FS chủ động, chưa quá `end_at`).
- Sai định dạng `date` hiện trả 500 do lỗi chung của API khi query sai kiểu, không riêng route này.

## Ví dụ

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"fs1@lemar.vn","password":"Test@1234"}' | jq -r .data.token)

curl -s "http://localhost:8080/api/staff/appointments?date=2026-11-03" -H "Authorization: Bearer $TOKEN"
# {"message":"Lấy lịch làm việc thành công","data":[{"id":1,"type":"CHECKIN","status":"Pending","date":"2026-11-03","startedAt":"2026-11-03T01:00:00Z","endAt":"2026-11-03T03:00:00Z","arrivedAt":null,"orderId":1,"unitCode":"A-01","facility":{"id":1,"name":"Self Storage Quận 7"},"customer":{"name":"Khách Demo","email":"customer1@lemar.vn","phone":"0900000000"},"noShowDue":false,"cancelReason":null}]}
```

## Liên quan

- [staff-appointment-arrive.md](staff-appointment-arrive.md) · [staff-appointment-cancel.md](staff-appointment-cancel.md) · [appointment-no-show.md](../../booking/appointment-no-show.md) · [routes.md](../../routes.md)
