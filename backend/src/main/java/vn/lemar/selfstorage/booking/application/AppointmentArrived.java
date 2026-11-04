package vn.lemar.selfstorage.booking.application;

/**
 * Flow 2.1: FS đã xác nhận khách đến. {@code handover} nghe event này (PR Flow 2) để set
 * {@code HandoverRecord.due_at}; appointment không tự biết due_days.
 */
public record AppointmentArrived(Long appointmentId) {
}
