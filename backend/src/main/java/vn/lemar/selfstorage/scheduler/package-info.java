/**
 * Module trống, giữ chỗ cho hạ tầng lịch chạy dùng chung. Các job hiện nằm trong module nghiệp vụ của chúng và chạy
 * nhờ {@code @EnableScheduling} ở {@code booking.config.BookingSchedulingConfig}; chưa có khoá phân tán nên chỉ chạy
 * một instance.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Scheduler")
package vn.lemar.selfstorage.scheduler;
