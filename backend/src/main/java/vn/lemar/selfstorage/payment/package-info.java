/**
 * Cổng thanh toán. Interface PaymentGateway và adapter VNPay / payOS nằm ở đây.
 *
 * <p>Chỉ phụ thuộc audit để ghi vết chính entity của mình ({@code PaymentTransaction}); không gọi
 * sang module nghiệp vụ nào — chiều đó đi bằng event.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Payment", allowedDependencies = {"audit::application"})
package vn.lemar.selfstorage.payment;
