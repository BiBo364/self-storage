---
auth-model: role-bound
purpose: FM phân công hoặc đổi FS phụ trách lịch check-in — lần đầu đơn chuyển InProgress, FS nhận thông báo.
---

# POST /api/fm/appointments/{appointmentId}/staff

Body `{ "staffId": 5 }`.

- **Actor / quyền:** role-bound `FM` (`/api/fm/**`) + permission `appointment.assign` theo cơ sở của lịch hẹn.
- **Contract chi tiết:** annotation tại `booking/controller/FmAppointmentController.java`; logic ở
  `booking/application/AppointmentService.java`.

## Luồng / hành vi

1. Lịch không tồn tại → 404. Kiểm quyền theo cơ sở của lịch → 403 nếu ngoài phạm vi.
2. Khoá row lịch hẹn. Lịch không phải `CHECKIN` / không còn `Pending` → 409, không đổi gì. Lịch đã do chính
   `staffId` này phụ trách → 409.
3. `staffId` không phải FS đang hoạt động của cùng cơ sở → 400. Thiếu `staffId` → 400.
4. Khoá đơn thuê nối qua `rental_appointments`. Lần gán đầu: đơn phải `Scheduled`; lần đổi FS (lịch đã có FS):
   đơn phải `InProgress`. Sai trạng thái → 409.
5. Gán lần đầu → một transaction: `appointments.staff_id = staffId`, đơn `Scheduled → InProgress`, AuditLog
   `FS_ASSIGNED` (entity `Appointment`, actor = FM, giá trị mới gồm `staffId`, `orderId`, `orderStatus`),
   FS nhận notification + email `FS_ASSIGNED` (mã đơn, giờ hẹn theo giờ VN, địa chỉ cơ sở, mã khoang).
6. Đổi FS → `appointments.staff_id` đổi sang FS mới, đơn giữ `InProgress`, một AuditLog `FS_ASSIGNED` với
   `old_value = {staffId: cũ}` và `new_value = {staffId: mới, orderId}`; FS mới nhận `FS_ASSIGNED`, FS cũ nhận
   `FS_REASSIGNED`.

## Ghi chú nghiệp vụ

- Spec 1.5: FM chỉ định một FS phụ trách ca tiếp đón; gán xong đơn sang `InProgress`.
- Đổi FS chỉ cho phép khi lịch còn `Pending`; không có endpoint riêng, dùng lại route này.

## Ví dụ

```bash
TOKEN='<token của fm1@lemar.vn>'

# 400 — staffId là tài khoản FM, không phải FS của cơ sở
curl -s -X POST http://localhost:8080/api/fm/appointments/1/staff -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"staffId":3}'
# {"message":"Nhân viên không phải FS của cơ sở này","timestamp":"2026-10-23T08:44:52.674866800Z"}

# 200 — gán fs1
curl -s -X POST http://localhost:8080/api/fm/appointments/1/staff -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"staffId":5}'
# {"message":"Đã phân công nhân viên phụ trách lịch hẹn","data":{"id":1,"type":"CHECKIN","status":"Pending","date":"2026-10-27","startedAt":"2026-10-27T01:00:00Z","endAt":"2026-10-27T03:00:00Z","orderId":1,"orderStatus":"InProgress","unitCode":"A-01","customerEmail":"customer1@lemar.vn","staffId":5,"staffEmail":"fs1@lemar.vn"}}

# 409 — gọi lại với đúng FS đó
curl -s -X POST http://localhost:8080/api/fm/appointments/1/staff -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"staffId":5}'
# {"message":"Lịch hẹn đã do FS này phụ trách","timestamp":"2026-11-03T04:45:25.755838347Z"}

# 403 — fm2@lemar.vn (cơ sở TD)
# {"message":"Bạn không được gán vào cơ sở này","timestamp":"2026-10-23T08:44:52.890730500Z"}

# 404 — lịch không tồn tại
# {"message":"Không tìm thấy lịch hẹn","timestamp":"2026-10-23T08:44:53.748581300Z"}
```

Sau lần gọi 200: `audit_logs` có `FS_ASSIGNED | Appointment | 1 | actor 3 | {"orderId": 1, "staffId": 5,
"orderStatus": "InProgress"}`; fs1 nhận "Bạn được phân công lịch check-in — Đơn 1: lịch hẹn 08:00–10:00 27/10/2026
tại 123 Nguyễn Văn Linh, Quận 7, TP.HCM, khoang A-01."

## Liên quan

- [fm-appointment-day-list.md](fm-appointment-day-list.md) · [fm-facility-staff.md](fm-facility-staff.md) ·
  [routes.md](../../routes.md)
