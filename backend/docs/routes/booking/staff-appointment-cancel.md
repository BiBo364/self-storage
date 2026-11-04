---
auth-model: role-bound
purpose: FS chủ động huỷ lịch đang chờ với lý do; quá giờ để cron no-show.
---

# POST /api/staff/appointments/{appointmentId}/cancel

Body `{ "reason": "Khách yêu cầu huỷ" }`.

- **Actor / quyền:** role-bound `FS` (`/api/staff/**`) + permission `appointment.checkin` theo cơ sở;
  chỉ lịch có `staff_id` = FS đang đăng nhập.
- **Contract chi tiết:** annotation tại `booking/controller/StaffAppointmentController.java`.

## Luồng / hành vi

1. Khoá lịch; lịch `Pending`, đúng FS, **chưa quá `end_at`** → 409 nếu sai/đã quá giờ.
2. `status = Canceled`, `cancel_reason = reason`; audit `APPOINTMENT_CANCELED` (actor FS, `reason`).
3. Không đụng `RentalOrder`/`HandoverRecord`; không gửi notification khách trong route này.
4. Quá `end_at` → 409 "Lịch đã quá giờ"; cron `APPOINTMENT_CANCELED_NO_SHOW` xử lý sau.

## Ví dụ

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"fs1@lemar.vn","password":"Test@1234"}' | jq -r .data.token)

curl -s -X POST http://localhost:8080/api/staff/appointments/2/cancel -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"reason":"Khách yêu cầu huỷ"}'
# {"message":"Đã huỷ lịch hẹn","data":{"id":2,"type":"CHECKIN","status":"Canceled","date":"2026-11-05","startedAt":"2026-11-05T06:00:00Z","endAt":"2026-11-05T08:00:00Z","arrivedAt":null,"orderId":1,"unitCode":"A-01","facility":{"id":1,"name":"Self Storage Quận 7"},"customer":{"name":"Khách Demo","email":"customer1@lemar.vn","phone":"0900000000"},"noShowDue":false}}

# 409 — quá end_at
# {"message":"Lịch đã quá giờ","timestamp":"2026-11-04T03:52:11.390999337Z"}
```

## Liên quan

- [staff-appointment-day-list.md](staff-appointment-day-list.md) · [staff-appointment-arrive.md](staff-appointment-arrive.md) ·
  [routes.md](../../routes.md)
