package vn.lemar.selfstorage.tenancy.application;

import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import vn.lemar.selfstorage.identity.application.StaffWorkTransfer;

/**
 * Yêu cầu trả kho đang giao ({@code Assigned}) cho FS nghỉ việc → FS thay thế, kèm lịch {@code RETURN} của yêu cầu (kể
 * cả buổi khách đã đến, biên bản trả kho chưa chốt).
 */
@Component
class ReturnWorkTransfer implements StaffWorkTransfer {

    private final JdbcTemplate jdbc;

    ReturnWorkTransfer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<String, Integer> transfer(Long fromStaffId, Long toStaffId) {
        jdbc.update("""
                update appointments set staff_id = ?, updated_at = now()
                where staff_id = ? and id in (select appointment_id from return_requests
                                              where status = 'Assigned' and assigned_staff_id = ?)
                """, toStaffId, fromStaffId, fromStaffId);
        int moved = jdbc.update("update return_requests set assigned_staff_id = ?, updated_at = now() "
                + "where assigned_staff_id = ? and status = 'Assigned'", toStaffId, fromStaffId);
        return moved == 0 ? Map.of() : Map.of("yêu cầu trả kho", moved);
    }
}
