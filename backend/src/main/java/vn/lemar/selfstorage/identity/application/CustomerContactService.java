package vn.lemar.selfstorage.identity.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.lemar.selfstorage.identity.application.exception.AccountNotFoundException;

/**
 * Public API cho module khác sửa họ tên / số điện thoại của một tài khoản — vd FS sửa thông tin bên thuê trong phiên
 * bàn giao trước khi ký hợp đồng. Không ghi audit: caller ghi kèm ngữ cảnh và lý do của mình. Email không sửa ở đây
 * (là email đăng nhập đã xác minh).
 */
@Service
public class CustomerContactService {

    private final JdbcTemplate jdbc;

    public CustomerContactService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Họ tên và số điện thoại đang lưu trên tài khoản ({@code phone} có thể {@code null}). */
    public record Contact(String name, String phone) {
    }

    @Transactional(readOnly = true)
    public Contact get(Long accountId) {
        return jdbc.query("select name, phone from accounts where id = ?",
                        (rs, i) -> new Contact(rs.getString("name"), rs.getString("phone")), accountId)
                .stream().findFirst().orElseThrow(AccountNotFoundException::new);
    }

    /** Ghi đè họ tên + số điện thoại; giá trị đã được caller kiểm (không trống, đúng định dạng). */
    @Transactional
    public Contact update(Long accountId, String name, String phone) {
        if (jdbc.update("update accounts set name = ?, phone = ? where id = ?", name, phone, accountId) == 0) {
            throw new AccountNotFoundException();
        }
        return new Contact(name, phone);
    }
}
