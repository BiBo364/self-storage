package vn.lemar.selfstorage.backoffice.repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Read model back-office (chỉ đọc, nối nhiều bảng): hoá đơn theo cơ sở, doanh thu, lấp đầy, KPI, hợp đồng, audit log.
 * Cơ sở của hoá đơn = cơ sở của yêu cầu đặt kho (hoá đơn gắn đơn) hoặc của khoang trong hợp đồng (hoá đơn gắn hợp
 * đồng).
 */
@Repository
public class BackofficeReadRepository {

    private static final String INVOICE_FROM = """
            from invoices i
            left join rental_orders o on o.id = i.order_id
            left join rental_requests rq on rq.id = o.request_id
            left join rental_contracts c on c.id = i.contract_id
            left join storage_units cu on cu.id = c.unit_id
            left join storage_units ou on ou.id = o.unit_id
            join accounts a on a.id = i.customer_id
            join facilities f on f.id = coalesce(rq.facility_id, cu.facility_id)
            """;
    private static final String PAID_AT = "coalesce((select max(t.paid_at) from payment_transactions t "
            + "where t.invoice_id = i.id and t.status = 'Success'), i.created_at)";

    private final JdbcTemplate jdbc;

    public BackofficeReadRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record InvoiceRow(Long id, String code, String type, String status, String title, BigDecimal amount,
                             Instant createdAt, Instant dueDate, Instant paidAt, Long customerId,
                             String customerName, String customerEmail, String unitCode, Long facilityId,
                             String facilityName, Long orderId, Long contractId, String contractCode) {
    }

    public List<InvoiceRow> invoices(Long facilityId, String status, String type, int limit) {
        StringBuilder sql = new StringBuilder("select i.id, i.code, i.type, i.status, i.title, i.amount, i.created_at, "
                + "i.due_date, case when i.status = 'Paid' then " + PAID_AT + " end, a.id, a.name, a.email, "
                + "coalesce(cu.code, ou.code), f.id, f.name, coalesce(i.order_id, c.order_id), i.contract_id, c.code "
                + INVOICE_FROM + " where 1 = 1");
        List<Object> args = new ArrayList<>();
        if (facilityId != null) {
            sql.append(" and f.id = ?");
            args.add(facilityId);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" and i.status = ?");
            args.add(status);
        }
        if (type != null && !type.isBlank()) {
            sql.append(" and i.type = ?");
            args.add(type);
        }
        sql.append(" order by i.id desc limit ?");
        args.add(limit);
        return jdbc.query(sql.toString(), (rs, n) -> new InvoiceRow(rs.getLong(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getBigDecimal(6), instant(rs, 7), instant(rs, 8), instant(rs, 9),
                rs.getLong(10), rs.getString(11), rs.getString(12), rs.getString(13), rs.getLong(14),
                rs.getString(15), (Long) rs.getObject(16), (Long) rs.getObject(17), rs.getString(18)),
                args.toArray());
    }

    /** Doanh thu một tháng × loại hoá đơn (hoá đơn {@code Paid}, theo ngày thanh toán). */
    public record RevenueRow(String month, String type, BigDecimal amount, long count) {
    }

    public List<RevenueRow> revenue(Long facilityId, LocalDate from, LocalDate to) {
        String facilityFilter = facilityId == null ? "" : " and f.id = ?";
        List<Object> args = new ArrayList<>();
        args.add(Date.valueOf(from));
        args.add(Date.valueOf(to.plusDays(1)));
        if (facilityId != null) {
            args.add(facilityId);
        }
        return jdbc.query("select to_char(p.paid_at at time zone 'Asia/Ho_Chi_Minh', 'YYYY-MM') as month, p.type, "
                        + "sum(p.amount), count(*) from (select i.type, i.amount, " + PAID_AT + " as paid_at "
                        + INVOICE_FROM + " where i.status = 'Paid'" + facilityFilter + ") p "
                        + "where p.paid_at >= ? and p.paid_at < ? group by 1, 2 order by 1, 2",
                (rs, n) -> new RevenueRow(rs.getString(1), rs.getString(2), rs.getBigDecimal(3), rs.getLong(4)),
                reorder(args, facilityId));
    }

    /** Số khoang theo trạng thái. */
    public record OccupancyRow(Long facilityId, String facilityName, String status, long count) {
    }

