package vn.lemar.selfstorage.booking.application;

/**
 * Lịch check-in {@code Pending} vừa bị huỷ mà khách chưa đến (cron no-show hoặc FS huỷ lịch). Phát đồng bộ trong
 * transaction huỷ lịch, trước khi booking trả đơn về {@code Deposited} — handover đóng {@code HandoverRecord} của lịch
 * ({@code CANCELED} + {@code reject_reason}) để đơn mở được phiên bàn giao mới.
 */
public record CheckinAppointmentClosed(Long appointmentId, Long orderId, String reason) {
}
