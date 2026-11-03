---
auth-model: role-bound
purpose: Danh sách FS đang hoạt động của một cơ sở để FM chọn khi phân công lịch hẹn.
---

# GET /api/fm/facilities/{facilityId}/staff

FS (role `FS`, tài khoản `ACTIVE`) được gán vào cơ sở qua `account_facility_assignments`, xếp theo email.

- **Actor / quyền:** role-bound `FM` (`/api/fm/**`) + permission `appointment.assign` theo cơ sở (cùng quyền với
  thao tác phân công).
- **Contract chi tiết:** annotation tại `booking/controller/FmAppointmentController.java`; truy vấn ở
  `identity/application/FacilityStaffQuery.java`.

## Luồng / hành vi

- Trả `{id, name, email, phone, assignedAt}` — `name`/`phone` nullable với account cũ; FE fallback email/—.
  `assignedAt` là thời điểm gán FS vào cơ sở (`account_facility_assignments.assigned_at`).
- Tài khoản `BANNED` không xuất hiện. Ngoài phạm vi cơ sở → 403; cơ sở không tồn tại → 404 (theo `Access`).

## Ví dụ

```bash
TOKEN='<token của fm1@lemar.vn>'

curl -s http://localhost:8080/api/fm/facilities/1/staff -H "Authorization: Bearer $TOKEN"
# {"message":"Lấy danh sách nhân viên cơ sở thành công","data":[{"id":5,"name":"FS Quận 7","email":"fs1@lemar.vn","phone":null,"assignedAt":"2026-11-03T15:07:28.417133Z"}]}

# 403 — fm1 hỏi FS của cơ sở TD
curl -s http://localhost:8080/api/fm/facilities/2/staff -H "Authorization: Bearer $TOKEN"
# {"message":"Bạn không được gán vào cơ sở này","timestamp":"2026-10-23T08:44:52.398897300Z"}
```

## Liên quan

- [fm-appointment-assign-staff.md](fm-appointment-assign-staff.md) · [routes.md](../../routes.md)
