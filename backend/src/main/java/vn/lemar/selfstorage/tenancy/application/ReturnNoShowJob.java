package vn.lemar.selfstorage.tenancy.application;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Cron 10 phút: lịch trả kho khách không đến (quá {@code end_at + return.no_show_grace_minutes}) → no-show. */
@Component
public class ReturnNoShowJob {

    private static final Logger LOG = LoggerFactory.getLogger(ReturnNoShowJob.class);

    private final TenancyService tenancyService;

    ReturnNoShowJob(TenancyService tenancyService) {
        this.tenancyService = tenancyService;
    }

    @Scheduled(
            fixedDelayString = "${tenancy.return-no-show.fixed-delay-ms:600000}",
            initialDelayString = "${tenancy.return-no-show.initial-delay-ms:90000}")
    public void run() {
        List<Long> due = tenancyService.missedReturnAppointmentIds();
        int handled = 0;
        for (Long appointmentId : due) {
            try {
                if (tenancyService.handleReturnNoShow(appointmentId)) {
                    handled++;
                }
            } catch (RuntimeException e) {
                LOG.warn("Không xử lý được no-show trả kho, lịch {}: {}", appointmentId, e.getMessage());
            }
        }
        if (handled > 0) {
            LOG.info("No-show trả kho: đã xử lý {} lịch", handled);
        }
    }
}
