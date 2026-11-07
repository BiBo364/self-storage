package vn.lemar.selfstorage.payment.application;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Cron 5 phút: giao dịch {@code Pending} đã quá cửa sổ phiên (khách bỏ ngang, IPN không về) được đối soát bằng
 * {@code querydr} — cổng có giao dịch thì chốt theo trạng thái thật, không có thì đóng {@code Failed}.
 */
@Component
public class PaymentReconcileJob {

    private static final Logger LOG = LoggerFactory.getLogger(PaymentReconcileJob.class);
    private static final String SERVER_IP = "127.0.0.1";

    private final PaymentService paymentService;

    public PaymentReconcileJob(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @Scheduled(
            fixedDelayString = "${payment.reconcile.fixed-delay-ms:300000}",
            initialDelayString = "${payment.reconcile.initial-delay-ms:90000}")
    public void reconcileStale() {
        List<String> due = paymentService.stalePendingTxnRefs();
        for (String txnRef : due) {
            try {
                paymentService.reconcile(txnRef, SERVER_IP);
            } catch (RuntimeException e) {
                LOG.warn("Không đối soát được giao dịch {}: {}", txnRef, e.getMessage());
            }
        }
        if (!due.isEmpty()) {
            LOG.info("Đối soát thanh toán: đã xử lý {} giao dịch Pending quá hạn phiên", due.size());
        }
    }
}
