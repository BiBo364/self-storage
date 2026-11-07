package vn.lemar.selfstorage.notification.application;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.lemar.selfstorage.notification.EmailSender;
import vn.lemar.selfstorage.notification.application.exception.NotificationNotFoundException;
import vn.lemar.selfstorage.notification.domain.Notification;
import vn.lemar.selfstorage.notification.domain.OrderNotification;
import vn.lemar.selfstorage.notification.repository.NotificationRepository;
import vn.lemar.selfstorage.notification.repository.OrderNotificationRepository;

/**
 * Thông báo web + email (A5).
 *
 * <p>Email gửi qua {@link EmailSender} với layout {@link NotificationEmailLayout}: đang trong
 * transaction nên được xếp lịch gửi **sau commit**, rollback thì không gửi. Lỗi gửi chỉ log,
 * KHÔNG rollback việc lưu thông báo web.
 * Nội dung theo template hardcode của {@link NotificationTemplateCatalog}; riêng {@code OTHER}
 * tạo thủ công qua {@link #createOther}. Không import sang module khác; caller tự truyền
 * {@code accountId} và {@code recipientEmail}.
 */
@Service
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final OrderNotificationRepository orderNotificationRepository;
    private final NotificationTemplateCatalog templateCatalog;
    private final EmailSender emailSender;

    public NotificationService(
            NotificationRepository notificationRepository,
            OrderNotificationRepository orderNotificationRepository,
            NotificationTemplateCatalog templateCatalog,
            EmailSender emailSender) {
        this.notificationRepository = notificationRepository;
        this.orderNotificationRepository = orderNotificationRepository;
        this.templateCatalog = templateCatalog;
        this.emailSender = emailSender;
    }

    /**
     * Tạo notification theo template của {@code type} và gửi email.
     *
     * @param orderId nếu khác null thì ghi thêm liên kết {@link OrderNotification}
     * @throws IllegalArgumentException thiếu giá trị placeholder hoặc type không có template
     */
    @Transactional
    public Notification notify(Long accountId, String recipientEmail, NotificationRecipient recipient,
            NotificationType type, Map<String, ?> values, Long orderId) {
        NotificationTemplateCatalog.Template template = templateCatalog.resolve(type, recipient);
        String title = template.renderTitle(values);
        String body = template.renderBody(values);
        return persist(accountId, type, title, body, recipientEmail, orderId);
    }

    /** Tạo notification {@code OTHER} với title/body thủ công — không dùng template. */
    @Transactional
    public Notification createOther(Long accountId, String recipientEmail, String title, String body) {
        return persist(accountId, NotificationType.OTHER, title, body, recipientEmail, null);
    }

    /** Danh sách notification của account, mới nhất trước; {@code isRead} null = tất cả. */
    @Transactional(readOnly = true)
    public List<Notification> listForAccount(Long accountId, Boolean isRead, int page, int size) {
        PageRequest pageable = PageRequest.of(page, size);
        if (isRead == null) {
            return notificationRepository.findByAccountIdOrderByCreatedAtDesc(accountId, pageable).getContent();
        }
        if (isRead) {
            return notificationRepository
                    .findByAccountIdAndReadAtIsNotNullOrderByCreatedAtDesc(accountId, pageable)
                    .getContent();
        }
        return notificationRepository
                .findByAccountIdAndReadAtIsNullOrderByCreatedAtDesc(accountId, pageable)
                .getContent();
    }

    /** Notification thuộc đúng account — không thuộc thì coi như không tồn tại. */
    @Transactional(readOnly = true)
    public Notification getForAccount(Long accountId, Long notificationId) {
        return notificationRepository.findByIdAndAccountId(notificationId, accountId)
                .orElseThrow(NotificationNotFoundException::new);
    }

    /** Đánh dấu đã đọc, idempotent. */
    @Transactional
    public Notification markRead(Long accountId, Long notificationId) {
        Notification notification = getForAccount(accountId, notificationId);
        if (notification.getReadAt() == null) {
            notification.markRead();
            notificationRepository.save(notification);
        }
        return notification;
    }

    /** Đánh dấu tất cả chưa đọc của account; trả số bản ghi vừa cập nhật. */
    @Transactional
    public int markAllRead(Long accountId) {
        List<Notification> unread = notificationRepository.findByAccountIdAndReadAtIsNull(accountId);
        unread.forEach(Notification::markRead);
        notificationRepository.saveAll(unread);
        return unread.size();
    }

    @Transactional(readOnly = true)
    public long unreadCount(Long accountId) {
        return notificationRepository.countByAccountIdAndReadAtIsNull(accountId);
    }

    /** Đơn đã nhận notification loại này chưa (dedup cron nhắc qua liên kết {@code OrderNotification}). */
    @Transactional(readOnly = true)
    public boolean hasOrderNotification(Long orderId, NotificationType type) {
        return orderNotificationRepository.existsForOrderAndType(orderId, type.name());
    }

    /** Map notificationId → orderId cho các notification có liên kết đơn. */
    @Transactional(readOnly = true)
    public Map<Long, Long> orderIdsOf(Collection<Long> notificationIds) {
        if (notificationIds.isEmpty()) {
            return Map.of();
        }
        return orderNotificationRepository.findByNotificationIdIn(notificationIds).stream()
                .collect(Collectors.toMap(OrderNotification::getNotificationId, OrderNotification::getOrderId));
    }

    /** orderId của notification — null nếu không gắn đơn. */
    @Transactional(readOnly = true)
    public Long orderIdOf(Long notificationId) {
        return orderNotificationRepository.findByNotificationId(notificationId)
                .map(OrderNotification::getOrderId)
                .orElse(null);
    }

    private Notification persist(Long accountId, NotificationType type, String title, String body,
            String recipientEmail, Long orderId) {
        Notification notification =
                notificationRepository.save(new Notification(accountId, type.name(), title, body));
        if (orderId != null) {
            orderNotificationRepository.save(new OrderNotification(orderId, notification.getId()));
        }
        emailSender.sendHtml(recipientEmail, title, NotificationEmailLayout.wrap(title, body));
        return notification;
    }
}
