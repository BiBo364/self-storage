package vn.lemar.selfstorage.handover.application;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import vn.lemar.selfstorage.booking.application.CheckinAppointmentClosed;
import vn.lemar.selfstorage.booking.application.ContractInvoiceExpired;

/**
 * Điểm vào Flow 2 từ booking (event đồng bộ, cùng transaction của booking). Cron quá hạn bàn giao nằm ở
 * {@link HandoverOverdueJob}.
 */
@Component
class HandoverFlowListeners {

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
}
