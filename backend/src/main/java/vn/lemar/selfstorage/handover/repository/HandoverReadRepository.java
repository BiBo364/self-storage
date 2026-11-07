package vn.lemar.selfstorage.handover.repository;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Read model phiên bàn giao: nối {@code handover_records} với lịch hẹn, đơn, khách, khoang, cơ sở. Chỉ đọc — ghi luôn
 * đi qua entity + public API của module sở hữu.
 */
@Repository
public class HandoverReadRepository {

    private static final String SELECT = """
            select h.id, h.order_id, h.appointment_id, h.unit_id, h.identity_status, h.identity_verified_at,
                   h.inspection_status, h.unit_inspected_at, h.inspection_notes, h.inspection_photos, h.result,
                   h.reject_reason, h.due_at, h.completed_at, h.requested_access, h.customer_confirmed_at,
                   a.date, a.started_at, a.end_at, a.arrived_at, a.staff_id, a.facility_id,
                   o.status as order_status, o.customer_id, o.expires_at,
                   acc.name as customer_name, acc.email as customer_email, acc.phone as customer_phone,
                   u.code as unit_code, t.name as unit_type, f.name as facility_name, f.address as facility_address
            from handover_records h
            join appointments a on a.id = h.appointment_id
            join rental_orders o on o.id = h.order_id
            join accounts acc on acc.id = o.customer_id
            join storage_units u on u.id = h.unit_id
            join unit_types t on t.id = u.unit_type_id
            join facilities f on f.id = a.facility_id
            """;

    private final JdbcTemplate jdbc;

    public HandoverReadRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Một dòng phiên bàn giao. */
    public record HandoverRow(Long id, Long orderId, Long appointmentId, Long unitId, String identityStatus,
                              Instant identityVerifiedAt, String inspectionStatus, Instant unitInspectedAt,
                              String inspectionNotes, List<String> inspectionPhotos, String result,
                              String rejectReason, Instant dueAt, Instant completedAt, String requestedAccess,
                              Instant customerConfirmedAt, LocalDate appointmentDate, Instant appointmentStart,
                              Instant appointmentEnd, Instant arrivedAt, Long staffId, Long facilityId,
                              String orderStatus, Long customerId, Instant orderExpiresAt, String customerName,
                              String customerEmail, String customerPhone, String unitCode, String unitType,
                              String facilityName, String facilityAddress) {
    }

    public Optional<HandoverRow> findById(Long id) {
        return jdbc.query(SELECT + " where h.id = ?", HandoverReadRepository::map, id).stream().findFirst();
    }

    /** "Đơn đang bàn giao" của FS: phiên đang mở, khách đã đến, lịch được phân cho FS. */
    public List<HandoverRow> findOpenArrivedByStaff(Long staffId) {
        return jdbc.query(SELECT + " where a.staff_id = ? and h.result = 'IN_PROGRESS' and a.arrived_at is not null "
                + "order by a.arrived_at", HandoverReadRepository::map, staffId);
    }

    /** Phiên bàn giao của khách — đang mở trước, sau đó các phiên đã đóng gần nhất. */
    public List<HandoverRow> findByCustomer(Long customerId) {
        return jdbc.query(SELECT + " where o.customer_id = ? "
                        + "order by (h.result = 'IN_PROGRESS') desc, h.id desc limit 50",
                HandoverReadRepository::map, customerId);
    }

    /** Số tháng thuê của đơn ({@code rental_requests.period}). */
    public Optional<Integer> findPeriod(Long orderId) {
        return jdbc.query("select r.period from rental_orders o join rental_requests r on r.id = o.request_id "
                + "where o.id = ?", (rs, i) -> rs.getInt(1), orderId).stream().findFirst();
    }

    /** Dữ liệu điền mẫu hợp đồng. */
    public record ContractDocumentRow(Long contractId, String code, String status, String termsVersion,
                                      BigDecimal monthlyPrice, BigDecimal depositAmount, int period,
                                      LocalDate startDate, LocalDate endDate, String overrideStatus,
                                      Instant signedAt, Long orderId, Long customerId, String customerName,
                                      String customerEmail, String customerPhone, String unitCode, String unitType,
                                      BigDecimal unitArea, Long facilityId, String facilityName,
                                      String facilityAddress, Long staffId, String inspectionNotes) {
    }

