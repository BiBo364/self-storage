package vn.lemar.selfstorage.booking.application.exception;

/** 409 — trạng thái lịch hẹn / đơn không cho phép thao tác (đã có FS, không còn Pending, đơn không Scheduled). */
public class AppointmentConflictException extends RuntimeException {

    public AppointmentConflictException(String message) {
        super(message);
    }
}
