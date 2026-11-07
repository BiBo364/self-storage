package vn.lemar.selfstorage.overdue.application;

import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Cron Flow 6 (mặc định mỗi giờ): nhắc sắp hết hạn, đồng bộ phí quá hạn. */
@Component
class OverdueJob {

    private static final Logger LOG = LoggerFactory.getLogger(OverdueJob.class);

    private final OverdueService service;

    OverdueJob(OverdueService service) {
        this.service = service;
    }

    @Scheduled(
            fixedDelayString = "${overdue.job.fixed-delay-ms:3600000}",
            initialDelayString = "${overdue.job.initial-delay-ms:150000}")
    public void run() {
        each("nhắc hợp đồng sắp hết hạn", service::expiringIds, service::remindExpiring);
        each("phí quá hạn", service::overdueCandidateIds, service::accrue);
    }

    private void each(String label, Supplier<List<Long>> ids, Function<Long, Boolean> action) {
        int done = 0;
        for (Long id : ids.get()) {
            try {
                if (Boolean.TRUE.equals(action.apply(id))) {
                    done++;
                }
            } catch (RuntimeException e) {
                LOG.warn("Cron {} — hợp đồng {} lỗi: {}", label, id, e.getMessage());
            }
        }
        if (done > 0) {
            LOG.info("Cron {}: {}", label, done);
        }
    }
}
