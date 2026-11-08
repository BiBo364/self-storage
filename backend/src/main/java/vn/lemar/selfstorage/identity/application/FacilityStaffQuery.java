package vn.lemar.selfstorage.identity.application;

import java.time.Instant;
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
                select a.id, a.name, a.email, a.phone, f.assigned_at from accounts a
                join roles r on r.id = a.role_id
                join account_facility_assignments f on f.account_id = a.id
                where f.facility_id = ? and r.name = ? and a.status = 'ACTIVE'
                order by a.email""",
                (rs, rowNum) -> new StaffView(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getTimestamp(5).toInstant()),
                facilityId, STAFF_ROLE);
    }

    /**
     * Tài khoản là FS đang hoạt động và được gán vào đúng cơ sở. Khoá {@code FOR SHARE} dòng gán tới hết transaction
     * của người gọi (FM đang phân công việc): gỡ FS khỏi cơ sở cùng lúc phải chờ, rồi thấy việc vừa giao
     * ({@link AccountAdminService#setStaffFacilities}).
     */
    @Transactional
    public boolean isActiveStaffOf(Long accountId, Long facilityId) {
        return !jdbc.queryForList("""
                select f.account_id from accounts a
                join roles r on r.id = a.role_id
                join account_facility_assignments f on f.account_id = a.id
                where a.id = ? and f.facility_id = ? and r.name = ? and a.status = 'ACTIVE'
                for share of f""",
                Long.class, accountId, facilityId, STAFF_ROLE).isEmpty();
    }

    /** Một FS của cơ sở; {@code name}/{@code phone} nullable — FE fallback về email. */
    public record StaffView(Long id, String name, String email, String phone, Instant assignedAt) {
    }
}
