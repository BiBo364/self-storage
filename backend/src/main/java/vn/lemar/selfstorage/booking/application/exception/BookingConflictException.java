package vn.lemar.selfstorage.booking.application.exception;

/** 409 — lỗi nghiệp vụ chung của booking, message tiếng Việt hiển thị thẳng cho FE. */
public class BookingConflictException extends RuntimeException {

    public BookingConflictException(String message) {
        super(message);
    }
}
