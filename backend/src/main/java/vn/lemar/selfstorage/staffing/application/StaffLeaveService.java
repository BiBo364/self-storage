package vn.lemar.selfstorage.staffing.application;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.lemar.selfstorage.audit.application.AuditEntry;
import vn.lemar.selfstorage.audit.application.AuditRecorder;
import vn.lemar.selfstorage.audit.domain.AuditAction;
import vn.lemar.selfstorage.audit.domain.AuditEntityType;
import vn.lemar.selfstorage.identity.application.Access;
import vn.lemar.selfstorage.identity.application.StaffOpenWork;
import vn.lemar.selfstorage.notification.application.NotificationRecipient;
import vn.lemar.selfstorage.notification.application.NotificationType;
import vn.lemar.selfstorage.staffing.application.StaffDirectory.StaffAccount;

/**
 * Nghỉ phép của FS: FS gửi / xem / huỷ đơn của mình; FM của bất kỳ cơ sở nào FS được gán xem và duyệt / từ chối
 * ({@code staff_leave.review}, scope FACILITY).
 *
 * <p>Duyệt khi FS còn việc trong khoảng nghỉ (lịch hẹn chờ có ngày trong khoảng, yêu cầu hỗ trợ đang giao) → 409
 * liệt kê việc; không còn việc thì duyệt và xoá ca của FS trong khoảng nghỉ. Trạng thái: {@code Pending →
 * Approved | Rejected | Canceled}, {@code Approved → Canceled} khi ngày bắt đầu còn ở tương lai.
 */
@Service
public class StaffLeaveService {

    static final String PERM_REVIEW = "staff_leave.review";
    static final String PENDING = "Pending";
    static final String APPROVED = "Approved";
    static final String REJECTED = "Rejected";
    static final String CANCELED = "Canceled";
    private static final int MAX_TEXT = 500;
    private static final int MAX_OPEN_ITEMS_SHOWN = 5;
    private static final int LIST_LIMIT = 200;

    private static final String SELECT = """
            select l.id, l.staff_id, a.name, a.email, l.from_date, l.to_date, l.reason, l.status, l.reviewed_by,
                   r.email, l.reviewed_at, l.review_note, l.created_at
            from staff_leaves l join accounts a on a.id = l.staff_id left join accounts r on r.id = l.reviewed_by
            """;

    private final JdbcTemplate jdbc;
    private final StaffDirectory directory;
    private final Access access;
    private final AuditRecorder auditRecorder;
    private final StaffNotifier notifier;
    private final StaffShiftService shifts;
    private final List<StaffOpenWork> openWork;

    StaffLeaveService(JdbcTemplate jdbc, StaffDirectory directory, Access access, AuditRecorder auditRecorder,
                      StaffNotifier notifier, StaffShiftService shifts, List<StaffOpenWork> openWork) {
        this.jdbc = jdbc;
        this.directory = directory;
        this.access = access;
        this.auditRecorder = auditRecorder;
        this.notifier = notifier;
        this.shifts = shifts;
        this.openWork = List.copyOf(openWork);
    }

    /**
     * Một đơn nghỉ; {@code cancelable} = FS còn huỷ được ({@code Pending}, hoặc {@code Approved} chưa tới ngày bắt
     * đầu). {@code staffName}, {@code reviewedBy*}, {@code reviewNote} nullable.
     */
    public record LeaveView(Long id, Long staffId, String staffName, String staffEmail, LocalDate fromDate,
                            LocalDate toDate, String reason, String status, Long reviewedBy, String reviewedByEmail,
                            Instant reviewedAt, String reviewNote, Instant createdAt, boolean cancelable) {
    }

    // ------------------------------------------------------------------ FS

    @Transactional(readOnly = true)
    public List<LeaveView> myLeaves() {
        return jdbc.query(SELECT + " where l.staff_id = ? order by l.from_date desc, l.id desc limit " + LIST_LIMIT,
                mapper(), directory.currentAccountId());
    }

