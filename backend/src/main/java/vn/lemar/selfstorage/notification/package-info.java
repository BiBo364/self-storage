/**
 * Gửi email và thông báo web. Đọc account qua public API của identity (mọi module
 * đọc được từ facility, pricing, identity).
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Notification",
        allowedDependencies = {"identity::application"})
package vn.lemar.selfstorage.notification;
