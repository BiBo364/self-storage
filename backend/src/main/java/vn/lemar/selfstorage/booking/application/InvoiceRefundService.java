package vn.lemar.selfstorage.booking.application;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.lemar.selfstorage.audit.application.AuditEntry;
import vn.lemar.selfstorage.audit.application.AuditRecorder;
import vn.lemar.selfstorage.audit.domain.AuditAction;
import vn.lemar.selfstorage.audit.domain.AuditEntityType;
import vn.lemar.selfstorage.booking.domain.Invoice;
import vn.lemar.selfstorage.booking.domain.InvoiceStatus;
import vn.lemar.selfstorage.booking.repository.InvoiceRepository;
import vn.lemar.selfstorage.payment.application.PaymentService;

/**
 * Đánh dấu hoá đơn cần hoàn tiền thủ công ({@code invoices.refund_status = Required}) — một cơ chế cho mọi ca "tiền
 * đã vào phải trả lại": khoang mất sau khi cọc, tiền vào hoá đơn đã đóng, cọc dư khi đổi khoang, đơn hết hạn khi đã
 * cọc, tháng đầu đã trả mà bàn giao huỷ, hoàn cọc khi trả kho. FM ghi nhận khoản đã chuyển qua
 * {@link ManualRefundService}; hàng đợi hoàn tiền của FM / ADMIN đọc cờ này.
 *
 * <p>Chạy trong transaction của caller; mỗi hoá đơn bị đánh dấu ghi một audit {@code INVOICE_REFUND_REQUIRED}.
 */
@Service
public class InvoiceRefundService {

    private static final Logger LOG = LoggerFactory.getLogger(InvoiceRefundService.class);

    private final InvoiceRepository invoiceRepository;
    private final PaymentService paymentService;
    private final AuditRecorder auditRecorder;

    InvoiceRefundService(InvoiceRepository invoiceRepository, PaymentService paymentService,
                         AuditRecorder auditRecorder) {
        this.invoiceRepository = invoiceRepository;
        this.paymentService = paymentService;
        this.auditRecorder = auditRecorder;
    }

    /** Cả số tiền của hoá đơn phải trả lại (hoá đơn đã được caller khoá). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireFull(Invoice invoice, String reason, Long actorAccountId) {
        String before = invoice.getRefundStatus().dbValue();
        invoice.markRefundRequired(reason);
        record(invoice, before, reason, actorAccountId);
    }

    /**
     * Phải trả lại {@code amount} tiền cọc của đơn: chia vào các hoá đơn {@code Deposit}/{@code DepositSupplement} đã
     * {@code Paid}, hoá đơn mới nhất trước, mỗi hoá đơn tối đa số còn hoàn được qua cổng. Phần không gắn được vào
     * hoá đơn nào (cọc không thu qua cổng) chỉ ghi log — FM vẫn nhận thông báo từ caller.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireDeposit(Long orderId, BigDecimal amount, String reason, Long actorAccountId) {
        if (amount == null || amount.signum() <= 0) {
            return;
        }
        BigDecimal remaining = amount;
        List<Invoice> paid = invoiceRepository.findByOrderIdAndStatus(orderId, InvoiceStatus.PAID).stream()
                .filter(Invoice::isDepositKind)
                .sorted(Comparator.comparing(Invoice::getId).reversed())
                .toList();
        for (Invoice found : paid) {
            if (remaining.signum() <= 0) {
                break;
            }
            BigDecimal refundable = paymentService.refundable(found.getId());
            if (refundable.signum() <= 0) {
                continue;
            }
            BigDecimal part = refundable.min(remaining);
            Invoice invoice = invoiceRepository.findByIdForUpdate(found.getId()).orElse(found);
            String before = invoice.getRefundStatus().dbValue();
            invoice.markRefundRequired(reason, part);
            record(invoice, before, reason, actorAccountId);
            remaining = remaining.subtract(part);
        }
        if (remaining.signum() > 0) {
            LOG.warn("Đơn {} cần hoàn {} nhưng hoá đơn cọc chỉ còn hoàn được tới {} qua cổng", orderId, amount,
                    amount.subtract(remaining));
        }
    }

    private void record(Invoice invoice, String before, String reason, Long actorAccountId) {
        Map<String, Object> newValue = new HashMap<>();
        newValue.put("refundStatus", invoice.getRefundStatus().dbValue());
        newValue.put("refundReason", reason);
        newValue.put("refundAmount", invoice.refundOwed());
        auditRecorder.record(new AuditEntry(AuditAction.INVOICE_REFUND_REQUIRED, AuditEntityType.INVOICE,
                String.valueOf(invoice.getId()), Map.of("refundStatus", before), newValue, reason, actorAccountId));
    }
}
