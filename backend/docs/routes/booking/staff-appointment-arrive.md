---
auth-model: role-bound
purpose: FS xác nhận khách đã đến cho lịch được phân công — lịch Pending chuyển Done.
---

# POST /api/staff/appointments/{appointmentId}/arrive

FS xác nhận khách đã đến; không body.

- **Actor / quyền:** role-bound `FS` (`/api/staff/**`) + permission `appointment.checkin` theo cơ sở;
  chỉ lịch có `staff_id` = FS đang đăng nhập.
- **Contract chi tiết:** annotation tại `booking/controller/StaffAppointmentController.java`.

## Luồng / hành vi

1. Khoá lịch; lịch phải `Pending`, do chính FS phụ trách và **chưa quá `end_at`** → 403/409 nếu sai
   (khách đến muộn phải trong khung giờ; quá giờ là no-show).
2. `status = Done`, `arrived_at = now`; audit `APPOINTMENT_ARRIVED` (actor FS); phát event
   `AppointmentArrived`.
3. Handover (PR Flow 2) nghe event để set `HandoverRecord.due_at`; route này không biết `due_days`.

## Ví dụ

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"fs1@lemar.vn","password":"Test@1234"}' | jq -r .data.token)

curl -s -X POST http://localhost:8080/api/staff/appointments/1/arrive -H "Authorization: Bearer $TOKEN"
# {"message":"Đã xác nhận khách đến","data":{"id":1,"type":"CHECKIN","status":"Done","date":"2026-11-05","startedAt":"2026-11-05T01:00:00Z","endAt":"2026-11-05T03:00:00Z","arrivedAt":"2026-11-04T03:52:11.280500Z","orderId":1,"unitCode":"A-01","facility":{"id":1,"name":"Self Storage Quận 7"},"customer":{"name":"Khách Demo","email":"customer1@lemar.vn","phone":"0900000000"},"noShowDue":false,"cancelReason":null}}

# 409 — quá end_at
# {"message":"Lịch đã quá khung giờ, không thể xác nhận khách đến","timestamp":"2026-11-04T03:52:11.390999337Z"}
```

## Liên quan

- [staff-appointment-day-list.md](staff-appointment-day-list.md) · [staff-appointment-cancel.md](staff-appointment-cancel.md) ·
  [routes.md](../../routes.md)
