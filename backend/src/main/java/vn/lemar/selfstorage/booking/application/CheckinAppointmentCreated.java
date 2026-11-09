package vn.lemar.selfstorage.booking.application;

/**
 * Khách vừa đặt lịch check-in (spec 1.5). Phát đồng bộ trong transaction đặt lịch; module handover nghe
 * {@code BEFORE_COMMIT} để mở {@code HandoverRecord} ({@code result = IN_PROGRESS}) — lỗi ở đó rollback cả lịch.
 */
public record CheckinAppointmentCreated(Long appointmentId, Long orderId, Long unitId) {
}
