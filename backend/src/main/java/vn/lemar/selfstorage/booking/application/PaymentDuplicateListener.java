package vn.lemar.selfstorage.booking.application;

import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import vn.lemar.selfstorage.booking.repository.InvoiceRepository;
import vn.lemar.selfstorage.notification.application.NotificationType;
import vn.lemar.selfstorage.payment.application.event.PaymentDuplicateDetected;

/**
 * Giao dịch thành công thứ hai cho cùng invoice: không có gì để chốt trong booking, nhưng FM
 * cần biết để hoàn tiền thủ công.
 */
@Component
class PaymentDuplicateListener {

    private static final Logger LOG = LoggerFactory.getLogger(PaymentDuplicateListener.class);

    private final InvoiceRepository invoices;
    private final OrderNotifier orderNotifier;
    private final InvoiceRefundService refunds;

    PaymentDuplicateListener(InvoiceRepository invoices, OrderNotifier orderNotifier, InvoiceRefundService refunds) {
        this.invoices = invoices;
        this.orderNotifier = orderNotifier;
        this.refunds = refunds;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onDuplicatePayment(PaymentDuplicateDetected event) {
        Optional<Long> orderId = invoices.findOrderIdById(event.invoiceId());
        if (orderId.isEmpty()) {
            LOG.error("Có thanh toán trùng cho invoice {} nhưng không tìm thấy đơn — cần đối soát tay",
                    event.invoiceId());
            return;
        }
        // Khoản thu trùng phải trả lại: đưa hoá đơn vào hàng đợi hoàn tiền (FM/ADMIN thấy ở danh sách cần hoàn),
        // không chỉ báo FM. Hoá đơn đang chờ hoàn vì lý do khác thì giữ nguyên lý do đó.
        invoices.findByIdForUpdate(event.invoiceId())
                .filter(invoice -> "None".equals(invoice.getRefundStatus().dbValue()))
                .ifPresent(invoice -> refunds.requireFull(invoice, InvoiceRefundReasons.DUPLICATE_PAYMENT, null));
        orderNotifier.notifyFacilityManager(orderId.get(),
                NotificationType.PAYMENT_DUPLICATE_REFUND_REQUIRED,
                Map.of("orderCode", String.valueOf(orderId.get()),
                        "vnpTxnRef", event.vnpTxnRef(),
                        "amount", event.amount().toPlainString()));
    }
}
