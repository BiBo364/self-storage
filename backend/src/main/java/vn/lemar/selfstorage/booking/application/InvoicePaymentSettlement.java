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
import vn.lemar.selfstorage.booking.domain.InvoiceType;
import vn.lemar.selfstorage.booking.domain.RentalOrder;
import vn.lemar.selfstorage.booking.domain.RentalOrderStatus;
import vn.lemar.selfstorage.booking.repository.InvoiceRepository;
import vn.lemar.selfstorage.booking.repository.RentalOrderRepository;
import vn.lemar.selfstorage.facility.application.StorageUnitQuery;
import vn.lemar.selfstorage.facility.application.StorageUnitService;
import vn.lemar.selfstorage.facility.application.StorageUnitView;
import vn.lemar.selfstorage.notification.application.NotificationType;
import vn.lemar.selfstorage.payment.application.event.PaymentSucceeded;

/**
 * Cổng đã xác nhận thu tiền ({@link PaymentSucceeded}, từ IPN hoặc đối soát {@code querydr}) → chốt hoá đơn.
 *
 * <ul>
 *   <li>{@code Deposit} (cọc đầu): hoá đơn {@code Paid}, khoang {@code Available -> Reserved}, đơn
 *       {@code Pending -> Deposited}, audit {@code PAYMENT_SUCCEEDED} + {@code INVOICE_PAID} +
 *       {@code RENTAL_ORDER_DEPOSITED} + {@code STORAGE_UNIT_RESERVED}, khách nhận
 *       {@code DEPOSIT_PAYMENT_SUCCEEDED}.</li>
 *   <li>{@code DepositSupplement} (đổi khoang sau cọc): hoá đơn {@code Paid} + reserve khoang mới; không đổi trạng
 *       thái đơn, không gửi lại "Đặt cọc thành công".</li>
 *   <li>Hoá đơn gắn hợp đồng (tháng đầu, gia hạn…): hoá đơn {@code Paid} rồi phát {@link ContractInvoicePaid} cho
 *       module sở hữu hợp đồng.</li>
 * </ul>
 *
 * <p>Listener {@code AFTER_COMMIT} + {@code REQUIRES_NEW}: lỗi ở đây không rollback giao dịch của cổng (tiền đã vào
 * thật, VNPay không retry vô ích). Khoá đơn trước rồi mới khoá hoá đơn — cùng thứ tự với đồng ý đề xuất.
 *
 * <p>Xác nhận muộn (hoá đơn đã {@code Canceled}/{@code Expired}, hoặc khoang đã bị người khác giữ): tiền đã vào,
 * không tự mở lại đơn — log ERROR + báo FM để xử lý thủ công (hoàn tiền / xếp khoang khác).
 */
@Component
class InvoicePaymentSettlement {

    private static final Logger LOG = LoggerFactory.getLogger(InvoicePaymentSettlement.class);

    private final InvoiceRepository invoiceRepository;
    private final RentalOrderRepository orderRepository;
    private final StorageUnitService storageUnitService;
    private final StorageUnitQuery storageUnitQuery;
    private final AuditRecorder auditRecorder;
    private final OrderNotifier orderNotifier;
    private final ApplicationEventPublisher events;

