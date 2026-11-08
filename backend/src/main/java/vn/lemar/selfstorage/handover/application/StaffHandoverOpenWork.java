package vn.lemar.selfstorage.handover.application;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import vn.lemar.selfstorage.identity.application.StaffOpenWork;

/**
 * Phiên bàn giao {@code IN_PROGRESS} khách đã đến, FS của lịch là người làm tiếp — không phân công lại được, FS phải
 * hoàn tất hoặc đóng phiên trước khi rời cơ sở. Phiên khách chưa đến đã tính qua lịch {@code Pending} ở booking.
 */
@Component
class StaffHandoverOpenWork implements StaffOpenWork {

    private final JdbcTemplate jdbc;

    StaffHandoverOpenWork(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<String> openItems(Long staffId, Long facilityId) {
        return jdbc.query("""
                select h.id from handover_records h join appointments a on a.id = h.appointment_id
                where a.staff_id = ? and a.facility_id = ? and h.result = 'IN_PROGRESS' and a.arrived_at is not null
                order by h.id""",
                (rs, rowNum) -> "phiên bàn giao #" + rs.getLong(1), staffId, facilityId);
    }
}
