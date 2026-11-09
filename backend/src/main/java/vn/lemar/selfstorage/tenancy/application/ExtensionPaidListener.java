package vn.lemar.selfstorage.tenancy.application;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import vn.lemar.selfstorage.booking.application.ContractInvoicePaid;

/** Hoá đơn gia hạn {@code Paid} → cộng kỳ thuê, cùng transaction chốt thanh toán của booking. */
@Component
class ExtensionPaidListener {

    private final TenancyService tenancyService;

    ExtensionPaidListener(TenancyService tenancyService) {
        this.tenancyService = tenancyService;
    }

    @EventListener
    public void on(ContractInvoicePaid event) {
        if ("Extension".equals(event.invoiceType())) {
            tenancyService.onExtensionPaid(event.contractId(), event.invoiceId());
        }
    }
}
