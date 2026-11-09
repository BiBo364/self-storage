package vn.lemar.selfstorage.staffing.application;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.sql.Date;
import java.sql.Time;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
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
import vn.lemar.selfstorage.identity.application.FacilityStaffQuery;
import vn.lemar.selfstorage.identity.application.StaffOpenWork;
import vn.lemar.selfstorage.identity.application.StaffOpenWork.TimedItem;
import vn.lemar.selfstorage.notification.application.NotificationRecipient;
import vn.lemar.selfstorage.notification.application.NotificationType;

/**
 * Ca làm việc của FS: FM của cơ sở thêm / sửa / xoá ({@code staff_shift.manage}, scope FACILITY), FS xem ca của mình.
 *
 * <p>Bất biến: một FS không có hai ca chồng giờ trong cùng ngày ở mọi cơ sở; không xếp ca vào ngày FS nghỉ phép đã
 * duyệt; xoá / thu hẹp / dời ca không được làm lịch hẹn {@code Pending} đã giao cho FS (đang nằm trong ca) rơi ra ngoài
 * mọi ca. Mọi thay đổi giữ khoá ghi theo FS ({@link StaffDirectory#lockStaff}).
 */
@Service
public class StaffShiftService {

    static final String PERM_MANAGE = "staff_shift.manage";
    static final int MAX_RANGE_DAYS = 62;
    private static final int MAX_NOTE = 500;

    private static final String SELECT = """
            select s.id, s.facility_id, f.code, s.staff_id, a.name, a.email, s.shift_date, s.start_time, s.end_time,
                   s.note, s.updated_at
            from staff_shifts s join facilities f on f.id = s.facility_id join accounts a on a.id = s.staff_id
            """;
    private static final RowMapper<ShiftView> MAPPER = (rs, i) -> new ShiftView(rs.getLong(1), rs.getLong(2),
            rs.getString(3), rs.getLong(4), rs.getString(5), rs.getString(6), rs.getObject(7, LocalDate.class),
            rs.getObject(8, LocalTime.class), rs.getObject(9, LocalTime.class), rs.getString(10),
            rs.getTimestamp(11).toInstant());

    private final JdbcTemplate jdbc;
    private final StaffDirectory directory;
    private final Access access;
    private final FacilityStaffQuery staffQuery;
    private final AuditRecorder auditRecorder;
    private final StaffNotifier notifier;
    private final List<StaffOpenWork> openWork;

    StaffShiftService(JdbcTemplate jdbc, StaffDirectory directory, Access access, FacilityStaffQuery staffQuery,
                      AuditRecorder auditRecorder, StaffNotifier notifier, List<StaffOpenWork> openWork) {
        this.jdbc = jdbc;
        this.directory = directory;
        this.access = access;
        this.staffQuery = staffQuery;
        this.auditRecorder = auditRecorder;
        this.notifier = notifier;
        this.openWork = List.copyOf(openWork);
    }

    /** Một ca; {@code staffName} nullable (FE fallback email). Giờ định dạng {@code HH:mm}, giờ VN. */
    public record ShiftView(Long id, Long facilityId, String facilityCode, Long staffId, String staffName,
                            String staffEmail, LocalDate shiftDate,
                            @JsonFormat(pattern = "HH:mm") LocalTime startTime,
                            @JsonFormat(pattern = "HH:mm") LocalTime endTime, String note, Instant updatedAt) {
    }

    /** Thêm ca. */
    public record CreateInput(Long facilityId, Long staffId, LocalDate shiftDate, LocalTime startTime,
                              LocalTime endTime, String note) {
    }

    /** Sửa ca: ngày, giờ, ghi chú (FS và cơ sở giữ nguyên). */
    public record UpdateInput(LocalDate shiftDate, LocalTime startTime, LocalTime endTime, String note) {
    }

    /** FM: ca của cơ sở trong khoảng ngày; mặc định tuần hiện tại (thứ Hai → Chủ nhật). */
    @Transactional(readOnly = true)
    public List<ShiftView> facilityShifts(Long facilityId, LocalDate from, LocalDate to) {
        access.can(PERM_MANAGE, facilityId);
        LocalDate[] range = range(from, to);
        return jdbc.query(SELECT + " where s.facility_id = ? and s.shift_date between ? and ?"
                        + " order by s.shift_date, s.start_time, a.email", MAPPER,
                facilityId, Date.valueOf(range[0]), Date.valueOf(range[1]));
    }

