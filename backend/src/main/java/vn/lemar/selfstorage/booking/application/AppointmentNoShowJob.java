package vn.lemar.selfstorage.booking.application;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

/** Cron 5 phút: quét lịch check-in quá giờ chưa đến và huỷ no-show. */
@Component
public class AppointmentNoShowJob {

    private static final Logger LOG = LoggerFactory.getLogger(AppointmentNoShowJob.class);

    private final AppointmentNoShowService service;

    public AppointmentNoShowJob(AppointmentNoShowService service) {
        this.service = service;
    }

    @Scheduled(
            fixedDelayString = "${booking.appointment-no-show.fixed-delay-ms:300000}",
            initialDelayString = "${booking.appointment-no-show.initial-delay-ms:60000}")
    @SchedulerLock(name = "appointmentNoShow", lockAtMostFor = "PT4M")
    public void cancelNoShow() {
        List<Long> due = service.findDueIds();
        int canceled = 0;
        for (Long id : due) {
            try {
                if (service.cancelIfDue(id)) {
                    canceled++;
                }
            } catch (RuntimeException e) {
                LOG.warn("Không huỷ được lịch no-show {}: {}", id, e.getMessage());
            }
        }
        if (canceled > 0) {
            LOG.info("No-show: đã huỷ {} lịch hẹn", canceled);
        }
    }
}
