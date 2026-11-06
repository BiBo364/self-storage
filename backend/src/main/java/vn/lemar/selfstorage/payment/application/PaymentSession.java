package vn.lemar.selfstorage.payment.application;

import java.time.Duration;

/** Cửa sổ sống của một phiên thanh toán — dùng chung cho URL gateway và khóa giao dịch. */
public final class PaymentSession {

    /**
     * Thời gian một lượt thanh toán còn hiệu lực. Đặt vào {@code vnp_ExpireDate} khi dựng URL, và
     * cũng là ngưỡng coi một giao dịch {@code Pending} còn đang mở khi kiểm khóa ngắn hạn — hai
     * chỗ phải cùng một con số, lệch nhau thì sinh khoang vừa khóa vừa hết hạn.
     */
    public static final Duration WINDOW = Duration.ofMinutes(15);

    private PaymentSession() {
    }
}
