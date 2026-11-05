---
auth-model: role-bound
purpose: Lịch check-in đang Pending của một cơ sở trong một ngày cho màn "Lịch hẹn & phân công FS" của FM.
---

# GET /api/fm/appointments

Lịch check-in đang `Pending` (`type = CHECKIN`) của cơ sở trong ngày, xếp theo giờ bắt đầu, kèm đơn thuê, khách,
khoang và FS phụ trách.

- **Actor / quyền:** role-bound `FM` (`/api/fm/**`) + permission `appointment.read` theo cơ sở qua
  `Access.can(permission, facilityId)`.
- **Contract chi tiết:** annotation tại `booking/controller/FmAppointmentController.java`; Swagger UI —
  `http://localhost:8080/swagger-ui/index.html` (bấm **Authorize** và dán JWT).

## Luồng / hành vi

- Query bắt buộc: `facilityId`, `date` (ISO `yyyy-MM-dd`). Thiếu → 400.
- Chỉ trả lịch `type = CHECKIN` và `status = Pending`; lịch bàn giao / trả kho (`HANDOVER`, `RETURN`) và lịch
  `Done`/`Canceled` không thuộc màn này.
- Ngoài phạm vi cơ sở / thiếu quyền → 403; cơ sở không tồn tại → 404 (theo `Access`).
- Mỗi dòng: `id`, `type`, `status`, `date`, `startedAt`, `endAt`, `orderId`, `orderStatus`, `unitCode`,
  `customerName`, `customerEmail`, `staffId`, `staffEmail` (`staffId`/`staffEmail` null khi chưa phân
  công). `started_at`/`end_at` NOT NULL từ V15 — lịch luôn có giờ.

## Ghi chú nghiệp vụ

- Spec 1.5: FM xem các lịch check-in trong ngày để phân công FS; lịch đã phân công vẫn hiện với
  `orderStatus = InProgress`.
- Sai định dạng `date` hiện trả 500 do lỗi chung của API khi path/query sai kiểu, không riêng route này.

## Ví dụ

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"fm1@lemar.vn","password":"Test@1234"}' | jq -r .data.token)

# 200 — lịch check-in 08:00–10:00 (giờ VN) của đơn 1, chưa có FS
curl -s "http://localhost:8080/api/fm/appointments?facilityId=1&date=2026-11-06" -H "Authorization: Bearer $TOKEN"
# {"message":"Lấy danh sách lịch hẹn thành công","data":[{"id":1,"type":"CHECKIN","status":"Pending","date":"2026-11-06","startedAt":"2026-11-06T01:00:00Z","endAt":"2026-11-06T03:00:00Z","orderId":1,"orderStatus":"Scheduled","unitCode":"A-01","customerName":"Khách Demo","customerEmail":"customer1@lemar.vn","staffId":null,"staffEmail":null}]}

# 200 — ngày không có lịch
curl -s "http://localhost:8080/api/fm/appointments?facilityId=1&date=2026-10-28" -H "Authorization: Bearer $TOKEN"
# {"message":"Lấy danh sách lịch hẹn thành công","data":[]}

# 403 — fm2@lemar.vn (cơ sở TD) xem lịch Q7
# {"message":"Bạn không được gán vào cơ sở này","timestamp":"2026-10-23T08:44:51.855147100Z"}

# 400 — thiếu date
# {"message":"Dữ liệu không hợp lệ","timestamp":"2026-10-23T08:44:52.144239300Z"}
```

## Liên quan

- [fm-facility-staff.md](fm-facility-staff.md) · [fm-appointment-assign-staff.md](fm-appointment-assign-staff.md) ·
  [routes.md](../../routes.md)
