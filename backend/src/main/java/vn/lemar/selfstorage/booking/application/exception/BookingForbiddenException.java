package vn.lemar.selfstorage.booking.application.exception;

/** 403 — lỗi nghiệp vụ chung của booking, message tiếng Việt hiển thị thẳng cho FE. */
public class BookingForbiddenException extends RuntimeException {

    public BookingForbiddenException(String message) {
        super(message);
    }
}
