/**
 * Public API của payment cho module khác: khởi tạo một lượt thanh toán và nghe kết quả.
 *
 * <p>Module nghiệp vụ sở hữu hóa đơn gọi {@link vn.lemar.selfstorage.payment.application.PaymentService}
 * để tạo URL thanh toán, rồi nghe {@code PaymentSucceeded}/{@code PaymentFailed} —
 * payment không bao giờ gọi ngược sang module nghiệp vụ.
 */
@org.springframework.modulith.NamedInterface("application")
package vn.lemar.selfstorage.payment.application;
