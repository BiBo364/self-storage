---
purpose: Cron no-show cho lịch check-in quá giờ chưa đến — guard, audit, notification.
---

# Quá giờ và khách không đến (no-show)

- `AppointmentNoShowJob` chạy mỗi **5 phút** (lần đầu sau 60 giây); quét `Appointment` type `CHECKIN`,
  `status = Pending`, `end_at < now`. Job chạy trên một instance; không dùng lock phân tán.
- Action `arrive`/`cancel` khi phát hiện quá giờ cũng gọi **cùng logic huỷ no-show ngay**, rồi trả 409;
  cron chỉ là backstop cho trường hợp FS không mở app.
- `started_at`/`end_at` NOT NULL từ V15; notification dùng trực tiếp hai mốc này.
- Guard dùng chung `Appointment.isNoShowDue(now)`: cron, read (`noShowDue`), action FS dùng lại; không
  rải điều kiện riêng.
- Mỗi lịch: `status = Canceled`, `cancel_reason = "Quá giờ và khách không đến"`; audit `APPOINTMENT_CANCELED_NO_SHOW`
  (actor System). Lý do là chuỗi tiếng Việt này (không dùng mã `NoShow`) — quyết định của chủ dự án.
  Idempotent: chỉ quét `Pending`.
- Cùng transaction, sau khi huỷ lịch (`AppointmentNoShowService.cancelIfDue`):
  - phát `CheckinAppointmentClosed` → handover đóng `HandoverRecord` của lịch (lý do "Khách không đến nhận kho theo
    lịch hẹn.");
  - lịch có đơn thì gọi `HandoverOrderService.release`: đơn `Scheduled/InProgress` còn hạn giữ kho → `Deposited`
    (khoang vẫn `Reserved`); đơn đã quá `expires_at` → Cancellation Cascade lý do "Không đến nhận kho quá hạn giữ";
    đơn đã đóng → không đổi.
- Notification khách `APPOINTMENT_CANCELED_NO_SHOW` (`appointmentTime` + `rescheduleLink =
  FRONTEND_URL/appointments/new`, nối order nếu lịch có đơn) chỉ gửi khi đơn **không** bị Cascade; Cascade đã gửi
  `RENTAL_ORDER_CANCELED` nên không gửi thêm.
- Khách đến muộn phải trong khung giờ: `arrive` sau `end_at` → 409; `cancel` sau `end_at` → 409. Nếu
  cron chưa chạy, guard vẫn phản ánh qua `noShowDue`/409 — không đợi cron.
- **Chỉ lịch `CHECKIN` có no-show** (`AppointmentNoShowPolicy.isDue` kiểm type): lịch `RETURN` quá `end_at` không bị
  huỷ, `noShowDue = false`, FS vẫn xác nhận khách đến trễ được. FS cũng không huỷ được lịch `RETURN` qua route huỷ
  (409).
- Read trả `noShowDue` (không side effect) và `cancelReason = "Quá giờ và khách không đến"`; FE ẩn 2 nút trên row
  đó và hiển thị raw. Notification dùng `appointmentTime` dạng `HH:mm–HH:mm dd/MM/yyyy` (giờ cơ sở) và
  `rescheduleLink`.
