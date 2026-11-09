package vn.lemar.selfstorage.staffing.application;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.sql.Date;
import java.sql.Time;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.lemar.selfstorage.identity.application.StaffOpenWork;
import vn.lemar.selfstorage.identity.application.StaffOpenWork.OpenCounts;

/**
 * Public API cho module giao việc: FS có nhận được việc không (ca làm việc + nghỉ phép), và trạng thái ngày của FS cho
 * màn phân công của FM. Mọi lệnh phân công (check-in, đổi FS, trả kho, hỗ trợ) gọi ngay sau
 * {@code FacilityStaffQuery.isActiveStaffOf}; trả rỗng = được giao, có message = 409.
 *
 * <p>Nghỉ phép đã duyệt luôn chặn; ca làm việc chỉ bắt buộc khi policy {@code staff.shift_required} = {@code true}.
 */
@Service
public class StaffAvailability {

    private final JdbcTemplate jdbc;
    private final StaffDirectory directory;
    private final List<StaffOpenWork> openWork;

    StaffAvailability(JdbcTemplate jdbc, StaffDirectory directory, List<StaffOpenWork> openWork) {
        this.jdbc = jdbc;
        this.directory = directory;
        this.openWork = List.copyOf(openWork);
    }

    /** Khung giờ một ca, định dạng {@code HH:mm}. */
    public record ShiftTime(@JsonFormat(pattern = "HH:mm") LocalTime startTime,
                            @JsonFormat(pattern = "HH:mm") LocalTime endTime) {
    }

    /** Việc còn mở trong ngày: lịch check-in, lịch trả kho. */
    public record DayWork(int checkins, int returns) {
    }

    /**
     * Trạng thái của FS tại cơ sở trong một ngày: ca đầu tiên ({@code null} nếu không có ca), mọi ca, đang nghỉ phép,
     * việc còn mở trong ngày và yêu cầu hỗ trợ đang giao (không gắn ngày).
     */
    public record StaffDay(ShiftTime shift, List<ShiftTime> shifts, boolean onLeave, DayWork openWork,
                           int openSupport) {
    }

    /**
     * Lịch hẹn {@code [start, end]} (check-in, trả kho): FS không nghỉ phép ngày hẹn và — khi policy bật — có ca tại
     * cơ sở trong ngày hẹn bao trọn khung giờ (giờ VN). Khoá đọc theo FS tới hết transaction của người gọi.
     */
    @Transactional
    public Optional<String> appointmentViolation(Long staffId, Long facilityId, Instant start, Instant end) {
        directory.lockStaffShared(staffId);
        LocalDateTime from = LocalDateTime.ofInstant(start, StaffDirectory.ZONE);
        LocalDateTime to = LocalDateTime.ofInstant(end, StaffDirectory.ZONE);
        LocalDate date = from.toLocalDate();
        if (directory.onApprovedLeave(staffId, date)) {
            return Optional.of(StaffDirectory.onLeaveMessage(date));
        }
        if (!directory.shiftRequired()) {
            return Optional.empty();
        }
        boolean covered = to.toLocalDate().equals(date) && Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from staff_shifts
                              where staff_id = ? and facility_id = ? and shift_date = ?
                                and start_time <= ? and end_time >= ?)""",
                Boolean.class, staffId, facilityId, Date.valueOf(date), Time.valueOf(from.toLocalTime()),
                Time.valueOf(to.toLocalTime())));
        if (covered) {
            return Optional.empty();
        }
        return Optional.of("FS không có ca làm việc tại cơ sở bao trọn giờ hẹn "
                + StaffDirectory.range(from.toLocalTime(), to.toLocalTime()) + " ngày "
                + StaffDirectory.DATE.format(date));
    }

    /**
     * Việc không gắn giờ (yêu cầu hỗ trợ): FS không nghỉ phép hôm nay và — khi policy bật — có ca tại cơ sở hôm nay.
     */
    @Transactional
    public Optional<String> todayViolation(Long staffId, Long facilityId) {
        directory.lockStaffShared(staffId);
        LocalDate today = LocalDate.now(StaffDirectory.ZONE);
        if (directory.onApprovedLeave(staffId, today)) {
            return Optional.of(StaffDirectory.onLeaveMessage(today));
        }
        if (!directory.shiftRequired() || !shifts(staffId, facilityId, today).isEmpty()) {
            return Optional.empty();
        }
        return Optional.of("FS không có ca làm việc tại cơ sở hôm nay (" + StaffDirectory.DATE.format(today) + ")");
    }

    @Transactional(readOnly = true)
    public StaffDay day(Long staffId, Long facilityId, LocalDate date) {
        List<ShiftTime> shifts = shifts(staffId, facilityId, date);
        OpenCounts counts = openWork.stream().map(source -> source.openCounts(staffId, facilityId, date))
                .reduce(OpenCounts.NONE, OpenCounts::plus);
        return new StaffDay(shifts.isEmpty() ? null : shifts.get(0), shifts,
                directory.onApprovedLeave(staffId, date), new DayWork(counts.checkins(), counts.returns()),
                counts.support());
    }

    private List<ShiftTime> shifts(Long staffId, Long facilityId, LocalDate date) {
        return jdbc.query("""
                select start_time, end_time from staff_shifts
                where staff_id = ? and facility_id = ? and shift_date = ? order by start_time""",
                (rs, i) -> new ShiftTime(rs.getObject(1, LocalTime.class), rs.getObject(2, LocalTime.class)),
                staffId, facilityId, Date.valueOf(date));
    }
}