    InvoicePaymentSettlement(InvoiceRepository invoiceRepository, RentalOrderRepository orderRepository,
                             StorageUnitService storageUnitService, StorageUnitQuery storageUnitQuery,
                             AuditRecorder auditRecorder, OrderNotifier orderNotifier,
                             ApplicationEventPublisher events) {
        this.invoiceRepository = invoiceRepository;
        this.orderRepository = orderRepository;
        this.storageUnitService = storageUnitService;
        this.storageUnitQuery = storageUnitQuery;
        this.auditRecorder = auditRecorder;
        this.orderNotifier = orderNotifier;
        this.events = events;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onPaymentSucceeded(PaymentSucceeded event) {
        Long orderId = invoiceRepository.findOrderIdById(event.invoiceId()).orElse(null);
        RentalOrder order = orderId == null ? null : orderRepository.findByIdForUpdate(orderId).orElse(null);
        Invoice invoice = invoiceRepository.findByIdForUpdate(event.invoiceId()).orElse(null);
        if (invoice == null) {
            LOG.error("Thanh toán {} thành công nhưng không tìm thấy hoá đơn {}", event.vnpTxnRef(), event.invoiceId());
            return;
        }
        recordPayment(event, invoice);
        if (!invoice.isUnpaid()) {
            LOG.error("Xác nhận muộn: giao dịch {} đã thu {} cho hoá đơn {} ở trạng thái {} — cần xử lý thủ công",
                    event.vnpTxnRef(), event.amount(), invoice.getCode(), invoice.getStatus());
            if (order != null && invoice.isDepositKind()) {
                orderNotifier.notifyFacilityManager(order.getId(), NotificationType.PROPOSAL_REPROPOSAL_REQUIRED,
                        Map.of("orderCode", OrderNotifier.orderCode(order), "unitCode", unitCode(order)));
            } else if (order != null) {
                // Hoá đơn tháng đầu đã đóng (hợp đồng huỷ / quá hạn) mà tiền vẫn vào → FM hoàn thủ công.
                orderNotifier.notifyFacilityManager(order.getId(), NotificationType.FIRST_MONTH_REFUND_REQUIRED,
                        Map.of("orderCode", OrderNotifier.orderCode(order), "refundAmount",
                                event.amount().toPlainString(), "reason", "thanh toán sau khi hoá đơn đã đóng"));
            }
            return;
        }
        invoice.markPaid();
        auditRecorder.record(new AuditEntry(AuditAction.INVOICE_PAID, AuditEntityType.INVOICE,
                String.valueOf(invoice.getId()), Map.of("status", InvoiceStatus.UNPAID.dbValue()),
                Map.of("status", invoice.getStatus().dbValue(), "vnpTxnRef", event.vnpTxnRef()), null, null));

        if (order != null && invoice.getType() == InvoiceType.DEPOSIT) {
            settleDeposit(order);
        } else if (order != null && invoice.getType() == InvoiceType.DEPOSIT_SUPPLEMENT) {
            if (order.getStatus() == RentalOrderStatus.PENDING) {
                // Đơn đã trả cọc nhưng khoang cũ bị giữ trước, nay bù chênh lệch cho khoang thay thế → chốt cọc.
                settleDeposit(order);
            } else {
                reserveUnit(order);
            }
        } else if (invoice.getContractId() != null) {
            events.publishEvent(new ContractInvoicePaid(invoice.getId(), invoice.getContractId(),
                    invoice.getType().dbValue(), event.paidAt()));
        }
    }

    private void settleDeposit(RentalOrder order) {
        if (order.getStatus() != RentalOrderStatus.PENDING) {
            LOG.error("Cọc đơn {} đã thu nhưng đơn ở trạng thái {} — cần xử lý thủ công", order.getId(),
                    order.getStatus());
            return;
        }
        if (!reserveUnit(order)) {
            return;
        }
        String before = order.getStatus().dbValue();
        order.markDeposited();
        auditRecorder.record(new AuditEntry(AuditAction.RENTAL_ORDER_DEPOSITED, AuditEntityType.RENTAL_ORDER,
                String.valueOf(order.getId()), Map.of("status", before),
                Map.of("status", order.getStatus().dbValue()), null, null));
        orderNotifier.notifyCustomer(order, NotificationType.DEPOSIT_PAYMENT_SUCCEEDED,
                Map.of("orderCode", OrderNotifier.orderCode(order)));
    }

    /** Khoang của đơn {@code Available -> Reserved}; khoang đã bị giữ thì báo FM (tiền đã vào, khoang mất). */
    private boolean reserveUnit(RentalOrder order) {
        if (order.getUnitId() == null) {
            LOG.error("Đơn {} đã thu cọc nhưng chưa gán khoang", order.getId());
            return false;
        }
        if (storageUnitService.reserveIfAvailable(order.getUnitId())) {
            auditRecorder.record(new AuditEntry(AuditAction.STORAGE_UNIT_RESERVED, AuditEntityType.STORAGE_UNIT,
                    String.valueOf(order.getUnitId()), Map.of("status", "Available"), Map.of("status", "Reserved",
                    "orderId", order.getId()), null, null));
            // Khoang đã có chủ: chặn ngay mọi hoá đơn cọc khác trỏ khoang này (sau commit, transaction riêng).
            events.publishEvent(new UnitReservedByDeposit(order.getUnitId(), order.getId()));
            return true;
        }
        LOG.error("Đơn {} đã thu cọc nhưng khoang {} không còn Available — cần xếp khoang khác/hoàn tiền thủ công",
                order.getId(), order.getUnitId());
        orderNotifier.notifyFacilityManager(order.getId(), NotificationType.PROPOSAL_REPROPOSAL_REQUIRED,
                Map.of("orderCode", OrderNotifier.orderCode(order), "unitCode", unitCode(order)));
        return false;
    }

    private void recordPayment(PaymentSucceeded event, Invoice invoice) {
        String entityId = event.transactionId() == null ? event.vnpTxnRef() : String.valueOf(event.transactionId());
        auditRecorder.record(new AuditEntry(AuditAction.PAYMENT_SUCCEEDED, AuditEntityType.PAYMENT_TRANSACTION,
                entityId, Map.of("status", "Pending"),
                Map.of("status", "Success", "invoiceId", invoice.getId(), "amount", event.amount(),
                        "vnpTxnRef", event.vnpTxnRef()), null, null));
    }

    private String unitCode(RentalOrder order) {
        return order.getUnitId() == null ? ""
                : storageUnitQuery.find(order.getUnitId()).map(StorageUnitView::code).orElse("");
    }
}