    public List<OccupancyRow> occupancy(Long facilityId) {
        String filter = facilityId == null ? "" : " where f.id = ?";
        Object[] args = facilityId == null ? new Object[0] : new Object[] {facilityId};
        return jdbc.query("select f.id, f.name, u.status, count(*) from storage_units u "
                        + "join facilities f on f.id = u.facility_id" + filter + " group by 1, 2, 3 order by 1, 3",
                (rs, n) -> new OccupancyRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4)), args);
    }

    /** Chỉ số nhanh của một cơ sở (hoặc toàn hệ thống khi {@code facilityId} null). */
    public record Kpis(long pendingRequests, long activeOrders, long activeContracts, long overdueContracts,
                       long openReturnRequests, long openSupportRequests, BigDecimal unpaidAmount) {
    }

    public Kpis kpis(Long facilityId) {
        String fu = facilityId == null ? "" : " and u.facility_id = " + facilityId;
        String fr = facilityId == null ? "" : " and r.facility_id = " + facilityId;
        String fs = facilityId == null ? "" : " and s.facility_id = " + facilityId;
        BigDecimal unpaid = facilityId == null
                ? jdbc.queryForObject("select coalesce(sum(amount), 0) from invoices where status = 'Unpaid'",
                        BigDecimal.class)
                : jdbc.queryForObject("select coalesce(sum(i.amount), 0) " + INVOICE_FROM
                        + " where i.status = 'Unpaid' and f.id = ?", BigDecimal.class, facilityId);
        return new Kpis(
                count("select count(*) from rental_requests r where r.status = 'Pending'" + fr),
                count("select count(*) from rental_orders o join rental_requests r on r.id = o.request_id "
                        + "where o.status not in ('Done', 'Canceled')" + fr),
                count("select count(*) from rental_contracts c join storage_units u on u.id = c.unit_id "
                        + "where c.status = 'Active'" + fu),
                count("select count(*) from rental_contracts c join storage_units u on u.id = c.unit_id "
                        + "where c.status = 'Active' and c.end_date < current_date" + fu),
                count("select count(*) from return_requests r where r.status in ('Pending', 'Assigned')" + fr),
                count("select count(*) from support_requests s where s.status in ('Open', 'Assigned')" + fs),
                unpaid);
    }

    /** Hợp đồng của cơ sở cho FM. */
    public record ContractRow(Long id, String code, String status, Long orderId, Long customerId,
                              String customerName, String customerEmail, String customerPhone, String unitCode,
                              BigDecimal monthlyPrice, BigDecimal depositAmount, int period, LocalDate startDate,
                              LocalDate endDate, Instant signedAt, BigDecimal unpaidAmount) {
    }

    public List<ContractRow> contracts(Long facilityId, String status) {
        StringBuilder sql = new StringBuilder("""
                select c.id, c.code, c.status, c.order_id, a.id, a.name, a.email, a.phone, u.code, c.monthly_price,
                       c.deposit_amount, c.period, c.start_date, c.end_date, c.signed_at,
                       (select coalesce(sum(i.amount), 0) from invoices i
                        where i.contract_id = c.id and i.status = 'Unpaid')
                from rental_contracts c
                join storage_units u on u.id = c.unit_id
                join accounts a on a.id = c.customer_id
                where u.facility_id = ?""");
        List<Object> args = new ArrayList<>();
        args.add(facilityId);
        if (status != null && !status.isBlank()) {
            sql.append(" and c.status = ?");
            args.add(status);
        }
        sql.append(" order by c.end_date nulls last, c.id desc limit 500");
        return jdbc.query(sql.toString(), (rs, n) -> new ContractRow(rs.getLong(1), rs.getString(2),
                rs.getString(3), rs.getLong(4), rs.getLong(5), rs.getString(6), rs.getString(7), rs.getString(8),
                rs.getString(9), rs.getBigDecimal(10), rs.getBigDecimal(11), rs.getInt(12), date(rs, 13),
                date(rs, 14), instant(rs, 15), rs.getBigDecimal(16)), args.toArray());
    }

    /** Dòng audit log cho Admin / BOM. */
    public record AuditRow(Long id, String action, String entityType, String entityId, Long actorId,
                           String actorEmail, String oldValue, String newValue, String reason, Instant createdAt) {
    }

    public List<AuditRow> auditLogs(String action, String entityType, String entityId, String actor, int page,
                                    int size) {
        StringBuilder sql = new StringBuilder("select l.id, l.action, l.entity_type, l.entity_id, l.actor_account_id, "
                + "a.email, l.old_value::text, l.new_value::text, l.reason, l.created_at from audit_logs l "
                + "left join accounts a on a.id = l.actor_account_id where 1 = 1");
        List<Object> args = new ArrayList<>();
        if (action != null && !action.isBlank()) {
            sql.append(" and l.action = ?");
            args.add(action);
        }
        if (entityType != null && !entityType.isBlank()) {
            sql.append(" and l.entity_type = ?");
            args.add(entityType);
        }
        if (entityId != null && !entityId.isBlank()) {
            sql.append(" and l.entity_id = ?");
            args.add(entityId);
        }
        if (actor != null && !actor.isBlank()) {
            sql.append(" and a.email ilike ?");
            args.add("%" + actor.trim() + "%");
        }
        sql.append(" order by l.id desc limit ? offset ?");
        args.add(size);
        args.add(page * size);
        return jdbc.query(sql.toString(), (rs, n) -> new AuditRow(rs.getLong(1), rs.getString(2), rs.getString(3),
                rs.getString(4), (Long) rs.getObject(5), rs.getString(6), rs.getString(7), rs.getString(8),
                rs.getString(9), instant(rs, 10)), args.toArray());
    }

    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }

    /** Doanh thu: tham số facility nằm trong subquery nên đứng trước khoảng ngày. */
    private static Object[] reorder(List<Object> args, Long facilityId) {
        if (facilityId == null) {
            return args.toArray();
        }
        return new Object[] {facilityId, args.get(0), args.get(1)};
    }

    private static Instant instant(ResultSet rs, int column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    private static LocalDate date(ResultSet rs, int column) throws SQLException {
        Date d = rs.getDate(column);
        return d == null ? null : d.toLocalDate();
    }
}
