package vn.lemar.selfstorage.booking.repository;

import java.math.BigDecimal;
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
 * Read model tổng quan đơn thuê — mỗi dòng gom đơn + nhu cầu + khoang + proposal / hoá đơn / lịch hẹn / biên bản /
 * hợp đồng mới nhất. Chỉ đọc (JDBC), không đi qua entity của module khác.
 */
@Repository
public class OrderOverviewReadRepository {

    private static final String SELECT = """
            select o.id, o.status, o.cancel_reason, o.expires_at, o.needs_reproposal, o.customer_id,
                   r.facility_id, f.name as facility_name, f.address as facility_address,
                   r.unit_type_id, t.name as type_name, r.start_date, r.period, r.created_at as requested_at,
                   coalesce(c.name, r.customer_name) as customer_name, c.email as customer_email,
                   coalesce(c.phone, r.customer_phone) as customer_phone,
                   o.unit_id, u.code as unit_code, u.size as unit_size, u.status as unit_status,
                   coalesce(u.monthly_price, t.monthly_price) as monthly_price,
                   lp.id as proposal_id, lp.status as proposal_status,
                   li.id as invoice_id, li.code as invoice_code, li.type as invoice_type, li.status as invoice_status,
                   li.amount as invoice_amount, li.due_date as invoice_due,
                   la.id as appointment_id, la.date as appointment_date, la.started_at as appointment_start,
                   la.end_at as appointment_end, la.status as appointment_status, la.staff_id,
                   lh.id as handover_id, lh.result as handover_result, lh.due_at as handover_due,
                   lc.id as contract_id, lc.code as contract_code, lc.status as contract_status,
                   lc.start_date as contract_start, lc.end_date as contract_end
            from rental_orders o
            join rental_requests r on r.id = o.request_id
            join facilities f on f.id = r.facility_id
            join unit_types t on t.id = r.unit_type_id
            join accounts c on c.id = o.customer_id
            left join storage_units u on u.id = o.unit_id
            left join lateral (select p.id, p.status from proposal_feedbacks p where p.order_id = o.id
                               order by p.id desc limit 1) lp on true
            left join lateral (select i.id, i.code, i.type, i.status, i.amount, i.due_date from invoices i
                               where i.order_id = o.id
                               order by (i.status = 'Unpaid') desc, i.id desc limit 1) li on true
            left join lateral (select a.id, a.date, a.started_at, a.end_at, a.status, a.staff_id from appointments a
                               join rental_appointments ra on ra.appointment_id = a.id
                               where ra.order_id = o.id order by a.id desc limit 1) la on true
            left join lateral (select h.id, h.result, h.due_at from handover_records h where h.order_id = o.id
                               order by h.id desc limit 1) lh on true
            left join lateral (select k.id, k.code, k.status, k.start_date, k.end_date from rental_contracts k
                               where k.order_id = o.id order by k.id desc limit 1) lc on true
            """;

    private final JdbcTemplate jdbc;

    public OrderOverviewReadRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<OrderOverviewRow> findByCustomer(Long customerId) {
        return jdbc.query(SELECT + " where o.customer_id = ? order by o.id desc", this::map, customerId);
    }

    public Optional<OrderOverviewRow> findById(Long orderId) {
        return jdbc.query(SELECT + " where o.id = ?", this::map, orderId).stream().findFirst();
    }


    private OrderOverviewRow map(ResultSet rs, int rowNum) throws SQLException {
        return new OrderOverviewRow(rs.getLong("id"), rs.getString("status"), rs.getString("cancel_reason"),
                instant(rs, "expires_at"), rs.getBoolean("needs_reproposal"), rs.getLong("customer_id"),
                rs.getLong("facility_id"), rs.getString("facility_name"), rs.getString("facility_address"),
                rs.getLong("unit_type_id"), rs.getString("type_name"), date(rs, "start_date"), rs.getInt("period"),
                instant(rs, "requested_at"), rs.getString("customer_name"), rs.getString("customer_email"),
                rs.getString("customer_phone"), (Long) rs.getObject("unit_id"), rs.getString("unit_code"),
                rs.getString("unit_size"), rs.getString("unit_status"), rs.getBigDecimal("monthly_price"),
                (Long) rs.getObject("proposal_id"), rs.getString("proposal_status"),
                (Long) rs.getObject("invoice_id"), rs.getString("invoice_code"), rs.getString("invoice_type"),
                rs.getString("invoice_status"), rs.getBigDecimal("invoice_amount"), instant(rs, "invoice_due"),
                (Long) rs.getObject("appointment_id"), date(rs, "appointment_date"),
                instant(rs, "appointment_start"), instant(rs, "appointment_end"), rs.getString("appointment_status"),
                (Long) rs.getObject("staff_id"), (Long) rs.getObject("handover_id"), rs.getString("handover_result"),
                instant(rs, "handover_due"), (Long) rs.getObject("contract_id"), rs.getString("contract_code"),
                rs.getString("contract_status"), date(rs, "contract_start"), date(rs, "contract_end"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    private static LocalDate date(ResultSet rs, String column) throws SQLException {
        java.sql.Date d = rs.getDate(column);
        return d == null ? null : d.toLocalDate();
    }

    /** Một dòng tổng quan đơn. */
    public record OrderOverviewRow(Long id, String status, String cancelReason, Instant expiresAt,
                                   boolean needsReproposal, Long customerId, Long facilityId, String facilityName,
                                   String facilityAddress, Long unitTypeId, String unitTypeName, LocalDate startDate,
                                   int period, Instant requestedAt, String customerName, String customerEmail,
                                   String customerPhone, Long unitId, String unitCode, String unitSize,
                                   String unitStatus, BigDecimal monthlyPrice, Long proposalId, String proposalStatus,
                                   Long invoiceId, String invoiceCode, String invoiceType, String invoiceStatus,
                                   BigDecimal invoiceAmount, Instant invoiceDueDate, Long appointmentId,
                                   LocalDate appointmentDate, Instant appointmentStart, Instant appointmentEnd,
                                   String appointmentStatus, Long staffId, Long handoverId, String handoverResult,
                                   Instant handoverDueAt, Long contractId, String contractCode, String contractStatus,
                                   LocalDate contractStartDate, LocalDate contractEndDate) {
    }
}
