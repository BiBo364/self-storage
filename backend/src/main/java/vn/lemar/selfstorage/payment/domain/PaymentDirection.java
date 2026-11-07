package vn.lemar.selfstorage.payment.domain;

/**
 * Chiều giao dịch. Giá trị trong DB đã viết HOA sẵn nên map thẳng được, không cần converter.
 *
 * <p>{@code REFUND}: FM hoàn tiền thủ công (chuyển khoản ngoài hệ thống) rồi ghi nhận lại — luôn {@code Success}.
 */
public enum PaymentDirection {
    PAY,
    REFUND
}
