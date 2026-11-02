package vn.lemar.selfstorage.payment.application.exception;

import vn.lemar.selfstorage.payment.domain.PaymentStatus;

/** Giao dịch đã được chốt trước đó — IPN hoặc một lần đối soát khác thắng. */
public class ManualReconciliationConflictException extends RuntimeException {

    public ManualReconciliationConflictException(PaymentStatus status) {
        super("Giao dịch đã ở trạng thái " + status.dbValue() + ", không chốt lại");
    }

    public ManualReconciliationConflictException(String message) {
        super(message);
    }
}
