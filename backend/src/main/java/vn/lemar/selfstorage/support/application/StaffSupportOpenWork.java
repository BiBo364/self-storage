package vn.lemar.selfstorage.support.application;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import vn.lemar.selfstorage.identity.application.StaffOpenWork;

/** Yêu cầu hỗ trợ {@code Assigned} giao cho FS tại cơ sở — FM giao lại được. */
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
                (rs, rowNum) -> "yêu cầu hỗ trợ #" + rs.getLong(1) + " (FM giao lại)", staffId, facilityId);
    }
}
