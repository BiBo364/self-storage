package vn.lemar.selfstorage.booking.application;

import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import vn.lemar.selfstorage.identity.application.StaffWorkTransfer;

/** Lịch hẹn ({@code CHECKIN} / {@code RETURN}) còn {@code Pending} của FS nghỉ việc → FS thay thế. */
@Component
class AppointmentWorkTransfer implements StaffWorkTransfer {

    private final JdbcTemplate jdbc;

    AppointmentWorkTransfer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<String, Integer> transfer(Long fromStaffId, Long toStaffId) {
        int moved = jdbc.update("update appointments set staff_id = ?, updated_at = now() "
                + "where staff_id = ? and status = 'Pending'", toStaffId, fromStaffId);
        return moved == 0 ? Map.of() : Map.of("lịch hẹn", moved);
    }
}
