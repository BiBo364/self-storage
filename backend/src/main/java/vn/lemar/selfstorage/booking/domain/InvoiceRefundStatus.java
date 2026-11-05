package vn.lemar.selfstorage.booking.domain;

/** Trạng thái hoàn tiền của invoice; tách khỏi trạng thái thanh toán. */
public enum InvoiceRefundStatus {
    NONE("None"),
    REQUIRED("Required"),
    PROCESSING("Processing"),
    REFUNDED("Refunded");

    private final String dbValue;

    InvoiceRefundStatus(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    public static InvoiceRefundStatus fromDbValue(String value) {
        for (InvoiceRefundStatus status : values()) {
            if (status.dbValue.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Giá trị refund status không hợp lệ: " + value);
    }
}
