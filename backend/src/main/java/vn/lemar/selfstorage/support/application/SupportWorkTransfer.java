package vn.lemar.selfstorage.support.application;

import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import vn.lemar.selfstorage.identity.application.StaffWorkTransfer;

/** Yêu cầu hỗ trợ đang giao ({@code Assigned} / đang chuyển khoang) cho FS nghỉ việc → FS thay thế. */
@Component
class SupportWorkTransfer implements StaffWorkTransfer {

    private final JdbcTemplate jdbc;

    SupportWorkTransfer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<String, Integer> transfer(Long fromStaffId, Long toStaffId) {
        int moved = jdbc.update("update support_requests set assigned_staff_id = ?, updated_at = now() "
                + "where assigned_staff_id = ? and status in ('Assigned', 'Transferring')", toStaffId, fromStaffId);
        return moved == 0 ? Map.of() : Map.of("yêu cầu hỗ trợ", moved);
    }
}