    /** FS gửi đơn: {@code from ≤ to}, {@code from ≥} hôm nay, không chồng đơn {@code Pending}/{@code Approved}. */
    @Transactional
    public LeaveView request(LocalDate from, LocalDate to, String reason) {
        if (from == null || to == null || from.isAfter(to)) {
            throw StaffingException.invalid("Khoảng ngày nghỉ không hợp lệ");
        }
        if (from.isBefore(today())) {
            throw StaffingException.invalid("Ngày bắt đầu nghỉ không được ở quá khứ");
        }
        if (reason == null || reason.isBlank() || reason.length() > MAX_TEXT) {
            throw StaffingException.invalid("Lý do nghỉ bắt buộc, tối đa " + MAX_TEXT + " ký tự");
        }
        Long staffId = directory.currentAccountId();
        directory.lockStaff(staffId);
        List<LeaveView> overlapping = jdbc.query(SELECT + """
                 where l.staff_id = ? and l.status in ('Pending', 'Approved')
                   and l.from_date <= ? and l.to_date >= ? order by l.from_date""", mapper(),
                staffId, Date.valueOf(to), Date.valueOf(from));
        if (!overlapping.isEmpty()) {
            LeaveView other = overlapping.get(0);
            throw StaffingException.conflict("Trùng đơn nghỉ #" + other.id() + " (" + period(other) + ", "
                    + other.status() + ")");
        }
        Long id = jdbc.queryForObject("""
                insert into staff_leaves (staff_id, from_date, to_date, reason) values (?, ?, ?, ?) returning id""",
                Long.class, staffId, Date.valueOf(from), Date.valueOf(to), reason.trim());
        LeaveView created = find(id).orElseThrow();
        audit(AuditAction.STAFF_LEAVE_REQUESTED, id, null, snapshot(created), null, staffId);
        Map<String, Object> values = new HashMap<>();
        values.put("staffName", displayName(created));
        values.put("fromDate", StaffDirectory.DATE.format(from));
        values.put("toDate", StaffDirectory.DATE.format(to));
        values.put("reason", created.reason());
        for (Long managerId : directory.managersOf(staffId)) {
            notifier.notify(managerId, NotificationRecipient.FM, NotificationType.STAFF_LEAVE_REQUESTED, values);
        }
        return created;
    }

    /**
     * FS huỷ đơn của mình: {@code Pending}, hoặc {@code Approved} có ngày bắt đầu sau hôm nay. Ca đã xoá lúc duyệt
     * không được khôi phục.
     */
    @Transactional
    public LeaveView cancel(Long id) {
        Long staffId = directory.currentAccountId();
        LeaveView peek = find(id).filter(l -> l.staffId().equals(staffId))
                .orElseThrow(() -> StaffingException.notFound("Không tìm thấy đơn nghỉ phép"));
        directory.lockStaff(staffId);
        LeaveView before = lockLeave(peek.id());
        if (!before.cancelable()) {
            throw StaffingException.conflict("Chỉ huỷ được đơn đang chờ duyệt, hoặc đơn đã duyệt chưa tới ngày nghỉ");
        }
        jdbc.update("update staff_leaves set status = ? where id = ?", CANCELED, id);
        LeaveView after = find(id).orElseThrow();
        audit(AuditAction.STAFF_LEAVE_CANCELED, id, Map.of("status", before.status()),
                Map.of("status", after.status()), null, staffId);
        return after;
    }

    // ------------------------------------------------------------------ FM

    /** FM: đơn nghỉ của các FS đang được gán vào cơ sở; {@code status} null = mọi trạng thái, đơn chờ duyệt lên đầu. */
    @Transactional(readOnly = true)
    public List<LeaveView> facilityLeaves(Long facilityId, String status) {
        access.can(PERM_REVIEW, facilityId);
        List<Object> args = new ArrayList<>();
        args.add(facilityId);
        String filter = "";
        if (status != null && !status.isBlank()) {
            filter = " and l.status = ?";
            args.add(status);
        }
        return jdbc.query(SELECT + """
                 where exists (select 1 from account_facility_assignments f
                               where f.account_id = l.staff_id and f.facility_id = ?)""" + filter
                        + " order by (l.status = 'Pending') desc, l.from_date desc, l.id desc limit " + LIST_LIMIT,
                mapper(), args.toArray());
    }

    @Transactional
    public LeaveView approve(Long id, String note) {
        LeaveView peek = requireReviewable(id, note);
        directory.lockStaff(peek.staffId());
        LeaveView before = lockLeave(id);
        requirePending(before);
        List<String> items = openWork.stream()
                .flatMap(source -> source.openItemsBetween(before.staffId(), before.fromDate(), before.toDate())
                        .stream()).toList();
        if (!items.isEmpty()) {
            String shown = String.join(", ", items.subList(0, Math.min(items.size(), MAX_OPEN_ITEMS_SHOWN)));
            String more = items.size() > MAX_OPEN_ITEMS_SHOWN
                    ? " và " + (items.size() - MAX_OPEN_ITEMS_SHOWN) + " việc khác" : "";
            throw StaffingException.conflict("FS còn việc trong thời gian nghỉ: " + shown + more
                    + ". Xử lý các việc này trước khi duyệt nghỉ phép");
        }
        Long actorId = directory.currentAccountId();
        LeaveView after = review(before, APPROVED, note, actorId);
        shifts.deleteForLeave(before.staffId(), before.fromDate(), before.toDate(), id, actorId);
        audit(AuditAction.STAFF_LEAVE_APPROVED, id, Map.of("status", before.status()), reviewValue(after), null,
                actorId);
        notifyReviewed(after, "đã được duyệt");
        return after;
    }

