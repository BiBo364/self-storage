package vn.lemar.selfstorage.booking.application.exception;

/** 400 — lỗi nghiệp vụ chung của booking, message tiếng Việt hiển thị thẳng cho FE. */
public class BookingValidationException extends RuntimeException {

    public BookingValidationException(String message) {
        super(message);
    }
}
