/**
 * Event payment bắn ra cho module nghiệp vụ. Cùng named interface {@code application} với
 * service — module nghe chỉ cần khai một dependency.
 *
 * <p>Event được publish trong transaction xử lý IPN, nên listener bắt buộc
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT)}.
 */
@org.springframework.modulith.NamedInterface("events")
package vn.lemar.selfstorage.payment.application.event;
