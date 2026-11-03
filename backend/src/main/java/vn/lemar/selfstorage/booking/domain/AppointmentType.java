package vn.lemar.selfstorage.booking.domain;

/** Loại lịch hẹn — khớp CHECK {@code appointments.type} (V1). Flow 1 chỉ tạo {@code CHECKIN}. */
public enum AppointmentType {
    CHECKIN,
    HANDOVER,
    RETURN
}
