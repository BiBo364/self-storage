package vn.lemar.selfstorage.identity.application;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Public API đọc nhân viên cơ sở (role FS) được gán vào một cơ sở qua {@code account_facility_assignments} — dùng khi
 * FM phân công FS cho lịch hẹn. Chỉ tính tài khoản {@code ACTIVE}.
 */
@Service
public class FacilityStaffQuery {

    private static final String STAFF_ROLE = "FS";

    private final JdbcTemplate jdbc;

    public FacilityStaffQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<StaffView> listStaff(Long facilityId) {
        return jdbc.query("""
                select a.id, a.email from accounts a
                join roles r on r.id = a.role_id
                join account_facility_assignments f on f.account_id = a.id
                where f.facility_id = ? and r.name = ? and a.status = 'ACTIVE'
                order by a.email""",
                (rs, rowNum) -> new StaffView(rs.getLong(1), rs.getString(2)), facilityId, STAFF_ROLE);
    }

    /** Tài khoản là FS đang hoạt động và được gán vào đúng cơ sở. */
    @Transactional(readOnly = true)
    public boolean isActiveStaffOf(Long accountId, Long facilityId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from accounts a
                    join roles r on r.id = a.role_id
                    join account_facility_assignments f on f.account_id = a.id
                    where a.id = ? and f.facility_id = ? and r.name = ? and a.status = 'ACTIVE')""",
                Boolean.class, accountId, facilityId, STAFF_ROLE));
    }

    /** Một FS của cơ sở — tài khoản chưa có cột họ tên nên hiển thị bằng email. */
    public record StaffView(Long id, String email) {
    }
}
