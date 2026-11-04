package vn.lemar.selfstorage.booking.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import vn.lemar.selfstorage.identity.application.AdminRecipient;
import vn.lemar.selfstorage.identity.application.AdminRecipientQuery;
import vn.lemar.selfstorage.notification.application.NotificationService;
import vn.lemar.selfstorage.payment.application.PaymentReconciliationCaseService;
import vn.lemar.selfstorage.payment.application.event.PaymentPendingReviewRequired;

class PaymentPendingReviewListenerTest {

    private final AdminRecipientQuery admins = mock(AdminRecipientQuery.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final PaymentReconciliationCaseService cases = mock(PaymentReconciliationCaseService.class);
    private final PaymentPendingReviewListener listener =
            new PaymentPendingReviewListener(admins, notifications, cases);

    private final PaymentPendingReviewRequired event = new PaymentPendingReviewRequired(
            10L, 1L, 7L, "TXN1", new BigDecimal("10000"), Instant.now().minusSeconds(1200));

    @Test
    void notifiesEveryActiveAdminAndMarksTheCase() {
        when(admins.activeAdmins()).thenReturn(List.of(
                new AdminRecipient(1L, "admin1@lemar.vn"),
                new AdminRecipient(2L, "admin2@lemar.vn")));

        listener.onPendingReview(event);

        verify(notifications, times(2)).notify(any(), any(), any(), any(), any(), any());
        verify(cases).markNotified(10L);
    }

    @Test
    void noAdminMeansNoNotificationAndNoMark() {
        when(admins.activeAdmins()).thenReturn(List.of());

        listener.onPendingReview(event);

        verify(notifications, never()).notify(any(), any(), any(), any(), any(), any());
        verify(cases, never()).markNotified(any());
    }

    @Test
    void notificationFailureDoesNotBreakTheListenerOrMarkTheCase() {
        when(admins.activeAdmins()).thenReturn(List.of(new AdminRecipient(1L, "admin@lemar.vn")));
        doThrow(new IllegalStateException("smtp down")).when(notifications)
                .notify(any(), any(), any(), any(), any(), any());

        assertThatCode(() -> listener.onPendingReview(event)).doesNotThrowAnyException();
        verify(cases, never()).markNotified(any());
    }
}
