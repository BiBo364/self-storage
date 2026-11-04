package vn.lemar.selfstorage.booking.application.exception;

/** Filter danh sách hoá đơn không hợp lệ — 400. */
public class InvoiceFilterException extends RuntimeException {

    public InvoiceFilterException(String message) {
        super(message);
    }
}
