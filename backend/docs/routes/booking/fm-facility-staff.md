---
auth-model: role-bound
purpose: Danh sách FS đang hoạt động của một cơ sở để FM chọn khi phân công lịch hẹn, kèm ca / nghỉ phép / việc còn mở theo ngày.
---

# GET /api/fm/facilities/{facilityId}/staff?date=YYYY-MM-DD

FS (role `FS`, tài khoản `ACTIVE`) được gán vào cơ sở qua `account_facility_assignments`, xếp theo email.

- **Actor / quyền:** role-bound `FM` (`/api/fm/**`) + permission `appointment.assign` theo cơ sở (cùng quyền với
  thao tác phân công).
- **Contract chi tiết:** annotation tại `booking/controller/FmAppointmentController.java`; truy vấn ở
  `identity/application/FacilityStaffQuery.java`; trạng thái ngày ở `staffing/application/StaffAvailability.day`.

## Luồng / hành vi

- Trả `{id, name, email, phone, assignedAt}` — `name`/`phone` nullable với account cũ; FE fallback email/—.
  `assignedAt` là thời điểm gán FS vào cơ sở (`account_facility_assignments.assigned_at`).
- `date` tuỳ chọn (mặc định hôm nay, giờ VN). Mỗi FS thêm `date`, `shift` (ca đầu tiên tại cơ sở trong ngày
  `{startTime, endTime}` dạng `HH:mm`, `null` nếu không có ca), `shifts` (mọi ca tại cơ sở trong ngày), `onLeave`
  (nghỉ phép `Approved` phủ ngày), `openWork` `{checkins, returns}` (lịch `Pending` đã giao cho FS tại cơ sở trong
  ngày) và `openSupport` (yêu cầu hỗ trợ `Assigned` tại cơ sở, không gắn ngày) —
  [backoffice/hr.md › Tải việc](../../backoffice/hr.md#tải-việc-trên-màn-phân-công). Chỉ để hiển thị; luật phân công
  kiểm ở route giao việc.
- Tài khoản `BANNED` không xuất hiện. Ngoài phạm vi cơ sở → 403; cơ sở không tồn tại → 404 (theo `Access`).

## Ví dụ

```bash
TOKEN='<token của fm1@lemar.vn>'

curl -s http://localhost:8080/api/fm/facilities/1/staff -H "Authorization: Bearer $TOKEN"
# {"message":"Lấy danh sách nhân viên cơ sở thành công","data":[{"id":5,"name":"FS Quận 7","email":"fs1@lemar.vn","phone":null,"assignedAt":"2026-11-03T15:07:28.417133Z"}]}

# Sau V32 — ngày 14/11/2026, fs1 có ca mẫu
curl -s 'http://localhost:8080/api/fm/facilities/1/staff?date=2026-11-14' -H "Authorization: Bearer $TOKEN"
# {"message":"Lấy danh sách nhân viên cơ sở thành công","data":[{"id":5,"name":"FS Quận 7","email":"fs1@lemar.vn","phone":null,"assignedAt":"2026-11-09T15:22:06.388716Z","date":"2026-11-14","shift":{"startTime":"07:00","endTime":"21:00"},"shifts":[{"startTime":"07:00","endTime":"21:00"}],"onLeave":false,"openWork":{"checkins":0,"returns":0},"openSupport":0}]}

# 403 — fm1 hỏi FS của cơ sở TD
curl -s http://localhost:8080/api/fm/facilities/2/staff -H "Authorization: Bearer $TOKEN"
# {"message":"Bạn không được gán vào cơ sở này","timestamp":"2026-10-23T08:44:52.398897300Z"}
```

## Liên quan

- [fm-appointment-assign-staff.md](fm-appointment-assign-staff.md) · [backoffice/hr.md](../../backoffice/hr.md) ·
  [routes.md](../../routes.md)
