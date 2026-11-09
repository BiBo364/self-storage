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

1. Khoá đơn của lịch rồi khoá lịch; lịch phải `Pending` và do chính FS phụ trách → 403/409 nếu sai.
2. Lịch `CHECKIN`:
   - chỉ cho xác nhận sớm tối đa **15 phút** trước `started_at`; sớm hơn → 409
     `"Chưa tới giờ hẹn. Chỉ có thể xác nhận khách đến sớm tối đa 15 phút."`;
   - quá `end_at` → hệ thống huỷ no-show **ngay** (audit `APPOINTMENT_CANCELED_NO_SHOW`, notification khách) rồi trả
     409; không đợi cron;
   - đơn phải đủ điều kiện vào bàn giao (`requireCheckinReady`), thiếu điều kiện nào → 409, lịch giữ `Pending`:

     | Điều kiện | 409 message |
     |---|---|
     | đơn giữ khoang (`holdsUnit`: `Deposited`/`Scheduled`/`InProgress`) | "Đơn chưa đặt cọc hoặc đã đóng — chưa thể check-in" |
     | không `needs_reproposal` | "Đơn đang chờ FM đề xuất lại khoang — chưa thể check-in" |
     | không còn hoá đơn `Deposit`/`DepositSupplement` `Unpaid` | "Khách còn hoá đơn cọc chưa thanh toán — chưa thể check-in" |
     | khoang của đơn đang `Reserved` | "Khoang của đơn không ở trạng thái giữ chỗ — liên hệ FM" |
3. Lịch `RETURN` (trả kho): không áp luật đến sớm 15 phút; route không huỷ no-show — khách đến trễ sau `end_at` vẫn
   xác nhận đến được chừng nào lịch còn `Pending`; quá `end_at + return.no_show_grace_minutes` thì cron no-show trả kho
   huỷ lịch ([tenancy/rentals.md](../../tenancy/rentals.md#khách-không-đến-buổi-trả-kho)) và route trả 409 như lịch đã
   đóng. Không kiểm điều kiện đơn ở trên.
4. `status = Done`, `arrived_at = now`; audit `APPOINTMENT_ARRIVED` (actor FS); phát event
   `AppointmentArrived`.
5. Module handover nghe event để set `HandoverRecord.due_at` (lịch `CHECKIN`); route này không biết `due_days`. Với
   lịch `RETURN`, mốc khách đến là điều kiện để FS ghi biên bản trả kho và là mốc dừng tính phí quá hạn
   ([tenancy/rentals.md](../../tenancy/rentals.md#quá-hạn-flow-6)).

## Ví dụ

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"fs1@lemar.vn","password":"Test@1234"}' | jq -r .data.token)

curl -s -X POST http://localhost:8080/api/staff/appointments/1/arrive -H "Authorization: Bearer $TOKEN"
# {"message":"Đã xác nhận khách đến","data":{"id":1,"type":"CHECKIN","status":"Done","date":"2026-11-05","startedAt":"2026-11-05T01:00:00Z","endAt":"2026-11-05T03:00:00Z","arrivedAt":"2026-11-04T03:52:11.280500Z","orderId":1,"unitCode":"A-01","facility":{"id":1,"name":"Self Storage Quận 7"},"customer":{"name":"Khách Demo","email":"customer1@lemar.vn","phone":"0900000000"},"noShowDue":false,"cancelReason":null}}

# 409 — quá end_at
# {"message":"Lịch đã quá khung giờ, không thể xác nhận khách đến","timestamp":"2026-11-04T03:52:11.390999337Z"}

# 409 — sớm hơn 15 phút trước started_at
# {"message":"Chưa tới giờ hẹn. Chỉ có thể xác nhận khách đến sớm tối đa 15 phút.","timestamp":"..."}
```

## Liên quan

- [staff-appointment-day-list.md](staff-appointment-day-list.md) · [staff-appointment-cancel.md](staff-appointment-cancel.md) ·
  [routes.md](../../routes.md)
