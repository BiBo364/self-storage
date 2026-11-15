package vn.lemar.selfstorage.staffing.application;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import vn.lemar.selfstorage.identity.application.StaffWorkTransfer;

/**
 * Ca làm từ hôm nay (giờ VN) của FS nghỉ việc → FS thay thế, để việc vừa chuyển vẫn có ca bao trọn khi phân công lại;
 * đơn nghỉ phép đang chờ của FS cũ bị huỷ.
 */
@Component
class ShiftWorkTransfer implements StaffWorkTransfer {

    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final JdbcTemplate jdbc;

    ShiftWorkTransfer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<String, Integer> transfer(Long fromStaffId, Long toStaffId) {
        int shifts = jdbc.update("update staff_shifts set staff_id = ?, updated_at = now() "
                + "where staff_id = ? and shift_date >= ?", toStaffId, fromStaffId, LocalDate.now(ZONE));
        int leaves = jdbc.update(
                "update staff_leaves set status = 'Canceled' where staff_id = ? and status = 'Pending'", fromStaffId);
        Map<String, Integer> result = new LinkedHashMap<>();
        if (shifts > 0) {
            result.put("ca làm", shifts);
        }
        if (leaves > 0) {
            result.put("đơn nghỉ phép bị huỷ", leaves);
        }
        return result;
    }
}