    /** FS: ca của mình ở mọi cơ sở; mặc định tuần hiện tại. */
    @Transactional(readOnly = true)
    public List<ShiftView> myShifts(LocalDate from, LocalDate to) {
        LocalDate[] range = range(from, to);
        return jdbc.query(SELECT + " where s.staff_id = ? and s.shift_date between ? and ?"
                        + " order by s.shift_date, s.start_time", MAPPER,
                directory.currentAccountId(), Date.valueOf(range[0]), Date.valueOf(range[1]));
    }

    @Transactional
    public ShiftView create(CreateInput input) {
        if (input.facilityId() == null) {
            throw StaffingException.invalid("Thiếu cơ sở");
        }
        access.can(PERM_MANAGE, input.facilityId());
        requireValid(input.shiftDate(), input.startTime(), input.endTime(), input.note());
        if (input.staffId() == null || !staffQuery.isActiveStaffOf(input.staffId(), input.facilityId())) {
            throw StaffingException.invalid("Nhân viên không phải FS của cơ sở này");
        }
        directory.lockStaff(input.staffId());
        requireNotOnLeave(input.staffId(), input.shiftDate());
        requireNoOverlap(input.staffId(), input.shiftDate(), input.startTime(), input.endTime(), null);
        Long actorId = directory.currentAccountId();
        Long id = jdbc.queryForObject("""
                insert into staff_shifts (facility_id, staff_id, shift_date, start_time, end_time, note, created_by)
                values (?, ?, ?, ?, ?, ?, ?) returning id""", Long.class, input.facilityId(), input.staffId(),
                Date.valueOf(input.shiftDate()), Time.valueOf(input.startTime()), Time.valueOf(input.endTime()),
                blankToNull(input.note()), actorId);
        ShiftView created = find(id).orElseThrow();
        audit(AuditAction.STAFF_SHIFT_CREATED, id, null, snapshot(created), actorId);
        if (isFuture(created)) {
            notifyStaff(created, "đã thêm ca " + describe(created));
        }
        return created;
    }

    @Transactional
    public ShiftView update(Long id, UpdateInput input) {
        ShiftView peek = find(id).orElseThrow(() -> StaffingException.notFound("Không tìm thấy ca làm việc"));
        access.can(PERM_MANAGE, peek.facilityId());
        requireValid(input.shiftDate(), input.startTime(), input.endTime(), input.note());
        if (!staffQuery.isActiveStaffOf(peek.staffId(), peek.facilityId())) {
            throw StaffingException.conflict("FS không còn thuộc cơ sở — chỉ xoá được ca này");
        }
        directory.lockStaff(peek.staffId());
        ShiftView before = find(id).orElseThrow(() -> StaffingException.notFound("Không tìm thấy ca làm việc"));
        requireNotOnLeave(before.staffId(), input.shiftDate());
        requireNoOverlap(before.staffId(), input.shiftDate(), input.startTime(), input.endTime(), id);
        requireCoverageKept(before, input.shiftDate(), input.startTime(), input.endTime());
        jdbc.update("""
                update staff_shifts set shift_date = ?, start_time = ?, end_time = ?, note = ?, updated_at = now()
                where id = ?""", Date.valueOf(input.shiftDate()), Time.valueOf(input.startTime()),
                Time.valueOf(input.endTime()), blankToNull(input.note()), id);
        ShiftView after = find(id).orElseThrow();
        Long actorId = directory.currentAccountId();
        audit(AuditAction.STAFF_SHIFT_UPDATED, id, snapshot(before), snapshot(after), actorId);
        if (isFuture(before) || isFuture(after)) {
            notifyStaff(after, "đổi ca " + describe(before) + " thành " + describe(after));
        }
        return after;
    }

