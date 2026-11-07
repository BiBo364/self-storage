package vn.lemar.selfstorage.handover.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.lemar.selfstorage.booking.application.HandoverOrderService;
import vn.lemar.selfstorage.booking.application.HandoverOrderService.Release;
import vn.lemar.selfstorage.handover.domain.HandoverRecord;
import vn.lemar.selfstorage.handover.domain.RentalContract;
import vn.lemar.selfstorage.handover.domain.RentalContractStatus;
import vn.lemar.selfstorage.handover.repository.HandoverRecordRepository;
import vn.lemar.selfstorage.handover.repository.RentalContractRepository;
import vn.lemar.selfstorage.notification.application.NotificationType;
import vn.lemar.selfstorage.payment.application.PaymentService;

/**
 * Cron Flow 2 ({@code specs} › Scheduled Jobs):
 * <ul>
 *   <li>{@code HandoverRecord} đang mở quá {@code due_at} → {@code CANCELED} "Chưa hoàn tất bàn giao trong hạn",
 *       hợp đồng {@code Draft}/{@code Signed} → {@code Canceled}; đơn còn hạn → {@code Deposited} + khách nhận
 *       {@code HANDOVER_OVERDUE}, quá hạn → Cancellation Cascade.</li>
 *   <li>Hoá đơn tháng đầu quá hạn ({@code ContractInvoiceExpired}, hợp đồng {@code Signed}) → cùng xử lý với lý do
 *       "Quá hạn thanh toán tháng đầu".</li>
 * </ul>
 * Mỗi bản ghi một transaction; thứ tự khoá đơn → biên bản → hợp đồng.
 */
@Service
public class HandoverExpiryService {

    static final String REASON_OVERDUE = "Chưa hoàn tất bàn giao trong hạn";
    static final String REASON_PAYMENT_OVERDUE = "Quá hạn thanh toán tháng đầu";

    private final HandoverRecordRepository records;
    private final RentalContractRepository contracts;
    private final HandoverService handoverService;
    private final HandoverOrderService orders;
    private final PaymentService paymentService;
    private final String frontendUrl;

    HandoverExpiryService(HandoverRecordRepository records, RentalContractRepository contracts,
                          HandoverService handoverService, HandoverOrderService orders,
                          PaymentService paymentService, @Value("${app.frontend-url}") String frontendUrl) {
        this.records = records;
        this.contracts = contracts;
        this.handoverService = handoverService;
        this.orders = orders;
        this.paymentService = paymentService;
        this.frontendUrl = frontendUrl.replaceAll("/+$", "");
    }

    @Transactional(readOnly = true)
    public List<Long> overdueIds() {
        return records.findOverdueOpenIds(Instant.now());
    }

    /** Đóng một phiên quá hạn; {@code true} khi thực sự đóng. Hoá đơn tháng đầu còn phiên cổng mở → đợi. */
    @Transactional
    public boolean closeIfOverdue(Long handoverId) {
        HandoverRecord peek = records.findById(handoverId).orElse(null);
        if (peek == null || !peek.isOpen()) {
            return false;
        }
        RentalContract contract = contracts.findCurrentByOrderId(peek.getOrderId()).orElse(null);
        if (contract != null && hasOpenPaymentSession(contract)) {
            return false;
        }
        orders.lock(peek.getOrderId());
        HandoverRecord record = records.findByIdForUpdate(handoverId).orElse(null);
        if (record == null || !record.isOpen() || record.getDueAt() == null
                || !record.getDueAt().isBefore(Instant.now())) {
            return false;
        }
        close(record.getOrderId(), REASON_OVERDUE);
        return true;
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

    private boolean hasOpenPaymentSession(RentalContract contract) {
        return orders.firstMonthInvoice(contract.getId())
                .filter(invoice -> "Unpaid".equals(invoice.status()))
                .map(invoice -> !paymentService.invoicesWithOpenSession(List.of(invoice.invoiceId())).isEmpty())
                .orElse(false);
    }
}
