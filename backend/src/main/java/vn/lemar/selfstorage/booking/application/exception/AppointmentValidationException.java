package vn.lemar.selfstorage.booking.application.exception;

/** 400 — dữ liệu gửi lên không hợp lệ (vd FS không thuộc cơ sở của lịch hẹn). */
public class AppointmentValidationException extends RuntimeException {

    public AppointmentValidationException(String message) {
        super(message);
    }
}
