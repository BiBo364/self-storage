package vn.lemar.selfstorage.support.application;

import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import vn.lemar.selfstorage.identity.application.StaffOpenWork;

/** Yêu cầu hỗ trợ {@code Assigned} giao cho FS — FM giao lại được. Yêu cầu không gắn ngày nên luôn tính là việc mở. */
@Component
class StaffSupportOpenWork implements StaffOpenWork {

    private final JdbcTemplate jdbc;

    StaffSupportOpenWork(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<String> openItems(Long staffId, Long facilityId) {
        return jdbc.query("""
                select id from support_requests
                where assigned_staff_id = ? and facility_id = ? and status = 'Assigned' order by id""",
                (rs, rowNum) -> label(rs.getLong(1)), staffId, facilityId);
    }

    @Override
    public List<String> openItemsBetween(Long staffId, LocalDate from, LocalDate to) {
        return jdbc.query("""
                select id from support_requests where assigned_staff_id = ? and status = 'Assigned' order by id""",
                (rs, rowNum) -> label(rs.getLong(1)), staffId);
    }

    @Override
    public OpenCounts openCounts(Long staffId, Long facilityId, LocalDate date) {
        return jdbc.queryForObject("""
                select count(*) from support_requests
                where assigned_staff_id = ? and facility_id = ? and status = 'Assigned'""",
                (rs, rowNum) -> new OpenCounts(0, 0, rs.getInt(1)), staffId, facilityId);
    }

    private static String label(long id) {
        return "yêu cầu hỗ trợ #" + id + " (FM giao lại)";
    }
}
