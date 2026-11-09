package vn.lemar.selfstorage.identity.application.exception;

/**
 * Email đang có đăng ký chờ xác minh — đăng ký lại không được ghi đè mật khẩu/hồ sơ đã lưu (ai biết email cũng gửi
 * được register, ghi đè thì chủ email xác minh xong lại nhận mật khẩu của người khác). 409 như email đã có account.
 */
public class RegistrationPendingException extends EmailAlreadyExistsException {
    public RegistrationPendingException() {
        super("Email này đang chờ xác minh. Nhập mã đã gửi tới email hoặc dùng Gửi lại mã.");
    }
}
