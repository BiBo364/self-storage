package vn.lemar.selfstorage.handover.application;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.lemar.selfstorage.booking.application.HandoverOrderService;
import vn.lemar.selfstorage.booking.application.HandoverOrderService.Release;
import vn.lemar.selfstorage.handover.domain.RentalContract;
import vn.lemar.selfstorage.handover.domain.RentalContractStatus;
import vn.lemar.selfstorage.handover.repository.RentalContractRepository;
import vn.lemar.selfstorage.notification.application.NotificationType;

/**
 * Hoá đơn tháng đầu quá hạn ({@code ContractInvoiceExpired}, hợp đồng {@code Signed}) → đóng phiên bàn giao, hợp đồng
 * {@code Canceled}; đơn còn hạn → {@code Deposited} + khách nhận {@code HANDOVER_OVERDUE}, quá hạn → Cancellation
 * Cascade. Phiên bàn giao quá {@code due_at} do {@link HandoverOverdueService} xử lý.
 */
@Service
public class HandoverExpiryService {

    static final String REASON_PAYMENT_OVERDUE = "Quá hạn thanh toán tháng đầu";

    private final RentalContractRepository contracts;
    private final HandoverService handoverService;
    private final HandoverOrderService orders;
    private final String frontendUrl;

    HandoverExpiryService(RentalContractRepository contracts, HandoverService handoverService,
                          HandoverOrderService orders, @Value("${app.frontend-url}") String frontendUrl) {
        this.contracts = contracts;
        this.handoverService = handoverService;
        this.orders = orders;
        this.frontendUrl = frontendUrl.replaceAll("/+$", "");
    }

    /** Hoá đơn tháng đầu vừa {@code Expired} (trong transaction cron của booking, đơn đã khoá). */
    @Transactional
    public void onFirstMonthInvoiceExpired(Long contractId) {
        RentalContract contract = contracts.findByIdForUpdate(contractId).orElse(null);
        if (contract == null || contract.getStatus() != RentalContractStatus.SIGNED) {
            return;
        }
        close(contract.getOrderId(), REASON_PAYMENT_OVERDUE);
    }

    private void close(Long orderId, String reason) {
        handoverService.cancelByOrderId(orderId, reason);
        if (orders.release(orderId, reason, null) == Release.RETURNED_TO_DEPOSITED) {
            orders.notifyCustomer(orderId, NotificationType.HANDOVER_OVERDUE,
                    Map.of("rescheduleLink", frontendUrl + "/appointments/new"));
        }
    }
}
