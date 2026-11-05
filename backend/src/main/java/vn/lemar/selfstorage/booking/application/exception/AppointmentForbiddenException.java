package vn.lemar.selfstorage.booking.application.exception;

/** 403 — FS không phải người được phân công cho lịch hẹn. */
public class AppointmentForbiddenException extends RuntimeException {

    public AppointmentForbiddenException(String message) {
        super(message);
    }
}
