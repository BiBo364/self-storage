package vn.lemar.selfstorage.staffing.application;

import java.util.Map;
import org.springframework.stereotype.Component;
import vn.lemar.selfstorage.identity.application.AccountQueryService;
import vn.lemar.selfstorage.notification.application.NotificationRecipient;
import vn.lemar.selfstorage.notification.application.NotificationService;
import vn.lemar.selfstorage.notification.application.NotificationType;

/** Thông báo nhân sự (in-app + email) — không gắn đơn thuê. */
@Component
class StaffNotifier {

    private final NotificationService notificationService;
    private final AccountQueryService accountQuery;

    StaffNotifier(NotificationService notificationService, AccountQueryService accountQuery) {
        this.notificationService = notificationService;
        this.accountQuery = accountQuery;
    }

    void notify(Long accountId, NotificationRecipient recipient, NotificationType type, Map<String, ?> values) {
        notificationService.notify(accountId, accountQuery.requireEmailById(accountId), recipient, type, values,
                null);
    }
}
