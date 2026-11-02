package vn.lemar.selfstorage.payment.application.exception;

import vn.lemar.selfstorage.payment.domain.PaymentStatus;

/** Giao dịch đã được chốt trước đó — IPN hoặc một lần đối soát khác thắng. */
public class ManualReconciliationConflictException extends RuntimeException {

    private final PaymentStatus status;

    public ManualReconciliationConflictException(PaymentStatus status) {
        super("Giao dịch đã ở trạng thái " + status.dbValue() + ", không chốt lại");
        this.status = status;
    }

    public ManualReconciliationConflictException(String message) {
        super(message);
        this.status = null;
    }

    public PaymentStatus status() {
        return status;
    }
}
