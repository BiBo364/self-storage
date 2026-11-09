package vn.lemar.selfstorage.staffing.application;

import java.sql.Date;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import vn.lemar.selfstorage.facility.application.FacilityAccess;
import vn.lemar.selfstorage.identity.application.Access;
import vn.lemar.selfstorage.identity.application.CurrentAccountProvider;
import vn.lemar.selfstorage.pricing.application.PricingQuery;

/**
 * Tra cứu dùng chung của module nhân sự: tài khoản nhân viên, cơ sở được gán, nghỉ phép đã duyệt, policy bắt buộc ca,
 * khoá advisory theo FS.
 *
 * <p>Khoá theo FS tuần tự hoá mọi thay đổi ca / duyệt nghỉ phép (khoá ghi) với các lệnh phân công việc (khoá đọc):
 * lệnh duyệt nghỉ phép / xoá ca chờ FM đang phân công commit rồi mới kiểm việc của FS, nên thấy việc vừa giao.
 */
@Component
class StaffDirectory {

    static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    static final String POLICY_SHIFT_REQUIRED = "staff.shift_required";
    /** Không gian khoá advisory (int, int) của nhân sự — tách khỏi khoá một tham số bigint đang dùng ở payment. */
    private static final int LOCK_SPACE = 5101;

    private final JdbcTemplate jdbc;
    private final PricingQuery pricingQuery;
    private final FacilityAccess facilityAccess;
    private final Access access;
    private final CurrentAccountProvider currentAccount;

    StaffDirectory(JdbcTemplate jdbc, PricingQuery pricingQuery, FacilityAccess facilityAccess, Access access,
                   CurrentAccountProvider currentAccount) {
        this.jdbc = jdbc;
        this.pricingQuery = pricingQuery;
        this.facilityAccess = facilityAccess;
        this.access = access;
        this.currentAccount = currentAccount;
    }

    /** Tài khoản nhân viên; {@code name}/{@code phone} nullable. */
    record StaffAccount(Long id, String email, String name, String phone, String role, String status) {

        String displayName() {
            return name == null || name.isBlank() ? email : name;
        }
    }

    Optional<StaffAccount> account(Long accountId) {
        return jdbc.query("""
                select a.id, a.email, a.name, a.phone, r.name, a.status from accounts a
                join roles r on r.id = a.role_id where a.id = ?""",
                (rs, i) -> new StaffAccount(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6)), accountId).stream().findFirst();
    }

    Long currentAccountId() {
        return currentAccount.getCurrentAccountId();
    }

    List<Long> facilitiesOf(Long staffId) {
        return jdbc.queryForList(
                "select facility_id from account_facility_assignments where account_id = ? order by facility_id",
                Long.class, staffId);
    }

    String facilityCode(Long facilityId) {
        return facilityAccess.code(facilityId).orElse("#" + facilityId);
    }

    /** FM phụ trách các cơ sở FS được gán (không trùng). */
    List<Long> managersOf(Long staffId) {
        return facilitiesOf(staffId).stream().map(facilityAccess::managerAccountId).flatMap(Optional::stream)
                .distinct().toList();
    }

    /**
     * Kiểm quyền {@code permission} (scope FACILITY) của người gọi lên một FS: người gọi phải phụ trách ít nhất một cơ
     * sở FS được gán. Trả cơ sở dùng để kiểm.
     */
    Long requireFacilityScopeOver(Long staffId, String permission) {
        List<Long> facilities = facilitiesOf(staffId);
        if (facilities.isEmpty()) {
            throw StaffingException.forbidden("FS không thuộc cơ sở nào bạn phụ trách");
        }
        Long actorId = currentAccountId();
        Long facilityId = facilities.stream().filter(f -> facilityAccess.isManagedBy(actorId, f)).findFirst()
                .orElse(facilities.get(0));
        access.can(permission, facilityId);
        return facilityId;
    }

    boolean onApprovedLeave(Long staffId, LocalDate date) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from staff_leaves
                              where staff_id = ? and status = 'Approved' and ? between from_date and to_date)""",
                Boolean.class, staffId, Date.valueOf(date)));
    }

    /** Policy {@code staff.shift_required}; thiếu key là lỗi cấu hình seed (như mọi policy khác). */
    boolean shiftRequired() {
        String value = pricingQuery.valueOf(POLICY_SHIFT_REQUIRED)
                .orElseThrow(() -> new IllegalStateException("Thiếu tham số policies." + POLICY_SHIFT_REQUIRED));
        return "true".equalsIgnoreCase(value.strip());
    }

    /** Khoá ghi theo FS tới hết transaction: thêm / sửa / xoá ca, gửi / duyệt nghỉ phép. */
    void lockStaff(Long staffId) {
        jdbc.queryForObject("select 1 from pg_advisory_xact_lock(?, ?)", Integer.class, LOCK_SPACE, lockKey(staffId));
    }

    /** Khoá đọc theo FS tới hết transaction: lệnh phân công việc cho FS. */
    void lockStaffShared(Long staffId) {
        jdbc.queryForObject("select 1 from pg_advisory_xact_lock_shared(?, ?)", Integer.class, LOCK_SPACE,
                lockKey(staffId));
    }

    static String range(LocalTime start, LocalTime end) {
        return TIME.format(start) + "–" + TIME.format(end);
    }

    static String onLeaveMessage(LocalDate date) {
        return "FS đang nghỉ phép ngày " + DATE.format(date);
    }

    private static int lockKey(Long staffId) {
        return (int) (staffId % Integer.MAX_VALUE);
    }
}