    @Transactional
    public void delete(Long id) {
        ShiftView peek = find(id).orElseThrow(() -> StaffingException.notFound("Không tìm thấy ca làm việc"));
        access.can(PERM_MANAGE, peek.facilityId());
        directory.lockStaff(peek.staffId());
        ShiftView before = find(id).orElseThrow(() -> StaffingException.notFound("Không tìm thấy ca làm việc"));
        requireCoverageKept(before, null, null, null);
        jdbc.update("delete from staff_shifts where id = ?", id);
        audit(AuditAction.STAFF_SHIFT_DELETED, id, snapshot(before), null, directory.currentAccountId());
        if (isFuture(before)) {
            notifyStaff(before, "đã xoá ca " + describe(before));
        }
    }

    /**
     * Duyệt nghỉ phép: xoá ca của FS trong khoảng nghỉ, mỗi ca một audit {@code STAFF_SHIFT_DELETED} (lý do nêu đơn
     * nghỉ). Người gọi đã giữ khoá ghi theo FS và đã chặn việc còn mở trong khoảng này.
     */
    void deleteForLeave(Long staffId, LocalDate from, LocalDate to, Long leaveId, Long actorId) {
        List<ShiftView> shifts = jdbc.query(SELECT + " where s.staff_id = ? and s.shift_date between ? and ?"
                + " order by s.shift_date, s.start_time", MAPPER, staffId, Date.valueOf(from), Date.valueOf(to));
        for (ShiftView shift : shifts) {
            jdbc.update("delete from staff_shifts where id = ?", shift.id());
            auditRecorder.record(new AuditEntry(AuditAction.STAFF_SHIFT_DELETED, AuditEntityType.STAFF_SHIFT,
                    String.valueOf(shift.id()), snapshot(shift), null, "Duyệt nghỉ phép #" + leaveId, actorId));
        }
    }

    Optional<ShiftView> find(Long id) {
        return jdbc.query(SELECT + " where s.id = ?", MAPPER, id).stream().findFirst();
    }

    // ------------------------------------------------------------------ kiểm tra

    private static void requireValid(LocalDate date, LocalTime start, LocalTime end, String note) {
        if (date == null || start == null || end == null) {
            throw StaffingException.invalid("Ngày, giờ bắt đầu và giờ kết thúc bắt buộc");
        }
        if (!start.isBefore(end)) {
            throw StaffingException.invalid("Giờ bắt đầu phải trước giờ kết thúc (ca trong cùng một ngày)");
        }
        if (note != null && note.length() > MAX_NOTE) {
            throw StaffingException.invalid("Ghi chú tối đa " + MAX_NOTE + " ký tự");
        }
    }

    private void requireNotOnLeave(Long staffId, LocalDate date) {
        if (directory.onApprovedLeave(staffId, date)) {
            throw StaffingException.conflict(StaffDirectory.onLeaveMessage(date) + " — không xếp ca được");
        }
    }

    /** Ca chồng giờ với ca khác của FS cùng ngày ở bất kỳ cơ sở nào → 409 (chạm mép không tính là chồng). */
    private void requireNoOverlap(Long staffId, LocalDate date, LocalTime start, LocalTime end, Long excludeId) {
        List<ShiftView> overlapping = jdbc.query(SELECT + """
                 where s.staff_id = ? and s.shift_date = ? and s.start_time < ? and s.end_time > ?
                   and s.id <> coalesce(cast(? as bigint), -1) order by s.start_time""", MAPPER, staffId,
                Date.valueOf(date), Time.valueOf(end), Time.valueOf(start), excludeId);
        if (!overlapping.isEmpty()) {
            ShiftView other = overlapping.get(0);
            throw StaffingException.conflict("Ca chồng giờ với ca " + describe(other) + " tại cơ sở "
                    + other.facilityCode() + " của cùng FS");
        }
    }

