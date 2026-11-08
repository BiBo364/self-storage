package vn.lemar.selfstorage.handover.application;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Cron 5 phút: đóng các phiên bàn giao đã quá {@code due_at}. */
@Component
public class HandoverOverdueJob {

    private static final Logger LOG = LoggerFactory.getLogger(HandoverOverdueJob.class);

    private final HandoverOverdueService service;

    public HandoverOverdueJob(HandoverOverdueService service) {
        this.service = service;
    }

    @Scheduled(
            fixedDelayString = "${handover.overdue.fixed-delay-ms:300000}",
            initialDelayString = "${handover.overdue.initial-delay-ms:60000}")
    public void cancelOverdue() {
        List<Long> due = service.findOverdueIds();
        int canceled = 0;
        for (Long id : due) {
            try {
                if (service.cancelIfOverdue(id)) {
                    canceled++;
                }
            } catch (RuntimeException e) {
                LOG.warn("Không huỷ được handover quá hạn {}: {}", id, e.getMessage());
            }
        }
        if (canceled > 0) {
            LOG.info("Handover quá hạn: đã huỷ {} phiên", canceled);
        }
    }
}
