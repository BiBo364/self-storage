package vn.lemar.selfstorage.booking.application;

import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import vn.lemar.selfstorage.audit.application.AuditEntry;
import vn.lemar.selfstorage.audit.application.AuditRecorder;
import vn.lemar.selfstorage.audit.domain.AuditAction;
import vn.lemar.selfstorage.audit.domain.AuditEntityType;
import vn.lemar.selfstorage.payment.application.event.PaymentFailed;

/**
 * Giao dịch đóng {@code Failed} (cổng báo lỗi, phiên hết hạn, phiên bị huỷ) → audit {@code PAYMENT_FAILED}. Module
 * payment không phụ thuộc audit nên booking ghi hộ; hoá đơn giữ {@code Unpaid}, khoá phiên tự nhả.
 */
@Component
class PaymentFailureAudit {

    private final AuditRecorder auditRecorder;

    PaymentFailureAudit(AuditRecorder auditRecorder) {
        this.auditRecorder = auditRecorder;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void on(PaymentFailed event) {
        String entityId = event.transactionId() == null ? event.vnpTxnRef() : String.valueOf(event.transactionId());
        auditRecorder.record(new AuditEntry(AuditAction.PAYMENT_FAILED, AuditEntityType.PAYMENT_TRANSACTION, entityId,
                Map.of("status", "Pending"),
                Map.of("status", "Failed", "invoiceId", event.invoiceId(), "txnRef", event.vnpTxnRef()),
                event.reason(), null));
    }
}
