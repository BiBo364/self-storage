package vn.lemar.selfstorage.booking.domain;

/** Trạng thái lịch hẹn — khớp CHECK {@code appointments.status} (V1, spec db-table-draft.md › Appointment). */
public enum AppointmentStatus {
    Pending,
    Done,
    Canceled
}
