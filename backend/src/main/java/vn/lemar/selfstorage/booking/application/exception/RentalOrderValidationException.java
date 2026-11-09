package vn.lemar.selfstorage.booking.application.exception;

/** 400 — filter/paging của danh sách đơn thuê không hợp lệ. */
public class RentalOrderValidationException extends RuntimeException {

    public RentalOrderValidationException(String message) {
        super(message);
    }
}
