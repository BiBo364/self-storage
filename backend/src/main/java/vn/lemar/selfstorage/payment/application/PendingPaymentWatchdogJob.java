package vn.lemar.selfstorage.payment.application;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Phát hiện PAY/Pending quá hạn và tạo case cho ADMIN; không đổi trạng thái giao dịch. */
@Component
public class PendingPaymentWatchdogJob {

    private static final Logger LOG = LoggerFactory.getLogger(PendingPaymentWatchdogJob.class);

    private final PaymentReconciliationCaseService cases;
    private final int batchSize;

    public PendingPaymentWatchdogJob(PaymentReconciliationCaseService cases,
                                     @Value("${payment.pending-watchdog.batch-size:50}") int batchSize) {
        this.cases = cases;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${payment.pending-watchdog.fixed-delay-ms:300000}",
            initialDelayString = "${payment.pending-watchdog.initial-delay-ms:60000}")
    public void scan() {
        try {
            int created = cases.createMissingCases(Instant.now().minus(PaymentSession.WINDOW), batchSize);
            if (created > 0) {
                LOG.info("Đã tạo {} case đối soát Pending", created);
            }
        } catch (RuntimeException e) {
            LOG.error("Không quét được case đối soát Pending", e);
        }
    }
}
