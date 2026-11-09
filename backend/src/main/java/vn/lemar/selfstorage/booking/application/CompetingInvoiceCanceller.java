package vn.lemar.selfstorage.booking.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import vn.lemar.selfstorage.booking.domain.RentalOrderStatus;
import vn.lemar.selfstorage.booking.repository.InvoiceRepository;
import vn.lemar.selfstorage.booking.repository.RentalOrderRepository;
import vn.lemar.selfstorage.facility.application.StorageUnitQuery;
import vn.lemar.selfstorage.facility.application.StorageUnitView;
import vn.lemar.selfstorage.notification.application.NotificationType;
import vn.lemar.selfstorage.payment.application.PaymentSessionCloser;

/**
 * Spec 1.1 › "Approve nhưng chưa đặt cọc, và đã có người khác đặt cọc": một khoang vừa được trả cọc thì mọi hoá
 * đơn cọc {@code Unpaid} khác trỏ khoang đó bị chặn <b>ngay</b> — không đợi khách kia bấm thanh toán mới phát hiện.
 *
 * <ul>
 *   <li>Hoá đơn → {@code Canceled} (audit {@code INVOICE_CANCELED}, lý do "Khoang đã có người đặt cọc trước"); phiên
 *       thanh toán còn mở (nếu có) bị huỷ ở cổng sau commit.</li>
 *   <li>Đơn không huỷ: FM nhận {@code PROPOSAL_REPROPOSAL_REQUIRED}, khách nhận {@code PROPOSAL_UNIT_TAKEN}
 *       ("Khoang [mã] đã có người đặt cọc trước. Cơ sở đang tìm khoang khác…"). Đơn đã cọc (hoá đơn bổ sung khi đổi
 *       khoang) bật {@code needs_reproposal}.</li>
 * </ul>
 *
 * <p>Chạy {@code AFTER_COMMIT} + {@code REQUIRES_NEW} của bước chốt cọc: lúc này khoá khoang đã nhả, khoá theo thứ
 * tự đơn → hoá đơn như mọi luồng khác nên không deadlock với khách đang bấm thanh toán.
 */
@Component
class CompetingInvoiceCanceller {

    static final String REASON = "Khoang đã có người đặt cọc trước";
    static final String SESSION_REASON = "Khoang da co nguoi dat coc";

    private final InvoiceRepository invoiceRepository;
    private final RentalOrderRepository orderRepository;
    private final StorageUnitQuery storageUnitQuery;
    private final AuditRecorder auditRecorder;
    private final OrderNotifier orderNotifier;
    private final PaymentSessionCloser sessionCloser;

    CompetingInvoiceCanceller(InvoiceRepository invoiceRepository, RentalOrderRepository orderRepository,
                              StorageUnitQuery storageUnitQuery, AuditRecorder auditRecorder,
                              OrderNotifier orderNotifier, PaymentSessionCloser sessionCloser) {
        this.invoiceRepository = invoiceRepository;
        this.orderRepository = orderRepository;
        this.storageUnitQuery = storageUnitQuery;
        this.auditRecorder = auditRecorder;
        this.orderNotifier = orderNotifier;
        this.sessionCloser = sessionCloser;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void on(UnitReservedByDeposit event) {
        String unitCode = storageUnitQuery.find(event.unitId()).map(StorageUnitView::code).orElse("");
        List<Long> canceled = new ArrayList<>();
        for (Long invoiceId : invoiceRepository.findUnpaidDepositIdsByUnitId(event.unitId())) {
            Long orderId = invoiceRepository.findOrderIdById(invoiceId).orElse(null);
            if (orderId == null || orderId.equals(event.orderId())) {
                continue;
            }
            RentalOrder order = orderRepository.findByIdForUpdate(orderId).orElse(null);
            Invoice invoice = invoiceRepository.findByIdForUpdate(invoiceId).orElse(null);
            if (order == null || invoice == null || !invoice.isUnpaid() || order.getStatus().isClosed()) {
                continue;
            }
            invoice.cancel();
            canceled.add(invoiceId);
            auditRecorder.record(new AuditEntry(AuditAction.INVOICE_CANCELED, AuditEntityType.INVOICE,
                    String.valueOf(invoiceId), Map.of("status", InvoiceStatus.UNPAID.dbValue()),
                    Map.of("status", invoice.getStatus().dbValue()), REASON, null));
            if (order.getStatus() != RentalOrderStatus.PENDING) {
                order.requireReproposal();
            }
            Map<String, String> values = Map.of("orderCode", OrderNotifier.orderCode(order), "unitCode", unitCode);
            orderNotifier.notifyFacilityManager(order.getId(), NotificationType.PROPOSAL_REPROPOSAL_REQUIRED, values);
            orderNotifier.notifyCustomer(order, NotificationType.PROPOSAL_UNIT_TAKEN, values);
        }
        sessionCloser.closeAfterCommit(canceled, SESSION_REASON);
    }
}