    /**
     * Lịch hẹn chờ của FS tại cơ sở trong ngày của ca cũ: lịch đang nằm trong ca cũ mà sau thay đổi (ca mới
     * {@code newDate/newStart/newEnd}, {@code null} = xoá) không còn ca nào bao trọn → 409 liệt kê lịch.
     */
    private void requireCoverageKept(ShiftView before, LocalDate newDate, LocalTime newStart, LocalTime newEnd) {
        List<TimedItem> items = openWork.stream()
                .flatMap(source -> source.timedItems(before.staffId(), before.facilityId(), before.shiftDate())
                        .stream()).toList();
        if (items.isEmpty()) {
            return;
        }
        List<ShiftView> others = jdbc.query(SELECT + """
                 where s.staff_id = ? and s.facility_id = ? and s.shift_date = ? and s.id <> ?""", MAPPER,
                before.staffId(), before.facilityId(), Date.valueOf(before.shiftDate()), before.id());
        List<String> blocked = items.stream()
                .filter(item -> covers(before.shiftDate(), before.startTime(), before.endTime(), item))
                .filter(item -> others.stream().noneMatch(s -> covers(s.shiftDate(), s.startTime(), s.endTime(),
                        item)))
                .filter(item -> newDate == null || !covers(newDate, newStart, newEnd, item))
                .map(StaffShiftService::labelWithTime).toList();
        if (!blocked.isEmpty()) {
            throw StaffingException.conflict("Ca đang bao lịch đã giao cho FS: " + String.join(", ", blocked)
                    + ". Đổi FS hoặc xử lý các lịch này trước khi xoá / thu hẹp ca");
        }
    }

    static boolean covers(LocalDate date, LocalTime start, LocalTime end, TimedItem item) {
        LocalDateTime from = LocalDateTime.ofInstant(item.start(), StaffDirectory.ZONE);
        LocalDateTime to = LocalDateTime.ofInstant(item.end(), StaffDirectory.ZONE);
        return from.toLocalDate().equals(date) && to.toLocalDate().equals(date)
                && !from.toLocalTime().isBefore(start) && !to.toLocalTime().isAfter(end);
    }

    private static String labelWithTime(TimedItem item) {
        String time = StaffDirectory.range(LocalDateTime.ofInstant(item.start(), StaffDirectory.ZONE).toLocalTime(),
                LocalDateTime.ofInstant(item.end(), StaffDirectory.ZONE).toLocalTime());
        int paren = item.label().indexOf(" (");
        return paren < 0 ? item.label() + " " + time
                : item.label().substring(0, paren) + " " + time + item.label().substring(paren);
    }

    // ------------------------------------------------------------------ nội bộ

    private static LocalDate[] range(LocalDate from, LocalDate to) {
        LocalDate start = from != null ? from
                : LocalDate.now(StaffDirectory.ZONE).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate end = to != null ? to : start.plusDays(6);
        if (end.isBefore(start) || ChronoUnit.DAYS.between(start, end) >= MAX_RANGE_DAYS) {
            throw StaffingException.invalid("Khoảng ngày không hợp lệ (tối đa " + MAX_RANGE_DAYS + " ngày)");
        }
        return new LocalDate[] {start, end};
    }

    private static boolean isFuture(ShiftView shift) {
        return shift.shiftDate().atTime(shift.startTime()).atZone(StaffDirectory.ZONE).toInstant()
                .isAfter(Instant.now());
    }

    private static String describe(ShiftView shift) {
        return StaffDirectory.range(shift.startTime(), shift.endTime()) + " ngày "
                + StaffDirectory.DATE.format(shift.shiftDate());
    }

    private void notifyStaff(ShiftView shift, String change) {
        Map<String, Object> values = new HashMap<>();
        values.put("facilityCode", shift.facilityCode());
        values.put("change", change);
        notifier.notify(shift.staffId(), NotificationRecipient.FS, NotificationType.STAFF_SHIFT_CHANGED, values);
    }

    private static Map<String, Object> snapshot(ShiftView shift) {
        Map<String, Object> value = new HashMap<>();
        value.put("facilityId", shift.facilityId());
        value.put("staffId", shift.staffId());
        value.put("shiftDate", shift.shiftDate().toString());
        value.put("startTime", StaffDirectory.TIME.format(shift.startTime()));
        value.put("endTime", StaffDirectory.TIME.format(shift.endTime()));
        value.put("note", shift.note());
        return value;
    }

    private void audit(AuditAction action, Long id, Object oldValue, Object newValue, Long actorId) {
        auditRecorder.record(new AuditEntry(action, AuditEntityType.STAFF_SHIFT, String.valueOf(id), oldValue,
                newValue, null, actorId));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
