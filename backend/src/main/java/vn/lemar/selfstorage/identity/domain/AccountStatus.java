package vn.lemar.selfstorage.identity.domain;

public enum AccountStatus {
    ACTIVE,
    BANNED,
    /** Nhân viên đã nghỉ việc (được thay bằng tài khoản khác) — không đăng nhập được, giữ lịch sử. */
    INACTIVE
}
