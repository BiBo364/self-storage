package vn.lemar.selfstorage.booking.application;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.lemar.selfstorage.audit.application.AuditEntry;
import vn.lemar.selfstorage.audit.application.AuditRecorder;
import vn.lemar.selfstorage.audit.domain.AuditAction;
import vn.lemar.selfstorage.audit.domain.AuditEntityType;
import vn.lemar.selfstorage.booking.application.exception.BookingConflictException;
import vn.lemar.selfstorage.booking.application.exception.BookingNotFoundException;
import vn.lemar.selfstorage.booking.application.exception.BookingValidationException;
import vn.lemar.selfstorage.booking.domain.Invoice;
import vn.lemar.selfstorage.booking.repository.InvoiceRepository;
import vn.lemar.selfstorage.identity.application.Access;
import vn.lemar.selfstorage.identity.application.CurrentAccountProvider;
import vn.lemar.selfstorage.payment.application.PaymentService;
import vn.lemar.selfstorage.payment.application.PaymentService.RefundView;

/**
 * FM ghi nhận khoản hoàn tiền đã chuyển cho khách (payOS không có API hoàn tiền — FM chuyển khoản thủ công rồi ghi
 * lại). Dùng cho mọi trường hợp hệ thống báo "cần hoàn": hoàn cọc khi trả kho, cọc thừa khi đổi khoang, tiền vào
 * sau khi hoá đơn đã đóng, huỷ sau khi đã trả tháng đầu. Quyền {@code payment_transaction.refund} theo cơ sở của
 * hoá đơn.
 */
@Service
public class ManualRefundService {

    static final String PERMISSION = "payment_transaction.refund";
    static final int MAX_NOTE = 500;

    private final InvoiceRepository invoiceRepository;
    private final PaymentService paymentService;
    private final Access access;
    private final CurrentAccountProvider currentAccount;
    private final AuditRecorder auditRecorder;

    ManualRefundService(InvoiceRepository invoiceRepository, PaymentService paymentService, Access access,
                        CurrentAccountProvider currentAccount, AuditRecorder auditRecorder) {
        this.invoiceRepository = invoiceRepository;
        this.paymentService = paymentService;
        this.access = access;
        this.currentAccount = currentAccount;
        this.auditRecorder = auditRecorder;
    }

    /** Lịch sử hoàn + số còn hoàn được. */
    public record RefundSummary(Long invoiceId, BigDecimal refundable, List<RefundView> refunds) {
    }

    @Transactional(readOnly = true)
    public RefundSummary summary(Long invoiceId) {
        requireAccess(invoiceId);
        return new RefundSummary(invoiceId, paymentService.refundable(invoiceId), paymentService.refunds(invoiceId));
    }

    @Transactional
    public RefundSummary record(Long invoiceId, BigDecimal amount, String note) {
        if (amount == null || amount.signum() <= 0) {
            throw new BookingValidationException("Số tiền hoàn phải lớn hơn 0");
        }
        if (note == null || note.isBlank() || note.length() > MAX_NOTE) {
            throw new BookingValidationException("Cần ghi chú hoàn tiền (lý do, mã chuyển khoản), tối đa 500 ký tự");
        }
        requireAccess(invoiceId);
        Invoice invoice = invoiceRepository.findByIdForUpdate(invoiceId)
                .orElseThrow(() -> new BookingNotFoundException("Không tìm thấy hoá đơn"));
        RefundView refund;
        try {
            refund = paymentService.recordManualRefund(invoice.getId(), amount, note.strip());
        } catch (IllegalStateException e) {
            throw new BookingConflictException(e.getMessage());
        }
        Map<String, Object> newValue = new HashMap<>();
        newValue.put("invoiceId", invoiceId);
        newValue.put("invoiceCode", invoice.getCode());
        newValue.put("amount", amount);
        auditRecorder.record(new AuditEntry(AuditAction.MANUAL_REFUND_RECORDED, AuditEntityType.PAYMENT_TRANSACTION,
                String.valueOf(refund.id()), null, newValue, note.strip(), currentAccount.getCurrentAccountId()));
        return summary(invoiceId);
    }

    private void requireAccess(Long invoiceId) {
        Long facilityId = invoiceRepository.findFacilityIdOfInvoice(invoiceId)
                .orElseThrow(() -> new BookingNotFoundException("Không tìm thấy hoá đơn"));
        access.can(PERMISSION, facilityId);
    }
}
