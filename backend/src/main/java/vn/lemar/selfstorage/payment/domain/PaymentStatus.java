package vn.lemar.selfstorage.payment.domain;

/**
 * Trạng thái một lượt giao dịch.
 *
 * <p>Giá trị lưu xuống DB viết kiểu {@code Pending} chứ không phải {@code PENDING} — đây là
 * ràng buộc CHECK của bảng {@code payment_transactions}; {@code V13__payment_duplicate.sql}
 * mở rộng thêm {@code Duplicate}. Việc quy đổi do {@link PaymentStatusConverter} lo.
 */
public enum PaymentStatus {
    PENDING("Pending"),
    FAILED("Failed"),
    SUCCESS("Success"),
    DUPLICATE("Duplicate");

    private final String dbValue;

    PaymentStatus(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    public static PaymentStatus fromDbValue(String value) {
        for (PaymentStatus status : values()) {
            if (status.dbValue.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Giá trị status không hợp lệ: " + value);
    }
}
