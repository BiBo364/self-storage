package vn.lemar.selfstorage.notification.application;

/**
 * Nhóm người nhận theo catalog — một số type render nội dung khác nhau theo recipient
 * (ví dụ {@link NotificationType#APPOINTMENT_CREATED}).
 */
public enum NotificationRecipient {
    CUSTOMER,
    FM,
    FS,
    ADMIN
}
