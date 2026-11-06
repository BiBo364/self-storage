package vn.lemar.selfstorage.booking.application;

import java.time.Instant;

/**
 * Flow 2.1: FS đã xác nhận khách đến. {@code handover} nghe event này (PR Flow 2) để set
 * {@code HandoverRecord.due_at}; appointment không tự biết due_days.
 *
 * <p>{@code arrivedAt} là mốc {@code Appointment.arrived_at} vừa ghi — listener dùng làm gốc tính
 * hạn, không đọc lại đồng hồ của chính nó.
 */
public record AppointmentArrived(Long appointmentId, Instant arrivedAt) {
}
