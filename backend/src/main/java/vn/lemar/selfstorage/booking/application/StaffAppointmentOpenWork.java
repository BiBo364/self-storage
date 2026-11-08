package vn.lemar.selfstorage.booking.application;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import vn.lemar.selfstorage.identity.application.StaffOpenWork;

/**
 * Lịch hẹn {@code Pending} đã phân cho FS tại cơ sở (check-in, trả kho). Lịch trả kho đi kèm yêu cầu trả kho
 * {@code Assigned}, nên yêu cầu đó không cần liệt kê riêng.
 */
@Component
class StaffAppointmentOpenWork implements StaffOpenWork {

    private final JdbcTemplate jdbc;

    StaffAppointmentOpenWork(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<String> openItems(Long staffId, Long facilityId) {
        return jdbc.query("""
                select id, type from appointments
                where staff_id = ? and facility_id = ? and status = 'Pending' order by id""",
                (rs, rowNum) -> label(rs.getString(2)) + " #" + rs.getLong(1), staffId, facilityId);
    }

    private static String label(String type) {
        return switch (type) {
            case "CHECKIN" -> "lịch check-in";
            case "RETURN" -> "lịch trả kho";
            default -> "lịch bàn giao";
        };
    }
}
