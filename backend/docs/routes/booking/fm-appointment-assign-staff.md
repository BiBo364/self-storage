---
auth-model: role-bound
purpose: FM phân công FS phụ trách lịch check-in — đơn chuyển InProgress, FS nhận thông báo.
---

# POST /api/fm/appointments/{appointmentId}/staff

Body `{ "staffId": 5 }`.

- **Actor / quyền:** role-bound `FM` (`/api/fm/**`) + permission `appointment.assign` theo cơ sở của lịch hẹn.
- **Contract chi tiết:** annotation tại `booking/controller/FmAppointmentController.java`; logic ở
  `booking/application/AppointmentService.java`.

## Luồng / hành vi

1. Lịch không tồn tại → 404. Kiểm quyền theo cơ sở của lịch → 403 nếu ngoài phạm vi.
2. Đọc link qua `rental_appointments`; không có đơn → 409. Khoá đơn thuê; đơn không ở `Scheduled` → 409.
3. Khoá row lịch hẹn (sau đơn — cùng thứ tự với cancellation cascade). Lịch không phải `CHECKIN` / không
   còn `Pending` / đã có FS → 409, không đổi gì.
4. `staffId` không phải FS đang hoạt động của cùng cơ sở → 400. Thiếu `staffId` → 400.
5. OK → một transaction: `appointments.staff_id = staffId`, đơn `Scheduled → InProgress`, hai AuditLog
   `FS_ASSIGNED` (actor = FM): một cho `Appointment` (`old_value = null`, `new_value = {staffId, orderId}`)
   và một cho `RentalOrder` (`old_value = {status: "Scheduled"}`, `new_value = {status: "InProgress"}`).
   FS nhận notification + email `FS_ASSIGNED` (mã đơn, giờ hẹn theo giờ VN, địa chỉ cơ sở, mã khoang).

## Ghi chú nghiệp vụ

- Spec 1.5: FM chỉ định một FS phụ trách ca tiếp đón; gán xong đơn sang `InProgress`.
- `started_at`/`end_at` NOT NULL từ V15 — lịch phải có giờ trước khi assign; DB chặn lịch thiếu giờ.
- Route này chỉ phân công lần đầu: lịch đã có FS → 409 (kể cả khi hai FM bấm cùng lúc — chỉ một lần thắng). Đổi FS
  đã gán dùng [`PUT`](#put--đổi-fs).

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

# 409 — gọi lại khi lịch đã có FS (đúng FS đó, hoặc đổi sang FS khác như staffId 9 — cùng một câu)
curl -s -X POST http://localhost:8080/api/fm/appointments/1/staff -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"staffId":5}'
# {"message":"Lịch hẹn đã có FS phụ trách","timestamp":"2026-11-03T09:02:55.556912500Z"}

# 403 — fm2@lemar.vn (cơ sở TD)
# {"message":"Bạn không được gán vào cơ sở này","timestamp":"2026-10-23T08:44:52.890730500Z"}

# 404 — lịch không tồn tại
# {"message":"Không tìm thấy lịch hẹn","timestamp":"2026-10-23T08:44:53.748581300Z"}
```

Sau lần gọi 200: `audit_logs` có 2 bản ghi `FS_ASSIGNED` (actor = FM):

- `Appointment | 1 | old_value null | new_value {"orderId": 1, "staffId": 5}`
- `RentalOrder | 1 | old_value {"status": "Scheduled"} | new_value {"status": "InProgress"}`

fs1 nhận "Bạn được phân công lịch check-in — Đơn 1: lịch hẹn 08:00–10:00 27/10/2026 tại 123 Nguyễn Văn Linh,
Quận 7, TP.HCM, khoang A-01."

## PUT — đổi FS

`PUT /api/fm/appointments/{appointmentId}/staff` body `{ "staffId": 9 }` — cùng actor / quyền như `POST`. Dùng khi FS
cũ nghỉ hoặc cần chuyển cơ sở: BOM / ADMIN không gỡ được FS khỏi cơ sở khi FS còn lịch check-in `Pending` ở đó
([backoffice/overview.md › Gán FS vào cơ sở](../../backoffice/overview.md#gán-fs-vào-cơ-sở)).

1. Lịch không tồn tại → 404; ngoài phạm vi cơ sở → 403. Khoá đơn rồi khoá lịch (cùng thứ tự với `POST`).
2. Lịch không phải `CHECKIN` / không còn `Pending` (khách đã đến, đã huỷ) → 409. Lịch chưa có FS → 409 "Lịch hẹn
   chưa có FS — dùng phân công lần đầu". `staffId` trùng FS hiện tại → 409 "FS này đang phụ trách lịch hẹn".
3. `staffId` không phải FS đang hoạt động của cùng cơ sở → 400. Đơn không ở `InProgress` → 409.
4. OK → `appointments.staff_id = staffId`; đơn giữ `InProgress`. Một AuditLog `FS_ASSIGNED` cho `Appointment`
   (`old_value = {staffId: <FS cũ>}`, `new_value = {staffId, orderId}`); không ghi cho `RentalOrder` vì đơn không đổi.
   FS mới nhận notification + email `FS_ASSIGNED`; FS cũ không nhận thông báo.
5. Phiên bàn giao của đơn đọc FS qua `appointments.staff_id`, nên đi theo FS mới.

Hai FM cùng đổi FS một lịch: khoá đơn tuần tự hoá, lần sau ghi đè lần trước (mỗi lần một audit).

```bash
TOKEN='<token của fm1@lemar.vn>'

# 200 — lịch #1 đang do fs1 (id 5) phụ trách, đổi sang fs2 (id 9)
curl -s -X PUT http://localhost:8080/api/fm/appointments/1/staff -H "Authorization: Bearer $TOKEN"   -H 'Content-Type: application/json' -d '{"staffId":9}'
# {"message":"Đã đổi nhân viên phụ trách lịch hẹn","data":{"id":1,"type":"CHECKIN","status":"Pending","date":"2026-11-12","startedAt":"2026-11-12T01:00:00Z","endAt":"2026-11-12T03:00:00Z","orderId":1,"orderStatus":"InProgress","unitCode":"A-01","customerName":"Khách Demo","customerEmail":"customer1@lemar.vn","staffId":9,"staffEmail":"fs2@lemar.vn"}}

# 409 — đổi sang chính FS đang phụ trách
# {"message":"FS này đang phụ trách lịch hẹn","timestamp":"2026-11-08T18:44:03.723557300Z"}

# 400 — fs1 đã được BOM chuyển sang cơ sở TD, không còn là FS của Q7
# {"message":"Nhân viên không phải FS của cơ sở này","timestamp":"2026-11-08T18:44:03.995379800Z"}
```

Sau lần gọi 200, `audit_logs` thêm `FS_ASSIGNED | Appointment | 1 | old_value {"staffId": 5} | new_value
{"orderId": 1, "staffId": 9}` (actor = FM); fs2 nhận notification `FS_ASSIGNED`.

## Liên quan

- [fm-appointment-day-list.md](fm-appointment-day-list.md) · [fm-facility-staff.md](fm-facility-staff.md) ·
  [routes.md](../../routes.md)
