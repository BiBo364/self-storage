package vn.lemar.selfstorage.staffing.application;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.lemar.selfstorage.audit.application.AuditEntry;
import vn.lemar.selfstorage.audit.application.AuditRecorder;
import vn.lemar.selfstorage.audit.domain.AuditAction;
import vn.lemar.selfstorage.audit.domain.AuditEntityType;
import vn.lemar.selfstorage.identity.application.Access;
import vn.lemar.selfstorage.staffing.application.StaffDirectory.StaffAccount;

/**
 * Hồ sơ nhân viên (tài khoản FS / FM). ADMIN, BOM sửa ({@code staff_profile.manage}, GLOBAL) và đọc đủ, kể cả số giấy
 * tờ tuỳ thân; FM đọc hồ sơ FS của cơ sở mình ({@code staff_profile.read}, FACILITY) và FS đọc hồ sơ của mình — hai
 * trường hợp này dùng {@link ProfileSummary}, kiểu không có trường số giấy tờ. Audit che số giấy tờ (giữ 4 số cuối).
 */
@Service
public class StaffProfileService {

    static final String PERM_READ = "staff_profile.read";
    static final String PERM_MANAGE = "staff_profile.manage";
    private static final Set<String> STAFF_ROLES = Set.of("FS", "FM");
    private static final String NOT_FOUND = "Không tìm thấy hồ sơ nhân viên";

    private final JdbcTemplate jdbc;
    private final StaffDirectory directory;
    private final Access access;
    private final AuditRecorder auditRecorder;

    StaffProfileService(JdbcTemplate jdbc, StaffDirectory directory, Access access, AuditRecorder auditRecorder) {
        this.jdbc = jdbc;
        this.directory = directory;
        this.access = access;
        this.auditRecorder = auditRecorder;
    }

    /** Hồ sơ đầy đủ cho ADMIN / BOM; mọi trường hồ sơ nullable (chưa nhập). */
    public record ProfileView(Long accountId, String email, String name, String phone, String role, String status,
                              String position, LocalDate hireDate, String idNumber, String emergencyContactName,
                              String emergencyContactPhone, String note, Instant updatedAt) {
    }

    /** Hồ sơ cho FM / FS — không có số giấy tờ tuỳ thân. */
    public record ProfileSummary(Long accountId, String email, String name, String phone, String role,
                                 String status, String position, LocalDate hireDate, String emergencyContactName,
                                 String emergencyContactPhone, String note, Instant updatedAt) {
    }

    /** Nội dung sửa — thay toàn bộ; trường rỗng = xoá. */
    public record ProfileInput(String position, LocalDate hireDate, String idNumber, String emergencyContactName,
                               String emergencyContactPhone, String note) {
    }

    private record Fields(String position, LocalDate hireDate, String idNumber, String emergencyContactName,
                          String emergencyContactPhone, String note, Instant updatedAt) {

        static final Fields EMPTY = new Fields(null, null, null, null, null, null, null);
    }

    @Transactional(readOnly = true)
    public ProfileView get(Long accountId) {
        access.can(PERM_READ);
        return full(requireStaff(accountId));
    }

    @Transactional
    public ProfileView update(Long accountId, ProfileInput input) {
        access.can(PERM_MANAGE);
        StaffAccount account = requireStaff(accountId);
        Fields next = validate(input);
        Fields before = fields(accountId).orElse(Fields.EMPTY);
        Long actorId = directory.currentAccountId();
        jdbc.update("""
                insert into staff_profiles (account_id, position, hire_date, id_number, emergency_contact_name,
                                            emergency_contact_phone, note, updated_by, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, now())
                on conflict (account_id) do update set position = excluded.position, hire_date = excluded.hire_date,
                    id_number = excluded.id_number, emergency_contact_name = excluded.emergency_contact_name,
                    emergency_contact_phone = excluded.emergency_contact_phone, note = excluded.note,
                    updated_by = excluded.updated_by, updated_at = now()""",
                accountId, next.position(), next.hireDate() == null ? null : Date.valueOf(next.hireDate()),
                next.idNumber(), next.emergencyContactName(), next.emergencyContactPhone(), next.note(), actorId);
        auditRecorder.record(new AuditEntry(AuditAction.STAFF_PROFILE_UPDATED, AuditEntityType.STAFF_PROFILE,
                String.valueOf(accountId), auditValue(before), auditValue(next), null, actorId));
        return full(account);
    }

