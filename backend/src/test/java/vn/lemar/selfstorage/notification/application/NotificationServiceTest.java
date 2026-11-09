package vn.lemar.selfstorage.notification.application;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.test.util.ReflectionTestUtils;

import vn.lemar.selfstorage.notification.EmailSender;
import vn.lemar.selfstorage.notification.domain.Notification;
import vn.lemar.selfstorage.notification.domain.OrderNotification;
import vn.lemar.selfstorage.notification.repository.NotificationRepository;
import vn.lemar.selfstorage.notification.repository.OrderNotificationRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private OrderNotificationRepository orderNotificationRepository;

    @Mock
    private EmailSender emailSender;

    private NotificationService notificationService;

    @BeforeEach
    void setUp() {
        notificationService = new NotificationService(
                notificationRepository, orderNotificationRepository, new NotificationTemplateCatalog(), emailSender);
    }

    private void stubSaveAssignsId(long id) {
        when(notificationRepository.save(any(Notification.class))).thenAnswer(invocation -> {
            Notification notification = invocation.getArgument(0);
            ReflectionTestUtils.setField(notification, "id", id);
            return notification;
        });
    }

    @Test
    void notifyRendersTemplateAndSendsEmail() {
        stubSaveAssignsId(1L);
        // Template duyệt có thêm loại + kích thước khoang (spec 1.1).
        Map<String, Object> values = Map.of("unitCode", "A-01", "unitType", "Small (1 × 2 × 2.2 m)",
                "proposalLink", "https://lemar.vn/p/1");

        Notification result = notificationService.notify(1L, "customer1@lemar.vn", NotificationRecipient.CUSTOMER,
                NotificationType.RENTAL_REQUEST_APPROVED, values, null);

        assertThat(result.getType()).isEqualTo("RENTAL_REQUEST_APPROVED");
        assertThat(result.getTitle()).isEqualTo("Yêu cầu thuê kho đã được duyệt");
        assertThat(result.getBody()).contains("A-01").contains("https://lemar.vn/p/1");
        assertThat(result.getCreatedAt()).isNotNull();
        assertThat(result.getReadAt()).isNull();
        ArgumentCaptor<String> htmlCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailSender).sendHtml(
                eq("customer1@lemar.vn"), eq("Yêu cầu thuê kho đã được duyệt"), htmlCaptor.capture());
        assertThat(htmlCaptor.getValue()).contains("LEMAR SELF STORAGE").contains("A-01");
    }

    @Test
    void notifyRendersRecipientSpecificTemplate() {
        stubSaveAssignsId(2L);
        Map<String, Object> values = Map.of(
                "orderCode", "O-1", "appointmentTime", "10:00 06/10", "facilityAddress", "Q1");

        Notification fmNotification = notificationService.notify(2L, "fm@lemar.vn", NotificationRecipient.FM,
                NotificationType.APPOINTMENT_CREATED, values, null);
        Notification customerNotification = notificationService.notify(3L, "customer@lemar.vn",
                NotificationRecipient.CUSTOMER, NotificationType.APPOINTMENT_CREATED, values, null);

        assertThat(fmNotification.getTitle()).isEqualTo("Có lịch check-in mới cần phân công");
        assertThat(customerNotification.getTitle()).isEqualTo("Lịch hẹn check-in đã được tạo");
    }

    @Test
    void notifyRejectsMissingPlaceholder() {
        assertThatThrownBy(() -> notificationService.notify(1L, "customer1@lemar.vn",
                NotificationRecipient.CUSTOMER, NotificationType.RENTAL_REQUEST_APPROVED,
                Map.of("unitCode", "A-01", "unitType", "Small"), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("proposalLink");

        verifyNoInteractions(notificationRepository, orderNotificationRepository, emailSender);
    }

    @Test
    void notifyRejectsRecipientOutsideCatalog() {
        Map<String, Object> values = Map.of("unitCode", "A-01", "reason", "hết khoang");

        assertThatThrownBy(() -> notificationService.notify(1L, "customer1@lemar.vn",
                NotificationRecipient.CUSTOMER, NotificationType.PROPOSAL_REJECTED, values, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PROPOSAL_REJECTED/CUSTOMER");

        verifyNoInteractions(notificationRepository, orderNotificationRepository, emailSender);
    }

    @Test
    void notifyCreatesOrderNotificationLink() {
        stubSaveAssignsId(10L);
        Map<String, Object> values = Map.of("orderCode", "O-1", "expiresAt", "2026-11-01", "daysLeft", 3);

        notificationService.notify(1L, "customer1@lemar.vn", NotificationRecipient.CUSTOMER,
                NotificationType.RENTAL_ORDER_EXPIRING_SOON, values, 7L);

        ArgumentCaptor<OrderNotification> captor = ArgumentCaptor.forClass(OrderNotification.class);
        verify(orderNotificationRepository).save(captor.capture());
        assertThat(captor.getValue().getOrderId()).isEqualTo(7L);
        assertThat(captor.getValue().getNotificationId()).isEqualTo(10L);
    }

    @Test
    void createOtherStoresManualContent() {
        stubSaveAssignsId(11L);

        Notification result = notificationService.createOther(1L, "customer1@lemar.vn",
                "Bảo trì khẩn cấp", "Cơ sở tạm đóng ngày mai");

        assertThat(result.getType()).isEqualTo("OTHER");
        assertThat(result.getTitle()).isEqualTo("Bảo trì khẩn cấp");
        verifyNoInteractions(orderNotificationRepository);
        verify(emailSender).sendHtml(eq("customer1@lemar.vn"), eq("Bảo trì khẩn cấp"), any());
    }

    @Test
    void emailFailureDoesNotRollbackWebNotification() {
        stubSaveAssignsId(1L);
        when(emailSender.sendHtml(any(), any(), any())).thenReturn(false);

        Notification result = notificationService.notify(1L, "customer1@lemar.vn", NotificationRecipient.CUSTOMER,
                NotificationType.DEPOSIT_PAYMENT_SUCCEEDED, Map.of("orderCode", "O-1"), null);

        assertThat(result.getType()).isEqualTo("DEPOSIT_PAYMENT_SUCCEEDED");
        verify(notificationRepository).save(any(Notification.class));
        verify(emailSender).sendHtml(eq("customer1@lemar.vn"), any(), any());
    }

    @Test
    void markReadSetsReadAt() {
        Notification notification = new Notification(1L, "TYPE", "Tiêu đề", "Nội dung");
        when(notificationRepository.findByIdAndAccountId(10L, 1L)).thenReturn(Optional.of(notification));

        Notification result = notificationService.markRead(1L, 10L);

        assertThat(result.getReadAt()).isNotNull();
        verify(notificationRepository).save(notification);
    }

    @Test
    void markReadIsIdempotent() {
        Notification notification = new Notification(1L, "TYPE", "Tiêu đề", "Nội dung");
        notification.markRead();
        when(notificationRepository.findByIdAndAccountId(10L, 1L)).thenReturn(Optional.of(notification));

        notificationService.markRead(1L, 10L);

        verify(notificationRepository, never()).save(any(Notification.class));
    }

    @Test
    void markAllReadUpdatesUnreadOnly() {
        Notification first = new Notification(1L, "TYPE", "A", "a");
        Notification second = new Notification(1L, "TYPE", "B", "b");
        when(notificationRepository.findByAccountIdAndReadAtIsNull(1L)).thenReturn(List.of(first, second));

        int marked = notificationService.markAllRead(1L);

        assertThat(marked).isEqualTo(2);
        assertThat(first.getReadAt()).isNotNull();
        assertThat(second.getReadAt()).isNotNull();
        verify(notificationRepository).saveAll(List.of(first, second));
    }

    @Test
    void listForAccountFiltersByReadState() {
        when(notificationRepository.findByAccountIdAndReadAtIsNullOrderByCreatedAtDesc(eq(1L), any()))
                .thenReturn(Page.empty());

        notificationService.listForAccount(1L, false, 0, 20);

        verify(notificationRepository).findByAccountIdAndReadAtIsNullOrderByCreatedAtDesc(eq(1L), any());
        verify(notificationRepository, never()).findByAccountIdOrderByCreatedAtDesc(any(), any());
    }

    @Test
    void listForAccountWithoutFilterReturnsAll() {
        when(notificationRepository.findByAccountIdOrderByCreatedAtDesc(eq(1L), any())).thenReturn(Page.empty());

        notificationService.listForAccount(1L, null, 0, 20);

        verify(notificationRepository).findByAccountIdOrderByCreatedAtDesc(eq(1L), any());
    }

    @Test
    void listForAccountReadFilterUsesReadFinder() {
        when(notificationRepository.findByAccountIdAndReadAtIsNotNullOrderByCreatedAtDesc(eq(1L), any()))
                .thenReturn(Page.empty());

        notificationService.listForAccount(1L, true, 0, 20);

        verify(notificationRepository).findByAccountIdAndReadAtIsNotNullOrderByCreatedAtDesc(eq(1L), any());
    }

    @Test
    void notifyRejectsOtherType() {
        assertThatThrownBy(() -> notificationService.notify(1L, "customer1@lemar.vn",
                NotificationRecipient.CUSTOMER, NotificationType.OTHER, Map.of(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("OTHER");
    }

    @Test
    void notifyRejectsNullValues() {
        assertThatThrownBy(() -> notificationService.notify(1L, "customer1@lemar.vn",
                NotificationRecipient.CUSTOMER, NotificationType.RENTAL_REQUEST_APPROVED, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unitCode");
    }

    @Test
    void markAsRead() {
        Notification notification = new Notification(2L, "TYPE", "title", "body");

        notification.markRead();

        assertThat(notification.getReadAt()).isNotNull();
    }

    @Test
    void orderIdsOfMapsOrderLinks() {
        when(orderNotificationRepository.findByNotificationIdIn(List.of(1L, 2L)))
                .thenReturn(List.of(new OrderNotification(7L, 1L)));

        assertThat(notificationService.orderIdsOf(List.of(1L, 2L))).containsExactly(Map.entry(1L, 7L));
    }

    @Test
    void orderIdsOfReturnsEmptyWithoutQueryForEmptyInput() {
        assertThat(notificationService.orderIdsOf(List.of())).isEmpty();

        verifyNoInteractions(orderNotificationRepository);
    }

    @Test
    void orderIdOfReturnsNullWhenNotLinked() {
        when(orderNotificationRepository.findByNotificationId(1L)).thenReturn(Optional.empty());

        assertThat(notificationService.orderIdOf(1L)).isNull();
    }

    @Test
    void orderIdOfReturnsOrderIdWhenLinked() {
        when(orderNotificationRepository.findByNotificationId(1L))
                .thenReturn(Optional.of(new OrderNotification(7L, 1L)));

        assertThat(notificationService.orderIdOf(1L)).isEqualTo(7L);
    }
}
