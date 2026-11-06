package vn.lemar.selfstorage.booking.application.exception;

/**
 * Khách chưa xác minh email thì không được thực hiện thao tác nghiệp vụ trong luồng thuê kho
 * (spec Flow 1 §1.2). Đặt cọc là case enforce đầu tiên.
 */
public class EmailNotVerifiedException extends RuntimeException {

    public EmailNotVerifiedException() {
        super("Vui lòng xác minh email trước khi thực hiện thao tác này");
    }
}