    public Optional<ContractDocumentRow> findContractDocument(Long contractId) {
        return jdbc.query("""
                select c.id, c.code, c.status, c.terms_version, c.monthly_price, c.deposit_amount, c.period,
                       c.start_date, c.end_date, c.start_date_override_status, c.signed_at, c.order_id, c.customer_id,
                       acc.name, acc.email, acc.phone, u.code as unit_code, t.name as unit_type, t.area,
                       f.id as facility_id, f.name as facility_name, f.address,
                       (select a.staff_id from handover_records h join appointments a on a.id = h.appointment_id
                         where h.order_id = c.order_id order by h.id desc limit 1) as staff_id,
                       (select h.inspection_notes from handover_records h
                         where h.order_id = c.order_id order by h.id desc limit 1) as inspection_notes
                from rental_contracts c
                join accounts acc on acc.id = c.customer_id
                join storage_units u on u.id = c.unit_id
                join unit_types t on t.id = u.unit_type_id
                join facilities f on f.id = u.facility_id
                where c.id = ?
                """, (rs, i) -> new ContractDocumentRow(rs.getLong("id"), rs.getString("code"),
                rs.getString("status"), rs.getString("terms_version"), rs.getBigDecimal("monthly_price"),
                rs.getBigDecimal("deposit_amount"), rs.getInt("period"), localDate(rs, "start_date"),
                localDate(rs, "end_date"), rs.getString("start_date_override_status"), instant(rs, "signed_at"),
                rs.getLong("order_id"), rs.getLong("customer_id"), rs.getString("name"), rs.getString("email"),
                rs.getString("phone"), rs.getString("unit_code"), rs.getString("unit_type"),
                rs.getBigDecimal("area"), rs.getLong("facility_id"), rs.getString("facility_name"),
                rs.getString("address"), nullableLong(rs, "staff_id"), rs.getString("inspection_notes")), contractId)
                .stream().findFirst();
    }

    /** Đề nghị đổi mốc tính tiền đang chờ của các cơ sở FM phụ trách. */
    public record OverrideRequestRow(Long contractId, String contractCode, Long orderId, String unitCode,
                                     String customerName, LocalDate startDate, LocalDate requestedDate,
                                     String reason, String facilityName) {
    }

    public List<OverrideRequestRow> findPendingOverridesForManager(Long fmAccountId) {
        return jdbc.query("""
                select c.id, c.code, c.order_id, u.code as unit_code, acc.name, c.start_date,
                       c.start_date_override_requested, c.start_date_override_reason, f.name as facility_name
                from rental_contracts c
                join storage_units u on u.id = c.unit_id
                join facilities f on f.id = u.facility_id
                join accounts acc on acc.id = c.customer_id
                where c.status = 'Draft' and c.start_date_override_status = 'Pending' and f.fm_account_id = ?
                order by c.id
                """, (rs, i) -> new OverrideRequestRow(rs.getLong(1), rs.getString(2), rs.getLong(3),
                rs.getString(4), rs.getString(5), localDate(rs, "start_date"),
                localDate(rs, "start_date_override_requested"), rs.getString(8), rs.getString(9)), fmAccountId);
    }

    /** Cơ sở của hợp đồng (qua khoang). */
    public Optional<Long> findContractFacilityId(Long contractId) {
        return jdbc.query("select u.facility_id from rental_contracts c join storage_units u on u.id = c.unit_id "
                + "where c.id = ?", (rs, i) -> rs.getLong(1), contractId).stream().findFirst();
    }

    private static HandoverRow map(ResultSet rs, int rowNum) throws SQLException {
        return new HandoverRow(rs.getLong("id"), rs.getLong("order_id"), rs.getLong("appointment_id"),
                rs.getLong("unit_id"), rs.getString("identity_status"), instant(rs, "identity_verified_at"),
                rs.getString("inspection_status"), instant(rs, "unit_inspected_at"), rs.getString("inspection_notes"),
                photos(rs.getArray("inspection_photos")), rs.getString("result"), rs.getString("reject_reason"),
                instant(rs, "due_at"), instant(rs, "completed_at"), rs.getString("requested_access"),
                instant(rs, "customer_confirmed_at"), localDate(rs, "date"), instant(rs, "started_at"),
                instant(rs, "end_at"), instant(rs, "arrived_at"), nullableLong(rs, "staff_id"),
                rs.getLong("facility_id"), rs.getString("order_status"), rs.getLong("customer_id"),
                instant(rs, "expires_at"), rs.getString("customer_name"), rs.getString("customer_email"),
                rs.getString("customer_phone"), rs.getString("unit_code"), rs.getString("unit_type"),
                rs.getString("facility_name"), rs.getString("facility_address"));
    }

    private static List<String> photos(Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        return Arrays.asList((String[]) array.getArray());
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    private static LocalDate localDate(ResultSet rs, String column) throws SQLException {
        java.sql.Date date = rs.getDate(column);
        return date == null ? null : date.toLocalDate();
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