    /** FM: hồ sơ một FS được gán vào cơ sở FM phụ trách. */
    @Transactional(readOnly = true)
    public ProfileSummary forFacilityManager(Long accountId) {
        StaffAccount account = requireStaff(accountId);
        if (!"FS".equals(account.role())) {
            throw StaffingException.notFound(NOT_FOUND);
        }
        directory.requireFacilityScopeOver(accountId, PERM_READ);
        return summary(account);
    }

    /** FS: hồ sơ của chính mình. */
    @Transactional(readOnly = true)
    public ProfileSummary mine() {
        return summary(requireStaff(directory.currentAccountId()));
    }

    // ------------------------------------------------------------------ nội bộ

    private StaffAccount requireStaff(Long accountId) {
        return directory.account(accountId).filter(a -> STAFF_ROLES.contains(a.role()))
                .orElseThrow(() -> StaffingException.notFound(NOT_FOUND));
    }

    private ProfileView full(StaffAccount a) {
        Fields f = fields(a.id()).orElse(Fields.EMPTY);
        return new ProfileView(a.id(), a.email(), a.name(), a.phone(), a.role(), a.status(), f.position(),
                f.hireDate(), f.idNumber(), f.emergencyContactName(), f.emergencyContactPhone(), f.note(),
                f.updatedAt());
    }

    private ProfileSummary summary(StaffAccount a) {
        Fields f = fields(a.id()).orElse(Fields.EMPTY);
        return new ProfileSummary(a.id(), a.email(), a.name(), a.phone(), a.role(), a.status(), f.position(),
                f.hireDate(), f.emergencyContactName(), f.emergencyContactPhone(), f.note(), f.updatedAt());
    }

    private Optional<Fields> fields(Long accountId) {
        return jdbc.query("""
                select position, hire_date, id_number, emergency_contact_name, emergency_contact_phone, note,
                       updated_at
                from staff_profiles where account_id = ?""",
                (rs, i) -> {
                    Timestamp updated = rs.getTimestamp(7);
                    return new Fields(rs.getString(1), rs.getObject(2, LocalDate.class), rs.getString(3),
                            rs.getString(4), rs.getString(5), rs.getString(6), updated.toInstant());
                }, accountId).stream().findFirst();
    }

    private static Fields validate(ProfileInput input) {
        String position = trimmed(input.position(), 100, "Chức danh");
        String idNumber = trimmed(input.idNumber(), 20, "Số giấy tờ");
        if (idNumber != null) {
            idNumber = idNumber.toUpperCase();
            if (!idNumber.matches("[A-Z0-9]{6,20}")) {
                throw StaffingException.invalid("Số giấy tờ chỉ gồm chữ và số, 6–20 ký tự");
            }
        }
        String contactName = trimmed(input.emergencyContactName(), 100, "Tên người liên hệ khẩn cấp");
        String contactPhone = trimmed(input.emergencyContactPhone(), 20, "Số điện thoại liên hệ khẩn cấp");
        if (contactPhone != null && !contactPhone.matches("\\+?[0-9 .-]{8,20}")) {
            throw StaffingException.invalid("Số điện thoại liên hệ khẩn cấp không hợp lệ");
        }
        if (input.hireDate() != null && input.hireDate().isAfter(LocalDate.now(StaffDirectory.ZONE))) {
            throw StaffingException.invalid("Ngày vào làm không được ở tương lai");
        }
        return new Fields(position, input.hireDate(), idNumber, contactName, contactPhone,
                trimmed(input.note(), 1000, "Ghi chú"), null);
    }

    private static String trimmed(String value, int max, String label) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() > max) {
            throw StaffingException.invalid(label + " tối đa " + max + " ký tự");
        }
        return trimmed;
    }

    private static Map<String, Object> auditValue(Fields f) {
        Map<String, Object> value = new HashMap<>();
        value.put("position", f.position());
        value.put("hireDate", f.hireDate() == null ? null : f.hireDate().toString());
        value.put("idNumber", mask(f.idNumber()));
        value.put("emergencyContactName", f.emergencyContactName());
        value.put("emergencyContactPhone", f.emergencyContactPhone());
        value.put("note", f.note());
        return value;
    }

    /** Số giấy tờ trong audit: chỉ giữ 4 ký tự cuối. */
    static String mask(String idNumber) {
        if (idNumber == null) {
            return null;
        }
        int keep = Math.min(4, idNumber.length());
        return "*".repeat(idNumber.length() - keep) + idNumber.substring(idNumber.length() - keep);
    }
}
