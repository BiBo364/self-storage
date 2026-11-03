package vn.lemar.selfstorage.payment.application.exception;

/** Không có giao dịch với mã tham chiếu được nhập. */
public class ManualReconciliationNotFoundException extends RuntimeException {

    public ManualReconciliationNotFoundException(String txnRef) {
        super("Không tìm thấy giao dịch " + txnRef);
    }
}
