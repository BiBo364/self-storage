package vn.lemar.selfstorage.booking.application.exception;

/** 404 — lỗi nghiệp vụ chung của booking, message tiếng Việt hiển thị thẳng cho FE. */
public class BookingNotFoundException extends RuntimeException {

    public BookingNotFoundException(String message) {
        super(message);
    }
}
