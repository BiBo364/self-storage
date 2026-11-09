package vn.lemar.selfstorage.payment.application;

import java.util.Collection;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Đóng phiên thanh toán ở cổng để khách không trả được nữa (payOS: huỷ link).
 *
 * <ul>
 *   <li>{@link #closeAfterCommit}: hoá đơn / đơn vừa bị huỷ — gọi cổng <b>sau khi</b> transaction nghiệp vụ commit,
 *       best-effort. Lỗi gọi cổng không rollback việc huỷ (huỷ phía mình là nguồn sự thật); giao dịch giữ
 *       {@code Pending} để webhook / tra cứu cổng / đối soát thủ công quyết.</li>
 * </ul>
 *
 * <p>Mỗi giao dịch xử lý trong transaction riêng ({@code REQUIRES_NEW}) — trong {@code afterCommit} transaction cũ đã
 * commit nhưng tài nguyên vẫn bind, phải mở transaction mới mới ghi được.
 */
@Component
public class PaymentSessionCloser {

    private static final Logger LOG = LoggerFactory.getLogger(PaymentSessionCloser.class);

    private final PaymentService paymentService;
    private final TransactionTemplate requiresNew;

    public PaymentSessionCloser(PaymentService paymentService, PlatformTransactionManager transactionManager) {
        this.paymentService = paymentService;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Lấy danh sách giao dịch {@code Pending} ngay (trong transaction hiện tại), huỷ ở cổng sau commit. */
    public void closeAfterCommit(Collection<Long> invoiceIds, String reason) {
        List<String> txnRefs = paymentService.pendingTxnRefs(invoiceIds);
        if (txnRefs.isEmpty()) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            txnRefs.forEach(ref -> cancel(ref, reason));
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                txnRefs.forEach(ref -> cancel(ref, reason));
            }
        });
    }

    private void cancel(String txnRef, String reason) {
        try {
            requiresNew.executeWithoutResult(s -> paymentService.cancelSession(txnRef, reason));
        } catch (RuntimeException e) {
            LOG.warn("Huỷ phiên thanh toán {} lỗi — giữ Pending, đối soát sẽ xử lý: {}", txnRef, e.getMessage());
        }
    }
}
