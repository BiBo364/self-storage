package vn.lemar.selfstorage.handover.application;

import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import vn.lemar.selfstorage.identity.application.StaffWorkTransfer;

/** Phiên bàn giao đang dở ({@code IN_PROGRESS}): lịch hẹn của phiên chuyển sang FS thay thế để làm tiếp. */
@Component
class HandoverWorkTransfer implements StaffWorkTransfer {

    private final JdbcTemplate jdbc;

    HandoverWorkTransfer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<String, Integer> transfer(Long fromStaffId, Long toStaffId) {
        int moved = jdbc.update("""
                update appointments set staff_id = ?, updated_at = now()
                where staff_id = ? and id in (select appointment_id from handover_records where result = 'IN_PROGRESS')
                """, toStaffId, fromStaffId);
        return moved == 0 ? Map.of() : Map.of("phiên bàn giao đang dở", moved);
    }
}
