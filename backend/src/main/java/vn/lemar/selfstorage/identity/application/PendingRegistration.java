package vn.lemar.selfstorage.identity.application;

/**
 * Đăng ký chưa hoàn tất: email + hash mật khẩu giữ trong Redis tới khi mã xác minh hết hạn.
 * `Account` chỉ được tạo sau khi khách nhập đúng mã (proposal #152).
 */
record PendingRegistration(String email, String passwordHash, String name, String phone) {

    /** Tương thích test cũ chưa có hồ sơ. */
    PendingRegistration(String email, String passwordHash) {
        this(email, passwordHash, null, null);
    }
}
