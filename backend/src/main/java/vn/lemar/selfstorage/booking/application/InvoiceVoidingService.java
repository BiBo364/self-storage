package vn.lemar.selfstorage.booking.application;

import java.util.Map;
import java.util.Optional;
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
import vn.lemar.selfstorage.facility.application.FacilityAccess;
import vn.lemar.selfstorage.identity.application.AccountQueryService;
import vn.lemar.selfstorage.notification.application.NotificationRecipient;
import vn.lemar.selfstorage.notification.application.NotificationService;
import vn.lemar.selfstorage.notification.application.NotificationType;

/**
 * Vô hiệu hóa đơn khi khoang không còn dùng được — spec Flow 1.4 nhánh `Reserved`/`Maintenance`.
 *
 * <p><strong>Chạy trong transaction của caller ({@code MANDATORY}):</strong> nhánh từ chối ở
 * {@link DepositPaymentService} khai {@code noRollbackFor = DepositNotAllowedException.class},
 * nên phần huỷ hoá đơn (và cờ `needs_reproposal`) commit trước khi 409 trả về. Tách bean để giữ
 * audit + notify tách khỏi service mở phiên; không mở transaction riêng vì làm vậy sẽ khoá invoice
 * trong lúc transaction ngoài đang giữ khoang — đảo thứ tự khoá và có thể deadlock.
 */
@Service
public class InvoiceVoidingService {

    private static final Logger LOG = LoggerFactory.getLogger(InvoiceVoidingService.class);

    private final InvoiceRepository invoices;
    private final NotificationService notifications;
    private final FacilityAccess facilities;
    private final AccountQueryService accounts;
    private final AuditRecorder audit;

    public InvoiceVoidingService(InvoiceRepository invoices, NotificationService notifications,
            FacilityAccess facilities, AccountQueryService accounts, AuditRecorder audit) {
        this.invoices = invoices;
        this.notifications = notifications;
        this.facilities = facilities;
        this.accounts = accounts;
        this.audit = audit;
    }

    /** Hóa đơn mất hiệu lực; FM nhận thông báo để đề xuất khoang khác. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void voidForUnavailableUnit(Long invoiceId, String unitCode, Long facilityId, Long actorId) {
        // Khoá row: IPN có thể đang đánh `Paid` cùng lúc. Kiểm trạng thái trong bộ nhớ rồi ghi đè
        // là không đủ — hoá đơn đã thu tiền mà bị huỷ thì tiền thành mồ côi.
        Invoice invoice = invoices.findByIdForUpdate(invoiceId).orElseThrow();
        if (invoice.getStatus() != InvoiceStatus.UNPAID) {
            LOG.warn("Hóa đơn {} không còn Unpaid ({}), bỏ qua việc huỷ",
                    invoiceId, invoice.getStatus().dbValue());
            return;
        }
        invoice.cancel();

        audit.record(new AuditEntry(AuditAction.INVOICE_CANCELED, AuditEntityType.INVOICE,
                String.valueOf(invoice.getId()), Map.of("status", "Unpaid"),
                Map.of("status", "Canceled"), "Khoang " + unitCode + " không còn khả dụng", actorId));

        Optional<Long> manager = facilities.managerAccountId(facilityId);
        if (manager.isEmpty()) {
            // Thiếu người nhận không được chặn việc vô hiệu hóa đơn.
            LOG.warn("Cơ sở {} chưa gán FM, bỏ qua thông báo re-propose cho hóa đơn {}",
                    facilityId, invoiceId);
            return;
        }
        // recipientEmail bắt buộc, thiếu thì SmtpEmailSender bỏ qua gửi mail — backend/AGENTS.md
        // yêu cầu cả email lẫn thông báo trong app.
        notifications.notify(manager.get(), accounts.requireEmailById(manager.get()),
                NotificationRecipient.FM,
                NotificationType.PROPOSAL_REPROPOSAL_REQUIRED,
                Map.of("orderCode", String.valueOf(invoice.getOrderId()), "unitCode", unitCode),
                invoice.getOrderId());
    }
}
