package vn.lemar.selfstorage.booking.application.exception;

/**
 * Không cho tiến hành thanh toán cọc. Message lấy nguyên văn từ spec Flow 1.4 vì FE hiển thị
 * thẳng cho khách, không tự dịch lại.
 */
public class DepositNotAllowedException extends RuntimeException {

    public DepositNotAllowedException(String message) {
        super(message);
    }
}
