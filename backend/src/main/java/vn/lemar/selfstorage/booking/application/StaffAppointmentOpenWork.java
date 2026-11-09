package vn.lemar.selfstorage.booking.application;

import java.sql.Date;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import vn.lemar.selfstorage.identity.application.StaffOpenWork;

/**
 * Lịch hẹn {@code Pending} đã phân cho FS tại cơ sở (check-in, trả kho). Lịch trả kho đi kèm yêu cầu trả kho
 * {@code Assigned}, nên yêu cầu đó không cần liệt kê riêng.
 */
@Component
class StaffAppointmentOpenWork implements StaffOpenWork {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final JdbcTemplate jdbc;

    StaffAppointmentOpenWork(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<String> openItems(Long staffId, Long facilityId) {
        return jdbc.query("""
                select id, type from appointments
                where staff_id = ? and facility_id = ? and status = 'Pending' order by id""",
                (rs, rowNum) -> label(rs.getString(2), rs.getLong(1)), staffId, facilityId);
    }

    @Override
    public List<String> openItemsBetween(Long staffId, LocalDate from, LocalDate to) {
        return jdbc.query("""
                select id, type, date from appointments
                where staff_id = ? and status = 'Pending' and date between ? and ? order by date, id""",
                (rs, rowNum) -> withDate(label(rs.getString(2), rs.getLong(1)), rs.getDate(3).toLocalDate()),
                staffId, Date.valueOf(from), Date.valueOf(to));
    }

    @Override
    public OpenCounts openCounts(Long staffId, Long facilityId, LocalDate date) {
        return jdbc.queryForObject("""
                select count(*) filter (where type = 'CHECKIN'), count(*) filter (where type = 'RETURN')
                from appointments where staff_id = ? and facility_id = ? and status = 'Pending' and date = ?""",
                (rs, rowNum) -> new OpenCounts(rs.getInt(1), rs.getInt(2), 0), staffId, facilityId,
                Date.valueOf(date));
    }

    @Override
    public List<TimedItem> timedItems(Long staffId, Long facilityId, LocalDate date) {
        return jdbc.query("""
                select id, type, started_at, end_at from appointments
                where staff_id = ? and facility_id = ? and status = 'Pending' and date = ? order by started_at, id""",
                (rs, rowNum) -> new TimedItem(label(rs.getString(2), rs.getLong(1)),
                        rs.getTimestamp(3).toInstant(), rs.getTimestamp(4).toInstant()),
                staffId, facilityId, Date.valueOf(date));
    }

    private static String label(String type, long id) {
        return switch (type) {
            case "CHECKIN" -> "lịch check-in #" + id + " (FM đổi FS)";
            case "RETURN" -> "lịch trả kho #" + id + " (FM phân công lại yêu cầu trả kho)";
            default -> "lịch bàn giao #" + id + " (FM đổi FS)";
        };
    }

    /** Chèn ngày hẹn trước phần "(cách gỡ chặn)" của nhãn. */
    private static String withDate(String label, LocalDate date) {
        int paren = label.indexOf(" (");
        return label.substring(0, paren) + " ngày " + DATE.format(date) + label.substring(paren);
    }
}
