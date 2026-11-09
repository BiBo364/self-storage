package vn.lemar.selfstorage.booking.application;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import vn.lemar.selfstorage.audit.application.AuditEntry;
import vn.lemar.selfstorage.audit.application.AuditRecorder;
import vn.lemar.selfstorage.audit.domain.AuditAction;
import vn.lemar.selfstorage.audit.domain.AuditEntityType;
import vn.lemar.selfstorage.booking.domain.Invoice;
import vn.lemar.selfstorage.booking.domain.InvoiceStatus;
import vn.lemar.selfstorage.booking.domain.RentalOrder;
import vn.lemar.selfstorage.booking.repository.InvoiceRepository;
import vn.lemar.selfstorage.booking.repository.RentalOrderRepository;
import vn.lemar.selfstorage.notification.application.NotificationType;
import vn.lemar.selfstorage.payment.application.event.PaymentSucceeded;

/**
 * Cổng đã xác nhận thu tiền cho hoá đơn <b>gắn hợp đồng</b> (tháng đầu, gia hạn, tiền thuê, phạt, quyết toán trả
 * kho…): hoá đơn {@code Paid} + audit {@code INVOICE_PAID}, rồi phát {@link ContractInvoicePaid} cho module sở hữu
 * hợp đồng. Hoá đơn cọc do {@link DepositPaymentListener} chốt.
 *
 * <p>Listener {@code AFTER_COMMIT} + {@code REQUIRES_NEW}: lỗi ở đây không rollback giao dịch của cổng. Khoá đơn
 * trước rồi mới khoá hoá đơn — cùng thứ tự với listener cọc và cascade huỷ.
 *
 * <p>Xác nhận muộn (hoá đơn đã {@code Canceled}/{@code Expired}): tiền đã vào, không mở lại — hoá đơn
 * {@code refund_status = Required} + FM nhận {@code FIRST_MONTH_REFUND_REQUIRED} để hoàn thủ công.
 */
@Component
class InvoicePaymentSettlement {

    private static final Logger LOG = LoggerFactory.getLogger(InvoicePaymentSettlement.class);

    private final InvoiceRepository invoiceRepository;
    private final RentalOrderRepository orderRepository;
    private final AuditRecorder auditRecorder;
    private final OrderNotifier orderNotifier;
    private final InvoiceRefundService refunds;
    private final ApplicationEventPublisher events;

    InvoicePaymentSettlement(InvoiceRepository invoiceRepository, RentalOrderRepository orderRepository,
                             AuditRecorder auditRecorder, OrderNotifier orderNotifier, InvoiceRefundService refunds,
                             ApplicationEventPublisher events) {
        this.invoiceRepository = invoiceRepository;
        this.orderRepository = orderRepository;
        this.auditRecorder = auditRecorder;
        this.orderNotifier = orderNotifier;
        this.refunds = refunds;
        this.events = events;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onPaymentSucceeded(PaymentSucceeded event) {
        // Đọc projection trước: hoá đơn cọc thuộc DepositPaymentListener, không khoá đơn hai lần.
        if (invoiceRepository.findContractIdById(event.invoiceId()).isEmpty()) {
            return;
        }
        Long orderId = invoiceRepository.findOrderIdById(event.invoiceId()).orElse(null);
        RentalOrder order = orderId == null ? null : orderRepository.findByIdForUpdate(orderId).orElse(null);
        Invoice invoice = invoiceRepository.findByIdForUpdate(event.invoiceId()).orElse(null);
        if (invoice == null) {
            LOG.error("Thanh toán {} thành công nhưng không tìm thấy hoá đơn {}", event.vnpTxnRef(), event.invoiceId());
            return;
        }
        if (!invoice.isUnpaid()) {
            LOG.error("Xác nhận muộn: giao dịch {} đã thu {} cho hoá đơn {} ở trạng thái {} — cần xử lý thủ công",
                    event.vnpTxnRef(), event.amount(), invoice.getCode(), invoice.getStatus());
            if (invoice.getStatus() != InvoiceStatus.PAID) {
                refunds.requireFull(invoice, InvoiceRefundReasons.INVOICE_CLOSED, null);
                if (order != null) {
                    orderNotifier.notifyFacilityManager(order.getId(), NotificationType.FIRST_MONTH_REFUND_REQUIRED,
                            Map.of("orderCode", OrderNotifier.orderCode(order), "refundAmount",
                                    event.amount().toPlainString(), "reason", "thanh toán sau khi hoá đơn đã đóng"));
                }
            }
            return;
        }
        invoice.markPaid();
        auditRecorder.record(new AuditEntry(AuditAction.INVOICE_PAID, AuditEntityType.INVOICE,
                String.valueOf(invoice.getId()), Map.of("status", InvoiceStatus.UNPAID.dbValue()),
                Map.of("status", invoice.getStatus().dbValue(), "vnpTxnRef", event.vnpTxnRef()), null, null));
        events.publishEvent(new ContractInvoicePaid(invoice.getId(), invoice.getContractId(),
                invoice.getType().dbValue(), event.paidAt()));
    }
}