    @Transactional
    public LeaveView reject(Long id, String note) {
        LeaveView peek = requireReviewable(id, note);
        directory.lockStaff(peek.staffId());
        LeaveView before = lockLeave(id);
        requirePending(before);
        Long actorId = directory.currentAccountId();
        LeaveView after = review(before, REJECTED, note, actorId);
        audit(AuditAction.STAFF_LEAVE_REJECTED, id, Map.of("status", before.status()), reviewValue(after), null,
                actorId);
        notifyReviewed(after, "bị từ chối");
        return after;
    }

    // ------------------------------------------------------------------ nội bộ

    private LeaveView requireReviewable(Long id, String note) {
        if (note != null && note.length() > MAX_TEXT) {
            throw StaffingException.invalid("Ghi chú tối đa " + MAX_TEXT + " ký tự");
        }
        LeaveView peek = find(id).orElseThrow(() -> StaffingException.notFound("Không tìm thấy đơn nghỉ phép"));
        directory.requireFacilityScopeOver(peek.staffId(), PERM_REVIEW);
        return peek;
    }

    private static void requirePending(LeaveView leave) {
        if (!PENDING.equals(leave.status())) {
            throw StaffingException.conflict("Đơn nghỉ phép không còn chờ duyệt");
        }
    }

    private LeaveView review(LeaveView before, String status, String note, Long actorId) {
        jdbc.update("""
                update staff_leaves set status = ?, reviewed_by = ?, reviewed_at = now(), review_note = ?
                where id = ?""", status, actorId, note == null || note.isBlank() ? null : note.trim(), before.id());
        return find(before.id()).orElseThrow();
    }

    private void notifyReviewed(LeaveView leave, String result) {
        Map<String, Object> values = new HashMap<>();
        values.put("result", result);
        values.put("fromDate", StaffDirectory.DATE.format(leave.fromDate()));
        values.put("toDate", StaffDirectory.DATE.format(leave.toDate()));
        values.put("noteText", leave.reviewNote() == null ? "" : " Ghi chú: " + leave.reviewNote());
        notifier.notify(leave.staffId(), NotificationRecipient.FS, NotificationType.STAFF_LEAVE_REVIEWED, values);
    }

    Optional<LeaveView> find(Long id) {
        return jdbc.query(SELECT + " where l.id = ?", mapper(), id).stream().findFirst();
    }

    private LeaveView lockLeave(Long id) {
        jdbc.queryForList("select id from staff_leaves where id = ? for update", Long.class, id);
        return find(id).orElseThrow();
    }

    private RowMapper<LeaveView> mapper() {
        LocalDate today = today();
        return (rs, i) -> {
            String status = rs.getString(8);
            LocalDate from = rs.getObject(5, LocalDate.class);
            Timestamp reviewedAt = rs.getTimestamp(11);
            return new LeaveView(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), from,
                    rs.getObject(6, LocalDate.class), rs.getString(7), status, (Long) rs.getObject(9),
                    rs.getString(10), reviewedAt == null ? null : reviewedAt.toInstant(), rs.getString(12),
                    rs.getTimestamp(13).toInstant(),
                    PENDING.equals(status) || (APPROVED.equals(status) && from.isAfter(today)));
        };
    }

    private static LocalDate today() {
        return LocalDate.now(StaffDirectory.ZONE);
    }

    private static String period(LeaveView leave) {
        return StaffDirectory.DATE.format(leave.fromDate()) + " – " + StaffDirectory.DATE.format(leave.toDate());
    }

    private static String displayName(LeaveView leave) {
        return new StaffAccount(leave.staffId(), leave.staffEmail(), leave.staffName(), null, null, null)
                .displayName();
    }

    private static Map<String, Object> snapshot(LeaveView leave) {
        Map<String, Object> value = new HashMap<>();
        value.put("staffId", leave.staffId());
        value.put("fromDate", leave.fromDate().toString());
        value.put("toDate", leave.toDate().toString());
        value.put("status", leave.status());
        return value;
    }

    private static Map<String, Object> reviewValue(LeaveView leave) {
        Map<String, Object> value = new HashMap<>();
        value.put("status", leave.status());
        value.put("fromDate", leave.fromDate().toString());
        value.put("toDate", leave.toDate().toString());
        value.put("reviewNote", leave.reviewNote());
        return value;
    }

    private void audit(AuditAction action, Long id, Object oldValue, Object newValue, String reason, Long actorId) {
        auditRecorder.record(new AuditEntry(action, AuditEntityType.STAFF_LEAVE, String.valueOf(id), oldValue,
                newValue, reason, actorId));
    }
}
