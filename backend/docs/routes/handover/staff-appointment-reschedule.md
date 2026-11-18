---
auth-model: role-bound
purpose: FS dời lịch nhận kho (check-in) mình phụ trách sang khung khác, trước bước thanh toán tháng đầu.
---

# GET /api/staff/appointments/{appointmentId}/reschedule-slots · POST /api/staff/appointments/{appointmentId}/reschedule

Body POST `{ "date": "2026-11-20", "startAt": "2026-11-20T01:00:00Z", "reason": "Khách quên CCCD, hẹn quay lại" }`.

- **Actor / quyền:** role-bound `FS` (`/api/staff/**`) + permission `appointment.checkin` theo cơ sở; chỉ lịch có
  `staff_id` = FS đang đăng nhập (khác → 403). Chỉ lịch `CHECKIN` (khác → 409).
- **Code:** `handover/controller/StaffRescheduleController.java` → `handover/application/HandoverRescheduleService.java`
  (điều phối, đóng phiên cũ) → `booking/application/CheckinRescheduleService.java` (lịch hẹn). Booking không gọi sang
  handover — handover gọi booking trong cùng transaction.

## Khung giờ (`GET …/reschedule-slots`)

Trả `SlotWindow` như khách đặt lịch: từ hôm nay tới ngày của `RentalOrder.expires_at`, 3 khung cố định mỗi ngày.
`available = false` khi khung đã qua, kết thúc sau `expires_at`, hoặc cơ sở đã có lịch `CHECKIN`/`RETURN` còn hiệu lực
cùng giờ bắt đầu (kể cả khung hiện tại của lịch đang dời).

## Dời lịch (`POST …/reschedule`)

Được dời khi:

| Lúc | Điều kiện | Sai → |
|---|---|---|
| Khách chưa đến | lịch `Pending`, chưa quá `end_at` | quá giờ → 409 "Lịch đã quá giờ…" (để no-show xử lý) |
| Khách đã đến | lịch `Done`, phiên bàn giao còn mở, hoá đơn tháng đầu **chưa** `Paid` | đã trả → 409 "Khách đã thanh toán tháng đầu…"; đang có phiên cổng mở (giao dịch `Pending` trong cửa sổ 15') → 409 "Khách đang thanh toán…" |

Chung: đơn `InProgress` (409), `reason` bắt buộc ≤ 500 ký tự (400), đơn chưa có lịch `Pending` khác (409).

Khung mới — cùng luật "đặt lại" của [Đặt lịch check-in](../../booking/appointment-booking.md): ngày trong
`[hôm nay, ngày của expires_at]` (400), `startAt` đúng giờ bắt đầu một khung (400), khung chưa qua (400), khác khung hiện
tại (400), kết thúc trước `expires_at` (409), cơ sở chưa có lịch ở khung đó (409 "Khung giờ đã có người đặt…"). FS phải
còn phụ trách được khung mới theo cùng luật FM phân công: không nghỉ phép ngày đó, có ca tại cơ sở bao trọn khung khi
`staff.shift_required` bật (409, kèm gợi ý chọn khung khác hoặc nhờ FM phân công FS khác).

Một transaction, khoá đơn → hợp đồng → lịch → cơ sở → FS → biên bản:

1. Lịch cũ: khách chưa đến → `Canceled`, `cancel_reason = "Dời lịch: {lý do}"`; khách đã đến → giữ `Done` (buổi đó
   đã diễn ra). Không phát `CheckinAppointmentClosed`, không trả đơn về `Deposited` — đơn giữ `InProgress`.
2. Lịch mới `CHECKIN` `Pending` ở khung mới, `staff_id` = FS đang dời, nối `rental_appointments`; phát
   `CheckinAppointmentCreated` → handover mở biên bản mới `IN_PROGRESS` (`BEFORE_COMMIT`).
3. Biên bản cũ → `CANCELED`, `reject_reason = "Dời lịch: {lý do}"` — dữ liệu (ảnh giấy tờ, hiện trạng) giữ nguyên làm
   lịch sử; hợp đồng `Draft`/`Signed` → `Canceled`; hoá đơn tháng đầu `Unpaid` → `Canceled` (cùng luật
   [Huỷ phiên bàn giao](../../handover/records.md#huỷ-phiên-bàn-giao-handoverservice)). Biên bản cũ được flush trước
   commit để biên bản mới insert được (partial unique index: một biên bản mở / đơn).
4. Audit `APPOINTMENT_RESCHEDULED` trên lịch cũ (old: `status` + `startedAt`; new: `status`, `newAppointmentId`,
   `newStartedAt`; `reason`), `APPOINTMENT_CREATED` trên lịch mới (`rescheduledFrom`), `HANDOVER_RECORD_CREATED`,
   `HANDOVER_CANCELED`, `CONTRACT_CANCELED` / `INVOICE_CANCELED` nếu có; actor FS (phần lịch) / System (phần phiên).
5. Notification `APPOINTMENT_RESCHEDULED` (in-app + email) cho khách và FM của cơ sở: `orderCode`,
   `oldAppointmentTime`, `appointmentTime`, `facilityAddress`, `reason`.

Phiên mới làm lại từ đầu: khách đến → xác minh danh tính → kiểm tra khoang → khách đồng ý → hợp đồng `Draft` **mới**
(mốc tính tiền theo ngày sinh hợp đồng, tức ngày của buổi mới). `HandoverView.previousSession` của phiên mới trả phiên
cũ đóng vì dời lịch (lý do, giờ hẹn cũ, kết quả xác minh + ảnh giấy tờ, hiện trạng + ảnh) để FS xem lại — chỉ đọc, không
chép sang phiên mới.

Trả `Rescheduled`: `oldAppointmentId`, `newAppointmentId`, `orderId`, `arrived`, `date`, `startAt`, `endAt`, `oldTime`,
`newTime` (`HH:mm–HH:mm dd/MM/yyyy`, giờ VN).

## Ví dụ

```bash
curl -s -X POST http://localhost:8080/api/staff/appointments/7/reschedule -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"date":"2026-11-20","startAt":"2026-11-20T01:00:00Z","reason":"Khách quên CCCD, hẹn quay lại"}'
# {"message":"Đã dời lịch nhận kho","data":{"oldAppointmentId":7,"newAppointmentId":12,"orderId":6,"arrived":true,
#  "date":"2026-11-20","startAt":"2026-11-20T01:00:00Z","endAt":"2026-11-20T03:00:00Z",
#  "oldTime":"13:00–15:00 18/11/2026","newTime":"08:00–10:00 20/11/2026"}}

# 409 — đã trả tiền tháng đầu
# {"message":"Khách đã thanh toán tháng đầu — hoàn tất bàn giao, không dời lịch được nữa", ...}
```

## Test

- `handover/HandoverFlowIntegrationTest.java` (Postgres): dời trước khi khách đến; dời khi hợp đồng đã ký chưa trả
  (hợp đồng + hoá đơn huỷ, phiên mới có `previousSession`, hợp đồng nháp mới); chặn khi thiếu lý do / ngoài hạn giữ /
  khung sai / khung đã có lịch / FS nghỉ phép / FS khác / lịch quá giờ; chặn khi đang thanh toán và khi đã trả.
- `handover/controller/HandoverControllerTest.java`: HTTP contract + map lỗi 400 / 409.
