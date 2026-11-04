package vn.lemar.selfstorage.booking.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Đọc lịch hẹn kèm đơn, khách, khoang, FS cho màn lịch theo ngày của FM và nội dung thông báo. Đọc thẳng bảng
 * {@code accounts}, {@code storage_units}, {@code facilities} bằng SQL vì chỉ cần vài cột để hiển thị.
 */
@Repository
public class AppointmentReadRepository {

    private static final String SELECT = """
            select a.id, a.type, a.status, a.date, a.started_at, a.end_at, a.arrived_at,
                   ra.order_id, o.status as order_status, u.code as unit_code, a.facility_id,
                   coalesce(c.name, r.customer_name, c.email) as customer_name,
                   c.email as customer_email,
                   coalesce(c.phone, r.customer_phone) as customer_phone,
                   a.staff_id, s.email as staff_email,
                   f.name as facility_name, f.address as facility_address
            from appointments a
            join facilities f on f.id = a.facility_id
            join accounts c on c.id = a.customer_id
            left join accounts s on s.id = a.staff_id
            left join rental_appointments ra on ra.appointment_id = a.id
            left join rental_orders o on o.id = ra.order_id
            left join rental_requests r on r.id = o.request_id
            left join storage_units u on u.id = o.unit_id
            """;

    private final JdbcTemplate jdbc;

    public AppointmentReadRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Lịch check-in đang {@code Pending} của một cơ sở trong một ngày, theo giờ bắt đầu (chưa có giờ thì xếp
     * cuối). Lịch bàn giao / trả kho ({@code HANDOVER}, {@code RETURN}) và lịch {@code Done}/{@code Canceled}
     * không thuộc màn phân công FS nên bị loại.
     */
    public List<AppointmentRow> findByFacilityAndDate(Long facilityId, LocalDate date) {
        return jdbc.query(SELECT
                        + " where a.facility_id = ? and a.date = ? and a.type = 'CHECKIN'"
                        + " and a.status = 'Pending' order by a.started_at nulls last, a.id",
                AppointmentReadRepository::map, facilityId, date);
    }

    public Optional<AppointmentRow> findById(Long appointmentId) {
        return jdbc.query(SELECT + " where a.id = ?", AppointmentReadRepository::map, appointmentId)
                .stream().findFirst();
    }

    /** Lịch làm việc của một FS trong ngày — mọi type/status đã gán cho FS. */
    public List<AppointmentRow> findByStaffAndDate(Long staffId, LocalDate date) {
        return jdbc.query(SELECT + " where a.staff_id = ? and a.date = ?"
                        + " order by a.started_at nulls last, a.id",
                AppointmentReadRepository::map, staffId, date);
    }

    private static AppointmentRow map(ResultSet rs, int rowNum) throws SQLException {
        return new AppointmentRow(rs.getLong("id"), rs.getString("type"), rs.getString("status"),
                rs.getObject("date", LocalDate.class), instant(rs.getTimestamp("started_at")),
                instant(rs.getTimestamp("end_at")), instant(rs.getTimestamp("arrived_at")),
                (Long) rs.getObject("order_id"), rs.getString("order_status"), rs.getString("unit_code"),
                rs.getLong("facility_id"), rs.getString("customer_name"), rs.getString("customer_email"),
                rs.getString("customer_phone"), (Long) rs.getObject("staff_id"), rs.getString("staff_email"),
                rs.getString("facility_name"), rs.getString("facility_address"));
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    /** Một lịch hẹn kèm dữ liệu hiển thị; {@code orderId} null nếu lịch không nối với đơn thuê. */
    public record AppointmentRow(Long id, String type, String status, LocalDate date, Instant startedAt,
                                 Instant endAt, Instant arrivedAt, Long orderId, String orderStatus,
                                 String unitCode, Long facilityId, String customerName, String customerEmail,
                                 String customerPhone, Long staffId, String staffEmail, String facilityName,
                                 String facilityAddress) {
    }
}
