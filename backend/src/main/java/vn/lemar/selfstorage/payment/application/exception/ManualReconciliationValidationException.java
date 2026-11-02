package vn.lemar.selfstorage.payment.application.exception;

/** Request đối soát thủ công thiếu dữ liệu bắt buộc theo outcome. */
public class ManualReconciliationValidationException extends RuntimeException {

    public ManualReconciliationValidationException(String message) {
        super(message);
    }
}
