package vn.lemar.selfstorage.payment.application.exception;

/** Filter danh sách case Pending không hợp lệ — 400. */
public class PendingPaymentFilterException extends RuntimeException {

    public PendingPaymentFilterException(String message) {
        super(message);
    }
}
