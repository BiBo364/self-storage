package vn.lemar.selfstorage.booking.application.exception;

/** 404 — lịch hẹn không tồn tại. */
public class AppointmentNotFoundException extends RuntimeException {

    public AppointmentNotFoundException() {
        super("Không tìm thấy lịch hẹn");
    }
}
