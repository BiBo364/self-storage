---
purpose: Cron no-show cho lịch check-in quá giờ chưa đến — guard, audit, notification.
---

# Khách không đến (no-show)

- `AppointmentNoShowJob` chạy mỗi **5 phút** (ShedLock qua Redis); quét `Appointment` type `CHECKIN`,
  `status = Pending`, `end_at < now`.
- Guard dùng chung `Appointment.isNoShowDue(now)`: cron, read (`noShowDue`), action FS dùng lại; không
  rải điều kiện riêng.
- Mỗi lịch: `status = Canceled`, `cancel_reason = NoShow`; audit `APPOINTMENT_CANCELED_NO_SHOW`
  (actor System); notification khách `APPOINTMENT_CANCELED_NO_SHOW` (`appointmentTime` +
  `rescheduleLink = FRONTEND_URL/appointments/new`). Idempotent: chỉ quét `Pending`.
- **Không đụng `HandoverRecord`/`RentalOrder`**: record bàn giao thuộc Flow 2; huỷ đơn/nhả khoang/hoàn
  cọc thuộc luồng huỷ đơn/hoàn tiền. Hai phần đó có issue riêng.
- Khách đến muộn phải trong khung giờ: `arrive` sau `end_at` → 409; `cancel` sau `end_at` → 409. Nếu
  cron chưa chạy, guard vẫn phản ánh qua `noShowDue`/409 — không đợi cron.
- Read trả `noShowDue` (không side effect) và `cancelReason = NoShow`; FE ẩn 2 nút trên row đó và
  hiển thị "Lý do huỷ: Khách không đến".
