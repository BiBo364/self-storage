package vn.lemar.selfstorage.booking.application;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import vn.lemar.selfstorage.identity.application.AdminRecipient;
import vn.lemar.selfstorage.identity.application.AdminRecipientQuery;
import vn.lemar.selfstorage.notification.application.NotificationRecipient;
import vn.lemar.selfstorage.notification.application.NotificationService;
import vn.lemar.selfstorage.notification.application.NotificationType;
import vn.lemar.selfstorage.payment.application.PaymentReconciliationCaseService;
import vn.lemar.selfstorage.payment.application.event.PaymentPendingReviewRequired;

/** Gửi alert case Pending cho ADMIN sau khi case đã commit; lỗi notification không rollback case. */
@Component
class PaymentPendingReviewListener {

    private static final Logger LOG = LoggerFactory.getLogger(PaymentPendingReviewListener.class);

    private final AdminRecipientQuery admins;
    private final NotificationService notifications;
    private final PaymentReconciliationCaseService cases;

    PaymentPendingReviewListener(AdminRecipientQuery admins, NotificationService notifications,
                                 PaymentReconciliationCaseService cases) {
        this.admins = admins;
        this.notifications = notifications;
        this.cases = cases;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onPendingReview(PaymentPendingReviewRequired event) {
        var recipients = admins.activeAdmins();
        if (recipients.isEmpty()) {
            LOG.warn("Không có ADMIN active để nhận alert Pending {}", event.vnpTxnRef());
            return;
        }
        long ageMinutes = Math.max(0, Duration.between(event.createdAt(), Instant.now()).toMinutes());
        boolean delivered = false;
        for (AdminRecipient admin : recipients) {
            try {
                notifications.notify(admin.id(), admin.email(), NotificationRecipient.ADMIN,
                        NotificationType.PAYMENT_PENDING_REVIEW_REQUIRED,
                        Map.of("vnpTxnRef", event.vnpTxnRef(),
                                "invoiceId", String.valueOf(event.invoiceId()),
                                "amount", event.amount().toPlainString(),
                                "pendingAgeMinutes", String.valueOf(ageMinutes)),
                        null);
                delivered = true;
            } catch (RuntimeException e) {
                LOG.error("Không gửi được alert Pending {} cho ADMIN {}",
                        event.vnpTxnRef(), admin.email(), e);
            }
        }
        if (delivered) {
            cases.markNotified(event.caseId());
        }
    }
}
