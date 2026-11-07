package vn.lemar.selfstorage.identity.application;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.lemar.selfstorage.audit.application.AuditEntry;
import vn.lemar.selfstorage.audit.application.AuditRecorder;
import vn.lemar.selfstorage.audit.domain.AuditAction;
import vn.lemar.selfstorage.audit.domain.AuditEntityType;
import vn.lemar.selfstorage.facility.application.FacilityAccess;
import vn.lemar.selfstorage.identity.domain.Account;

/**
 * Quản trị tài khoản (Admin) và phân quyền: danh sách / tạo tài khoản nhân viên, đổi role / trạng thái, gán FS vào cơ
 * sở, ma trận role–permission, lịch sử đăng nhập. Role hệ thống cố định ({@code ADMIN, BOM, FM, FS, CUSTOMER});
 * quyền theo role là dữ liệu ({@code role_permissions}).
 */
@Service
public class AccountAdminService {

    public static final Set<String> ROLES = Set.of("ADMIN", "BOM", "FM", "FS", "CUSTOMER");
    public static final Set<String> STATUSES = Set.of("ACTIVE", "BANNED");

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final FacilityAccess facilityAccess;
    private final AuditRecorder auditRecorder;

    public AccountAdminService(JdbcTemplate jdbc, PasswordEncoder passwordEncoder, FacilityAccess facilityAccess,
                               AuditRecorder auditRecorder) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.facilityAccess = facilityAccess;
        this.auditRecorder = auditRecorder;
    }

    /** Tài khoản; {@code facilityIds} = cơ sở được gán (FS) hoặc phụ trách (FM). */
    public record AccountRow(Long id, String email, String name, String phone, String role, String status,
                             boolean verified, Instant createdAt, List<Long> facilityIds, Instant lastLoginAt) {
    }

    public record LoginEventRow(Long id, Long accountId, String email, boolean success, String reason,
                                String ipAddress, Instant createdAt) {
    }

    public record PermissionRow(String code, String description, Map<String, String> scopeByRole) {
    }

    @Transactional(readOnly = true)
    public List<AccountRow> list(String role, String status, String query, int page, int size) {
        StringBuilder sql = new StringBuilder("""
                select a.id, a.email, a.name, a.phone, r.name as role, a.status, a.email_verified_at is not null,
                       a.created_at,
                       (select max(e.created_at) from login_events e where e.account_id = a.id and e.success)
                from accounts a join roles r on r.id = a.role_id where 1 = 1""");
        List<Object> args = new ArrayList<>();
        if (role != null && !role.isBlank()) {
            sql.append(" and r.name = ?");
            args.add(role);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" and a.status = ?");
            args.add(status);
        }
        if (query != null && !query.isBlank()) {
            sql.append(" and (a.email ilike ? or a.name ilike ? or a.phone ilike ?)");
            String like = "%" + query.trim() + "%";
            args.add(like);
            args.add(like);
            args.add(like);
        }
        sql.append(" order by a.id limit ? offset ?");
        args.add(size);
        args.add(page * size);
        return jdbc.query(sql.toString(), (rs, i) -> {
            Timestamp last = rs.getTimestamp(9);
            long id = rs.getLong(1);
            return new AccountRow(id, rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                    rs.getString(6), rs.getBoolean(7), rs.getTimestamp(8).toInstant(), facilities(id),
                    last == null ? null : last.toInstant());
        }, args.toArray());
    }

    @Transactional(readOnly = true)
    public AccountRow get(Long id) {
        return list(null, null, null, 0, Integer.MAX_VALUE).stream().filter(a -> a.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy tài khoản"));
    }

    /** Tạo tài khoản (nhân viên hoặc khách) đã xác minh email, đăng nhập được ngay. */
    @Transactional
    public AccountRow create(String email, String password, String name, String phone, String role, Long actorId) {
        String normalized = Account.normalize(email);
        if (normalized == null || !normalized.contains("@")) {
            throw new IllegalArgumentException("Email không hợp lệ");
        }
        if (password == null || password.length() < 8) {
            throw new IllegalArgumentException("Mật khẩu tối thiểu 8 ký tự");
        }
        requireRole(role);
        if (Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from accounts where email = ?)",
                Boolean.class, normalized))) {
            throw new IllegalStateException("Email đã được dùng");
        }
        Long id = jdbc.queryForObject("""
                insert into accounts (email, password_hash, email_verified_at, role_id, name, phone)
                values (?, ?, now(), (select id from roles where name = ?), ?, ?) returning id""", Long.class,
                normalized, passwordEncoder.encode(password), role, blankToNull(name), blankToNull(phone));
        audit(AuditAction.ACCOUNT_CREATED, AuditEntityType.ACCOUNT, id, null,
                Map.of("email", normalized, "role", role), null, actorId);
        return get(id);
    }

    /** Đổi role / trạng thái / thông tin; tham số {@code null} = giữ nguyên. Không tự khoá / hạ quyền chính mình. */
    @Transactional
    public AccountRow update(Long id, String role, String status, String name, String phone, Long actorId) {
        AccountRow before = get(id);
        if (role != null) {
            requireRole(role);
        }
        if (status != null && !STATUSES.contains(status)) {
            throw new IllegalArgumentException("Trạng thái không hợp lệ");
        }
        if (id.equals(actorId) && ((role != null && !role.equals(before.role()))
                || (status != null && !status.equals(before.status())))) {
            throw new IllegalStateException("Không thể tự đổi role hoặc khoá tài khoản của chính mình");
        }
        jdbc.update("""
                update accounts set role_id = coalesce((select id from roles where name = ?), role_id),
                       status = coalesce(?, status), name = coalesce(?, name), phone = coalesce(?, phone)
                where id = ?""", role, status, blankToNull(name), blankToNull(phone), id);
        if (role != null && !role.equals(before.role()) && !"FS".equals(role)) {
            jdbc.update("delete from account_facility_assignments where account_id = ?", id);
        }
        AccountRow after = get(id);
        Map<String, Object> oldValue = new HashMap<>();
        oldValue.put("role", before.role());
        oldValue.put("status", before.status());
        Map<String, Object> newValue = new HashMap<>();
        newValue.put("role", after.role());
        newValue.put("status", after.status());
        audit(AuditAction.ACCOUNT_UPDATED, AuditEntityType.ACCOUNT, id, oldValue, newValue, null, actorId);
        return after;
    }

    /** Gán FS vào đúng các cơ sở {@code facilityIds} (thay toàn bộ danh sách). */
    @Transactional
    public AccountRow setStaffFacilities(Long accountId, List<Long> facilityIds, Long actorId) {
        AccountRow account = get(accountId);
        if (!"FS".equals(account.role())) {
            throw new IllegalStateException("Chỉ gán cơ sở cho tài khoản FS");
        }
        for (Long facilityId : facilityIds) {
            if (!facilityAccess.exists(facilityId)) {
                throw new IllegalArgumentException("Cơ sở " + facilityId + " không tồn tại");
            }
        }
        jdbc.update("delete from account_facility_assignments where account_id = ?", accountId);
        for (Long facilityId : facilityIds.stream().distinct().toList()) {
            jdbc.update("insert into account_facility_assignments (account_id, facility_id) values (?, ?)",
                    accountId, facilityId);
        }
        audit(AuditAction.ACCOUNT_UPDATED, AuditEntityType.ACCOUNT, accountId,
                Map.of("facilityIds", account.facilityIds()), Map.of("facilityIds", facilityIds), null, actorId);
        return get(accountId);
    }

    @Transactional(readOnly = true)
    public List<LoginEventRow> loginEvents(String email, int page, int size) {
        String filter = email == null || email.isBlank() ? "" : " where email ilike ?";
        List<Object> args = new ArrayList<>();
        if (!filter.isEmpty()) {
            args.add("%" + email.trim() + "%");
        }
        args.add(size);
        args.add(page * size);
        return jdbc.query("select id, account_id, email, success, reason, ip_address, created_at from login_events"
                        + filter + " order by created_at desc, id desc limit ? offset ?",
                (rs, i) -> new LoginEventRow(rs.getLong(1), (Long) rs.getObject(2), rs.getString(3),
                        rs.getBoolean(4), rs.getString(5), rs.getString(6), rs.getTimestamp(7).toInstant()),
                args.toArray());
    }

    /** Ma trận permission × role; ô rỗng = role không có quyền. */
    @Transactional(readOnly = true)
    public List<PermissionRow> permissionMatrix() {
        Map<String, PermissionRow> rows = new java.util.LinkedHashMap<>();
        jdbc.query("select code, description from permissions order by code", rs -> {
            rows.put(rs.getString(1), new PermissionRow(rs.getString(1), rs.getString(2), new HashMap<>()));
        });
        jdbc.query("""
                select p.code, r.name, rp.scope from role_permissions rp
                join permissions p on p.id = rp.permission_id join roles r on r.id = rp.role_id""", rs -> {
            PermissionRow row = rows.get(rs.getString(1));
            if (row != null) {
                row.scopeByRole().put(rs.getString(2), rs.getString(3));
            }
        });
        return List.copyOf(rows.values());
    }

    /** Đặt quyền của một role theo {@code code → scope} (GLOBAL/FACILITY); permission không có trong map bị gỡ. */
    @Transactional
    public List<PermissionRow> setRolePermissions(String role, Map<String, String> scopes, Long actorId) {
        requireRole(role);
        if ("ADMIN".equals(role)) {
            throw new IllegalStateException("Quyền ADMIN là role-bound, không chỉnh qua ma trận");
        }
        for (Map.Entry<String, String> e : scopes.entrySet()) {
            if (!"GLOBAL".equals(e.getValue()) && !"FACILITY".equals(e.getValue())) {
                throw new IllegalArgumentException("Scope không hợp lệ: " + e.getValue());
            }
        }
        Map<String, String> before = new HashMap<>();
        permissionMatrix().forEach(p -> {
            if (p.scopeByRole().containsKey(role)) {
                before.put(p.code(), p.scopeByRole().get(role));
            }
        });
        Long roleId = jdbc.queryForObject("select id from roles where name = ?", Long.class, role);
        jdbc.update("delete from role_permissions where role_id = ?", roleId);
        for (Map.Entry<String, String> e : scopes.entrySet()) {
            int inserted = jdbc.update("insert into role_permissions (role_id, permission_id, scope) "
                    + "select ?, id, ? from permissions where code = ?", roleId, e.getValue(), e.getKey());
            if (inserted == 0) {
                throw new IllegalArgumentException("Permission không tồn tại: " + e.getKey());
            }
        }
        audit(AuditAction.ROLE_PERMISSIONS_UPDATED, AuditEntityType.ROLE, roleId, before, scopes, null, actorId);
        return permissionMatrix();
    }

    /** Ghi một lần đăng nhập (transaction riêng — login chạy read-only, và lần thất bại vẫn phải lưu). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordLogin(String email, boolean success, String reason, String ipAddress) {
        Long accountId = jdbc.query("select id from accounts where email = ?", (rs, i) -> rs.getLong(1), email)
                .stream().findFirst().orElse(null);
        jdbc.update("insert into login_events (account_id, email, success, reason, ip_address) values (?, ?, ?, ?, ?)",
                accountId, email == null ? "" : email, success, reason, ipAddress);
    }

    private List<Long> facilities(Long accountId) {
        List<Long> ids = new ArrayList<>(jdbc.queryForList(
                "select facility_id from account_facility_assignments where account_id = ? order by facility_id",
                Long.class, accountId));
        ids.addAll(jdbc.queryForList("select id from facilities where fm_account_id = ?", Long.class, accountId));
        return ids;
    }

    private static void requireRole(String role) {
        if (role == null || !ROLES.contains(role)) {
            throw new IllegalArgumentException("Role không hợp lệ");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void audit(AuditAction action, AuditEntityType type, Long id, Object oldValue, Object newValue,
                       String reason, Long actorId) {
        auditRecorder.record(new AuditEntry(action, type, String.valueOf(id), oldValue, newValue, reason, actorId));
    }
}
