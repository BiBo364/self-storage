package vn.lemar.selfstorage.booking.application;

import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Cron 5 phút cho Flow 1: request hết hạn → hoá đơn quá hạn → catch-all đơn quá hạn (chạy cuối, sau các nhánh trên)
 * → nhắc đơn sắp hết hạn. Mỗi bản ghi một transaction; lỗi một bản ghi chỉ log.
 */
@Component
public class BookingExpiryJob {

    private static final Logger LOG = LoggerFactory.getLogger(BookingExpiryJob.class);

    private final BookingExpiryService service;

    public BookingExpiryJob(BookingExpiryService service) {
        this.service = service;
    }

    @Scheduled(
            fixedDelayString = "${booking.expiry.fixed-delay-ms:300000}",
            initialDelayString = "${booking.expiry.initial-delay-ms:75000}")
    public void run() {
        each("request hết hạn", service::expiredRequestIds, service::expireRequest);
        each("hoá đơn quá hạn", service::overdueInvoiceIds, service::expireInvoice);
        each("đơn quá hạn", service::expiredOrderIds, service::cancelExpiredOrder);
        each("nhắc đơn sắp hết hạn", service::expiringOrderIds, service::remindExpiring);
    }

    private void each(String label, Supplier<List<Long>> ids, Function<Long, Boolean> action) {
        int done = 0;
        for (Long id : ids.get()) {
            try {
                if (Boolean.TRUE.equals(action.apply(id))) {
                    done++;
                }
            } catch (RuntimeException e) {
                LOG.warn("Cron {} — bản ghi {} lỗi: {}", label, id, e.getMessage());
            }
        }
        if (done > 0) {
            LOG.info("Cron {}: đã xử lý {}", label, done);
        }
    }
}
