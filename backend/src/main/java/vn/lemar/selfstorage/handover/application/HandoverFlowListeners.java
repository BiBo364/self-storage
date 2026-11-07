package vn.lemar.selfstorage.handover.application;

import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import vn.lemar.selfstorage.booking.application.CheckinAppointmentClosed;
import vn.lemar.selfstorage.booking.application.ContractInvoiceExpired;

/**
 * Điểm vào Flow 2 từ booking (event đồng bộ, cùng transaction của booking) và cron quá hạn bàn giao.
 */
@Component
class HandoverFlowListeners {

    private static final Logger LOG = LoggerFactory.getLogger(HandoverFlowListeners.class);

    private final HandoverService handoverService;
    private final HandoverExpiryService expiryService;

    HandoverFlowListeners(HandoverService handoverService, HandoverExpiryService expiryService) {
        this.handoverService = handoverService;
        this.expiryService = expiryService;
    }

    /** Lịch check-in bị huỷ khi khách chưa đến (no-show / FS huỷ) → đóng biên bản của lịch. */
    @EventListener
    public void on(CheckinAppointmentClosed event) {
        handoverService.cancelByAppointmentId(event.appointmentId(), event.reason());
    }

    /** Hoá đơn tháng đầu quá hạn → đóng phiên bàn giao, hợp đồng {@code Signed} → {@code Canceled}. */
    @EventListener
    public void on(ContractInvoiceExpired event) {
        if ("Rental".equals(event.invoiceType())) {
            expiryService.onFirstMonthInvoiceExpired(event.contractId());
        }
    }

    @Scheduled(
            fixedDelayString = "${handover.expiry.fixed-delay-ms:300000}",
            initialDelayString = "${handover.expiry.initial-delay-ms:90000}")
    public void closeOverdue() {
        each("phiên bàn giao quá hạn", expiryService::overdueIds, expiryService::closeIfOverdue);
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
