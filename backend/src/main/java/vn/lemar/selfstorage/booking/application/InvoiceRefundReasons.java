package vn.lemar.selfstorage.booking.application;

/** Mã lý do của {@code invoices.refund_reason} (tối đa 50 ký tự) — hàng đợi hoàn tiền hiển thị theo mã này. */
public final class InvoiceRefundReasons {

    /** Tiền cọc đã vào nhưng khoang không còn giữ được. */
    public static final String UNIT_UNAVAILABLE = "UNIT_UNAVAILABLE";
    /** Tiền vào sau khi hoá đơn đã {@code Canceled}/{@code Expired}. */
    public static final String INVOICE_CLOSED = "INVOICE_CLOSED";
    /** Đổi sang khoang có cọc thấp hơn — cọc dư. */
    public static final String UNIT_CHANGE_EXCESS = "UNIT_CHANGE_EXCESS";
    /** Đơn hết hạn khi đã trả cọc mà chưa xếp được khoang. */
    public static final String DEPOSIT_UNSERVED = "DEPOSIT_UNSERVED";
    /** Đã trả tháng đầu nhưng bàn giao bị huỷ. */
    public static final String HANDOVER_CANCELED = "HANDOVER_CANCELED";
    /** Hoàn cọc khi trả kho (cọc trừ các khoản phát sinh). */
    public static final String CHECKOUT_DEPOSIT = "CHECKOUT_DEPOSIT";

    private InvoiceRefundReasons() {
    }
}
