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
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Read model back-office (chỉ đọc, nối nhiều bảng): hoá đơn theo cơ sở, doanh thu, lấp đầy, KPI, hợp đồng, audit log.
 * Cơ sở của hoá đơn = cơ sở của yêu cầu đặt kho (hoá đơn gắn đơn) hoặc của khoang trong hợp đồng (hoá đơn gắn hợp
 * đồng).
 */
@Repository
public class BackofficeReadRepository {

    /** Join từ {@code invoices i} ra cơ sở (đơn → yêu cầu, hoặc hợp đồng → khoang). */
    private static final String INVOICE_JOINS = """
            left join rental_orders o on o.id = i.order_id
            left join rental_requests rq on rq.id = o.request_id
            left join rental_contracts c on c.id = i.contract_id
            left join storage_units cu on cu.id = c.unit_id
            left join storage_units ou on ou.id = o.unit_id
            join accounts a on a.id = i.customer_id
            join facilities f on f.id = coalesce(rq.facility_id, cu.facility_id)
            """;
    private static final String INVOICE_FROM = " from invoices i " + INVOICE_JOINS;
    /** Mốc thu tiền: giao dịch {@code PAY} thành công (không lấy giao dịch hoàn); đối trừ cọc không có → lúc lập. */
    private static final String PAID_AT = "coalesce((select max(t.paid_at) from payment_transactions t "
            + "where t.invoice_id = i.id and t.direction = 'PAY' and t.status = 'Success'), i.created_at)";
    /** Loại hoá đơn là doanh thu khi đã thu (cọc không phải doanh thu — là khoản giữ hộ khách). */
    private static final String EARNED_TYPES = "('Rental', 'Extension', 'Penalty', 'Service')";
    private static final String DEPOSIT_TYPES = "('Deposit', 'DepositSupplement')";
    private static final String REFUND = "t.direction = 'REFUND' and t.status = 'Success'";
    /** Loại dòng doanh thu ngoài loại hoá đơn: cọc của đơn bị huỷ mà cơ sở giữ lại. */
    public static final String FORFEITED_DEPOSIT = "ForfeitedDeposit";

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

    /**
     * Doanh thu ghi nhận một tháng × loại: {@code type} là loại hoá đơn ({@code Rental}/{@code Extension}/
     * {@code Penalty}/{@code Service}) hoặc {@link #FORFEITED_DEPOSIT}; {@code count} = số hoá đơn / số hoá đơn cọc
     * ghi nhận (dòng hoàn tiền không cộng count).
     */
    public record RevenueRow(String month, String type, BigDecimal amount, long count) {
    }

    /**
     * Doanh thu theo kế toán (giờ VN, {@code [from, to]}):
     * <ol>
     *   <li>hoá đơn thuê / gia hạn / phí / dịch vụ {@code Paid} — kể cả phí đối trừ vào cọc lúc trả kho — theo mốc
     *       thu;</li>
     *   <li>trừ khoản hoàn ghi trên các hoá đơn đó, theo mốc hoàn;</li>
     *   <li>cọc của đơn {@code Canceled} (cọc bị giữ lại) = cọc − phần đã hoàn trước lúc huỷ, theo
     *       {@code canceled_at}; hoàn sau lúc huỷ (huỷ do lỗi cơ sở) trừ vào tháng hoàn.</li>
     * </ol>
     * Cọc của đơn chưa huỷ không phải doanh thu (xem {@link Kpis#depositHeld()}).
     */
    public List<RevenueRow> revenue(Long facilityId, LocalDate from, LocalDate to) {
        String ff = facilityId == null ? "" : " and f.id = ?";
        String canceledDeposit = " i.status = 'Paid' and i.type in " + DEPOSIT_TYPES
                + " and o.status = 'Canceled' and o.canceled_at is not null";
        String sql = "select to_char(p.at at time zone 'Asia/Ho_Chi_Minh', 'YYYY-MM') as month, p.type, "
                + "sum(p.amount), sum(p.cnt) from ("
                + "select i.type, i.amount, " + PAID_AT + " as at, 1 as cnt" + INVOICE_FROM
                + " where i.status = 'Paid' and i.type in " + EARNED_TYPES + ff
                + " union all select i.type, -t.amount, t.paid_at, 0 from payment_transactions t "
                + "join invoices i on i.id = t.invoice_id " + INVOICE_JOINS
                + " where " + REFUND + " and i.type in " + EARNED_TYPES + ff
                + " union all select '" + FORFEITED_DEPOSIT + "', i.amount - coalesce((select sum(t.amount) "
                + "from payment_transactions t where t.invoice_id = i.id and " + REFUND
                + " and t.paid_at <= o.canceled_at), 0), o.canceled_at, 1" + INVOICE_FROM + " where"
                + canceledDeposit + ff
                + " union all select '" + FORFEITED_DEPOSIT + "', -t.amount, t.paid_at, 0 from payment_transactions t "
                + "join invoices i on i.id = t.invoice_id " + INVOICE_JOINS
                + " where " + REFUND + " and t.paid_at > o.canceled_at and" + canceledDeposit + ff
                + ") p where p.at >= ? and p.at < ? group by 1, 2 order by 1, 2";
        List<Object> args = new ArrayList<>();
        if (facilityId != null) {
            for (int i = 0; i < 4; i++) {
                args.add(facilityId);
            }
        }
        args.add(Date.valueOf(from));
        args.add(Date.valueOf(to.plusDays(1)));
        return jdbc.query(sql,
                (rs, n) -> new RevenueRow(rs.getString(1), rs.getString(2), rs.getBigDecimal(3), rs.getLong(4)),
                args.toArray());
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

    /**
     * Chỉ số nhanh của một cơ sở (hoặc toàn hệ thống khi {@code facilityId} null). {@code depositHeld} = cọc đang giữ
     * hộ khách tại thời điểm xem: cọc đã thu của đơn chưa huỷ − đã hoàn − phần đã đối trừ phí lúc trả kho.
     */
    public record Kpis(long pendingRequests, long activeOrders, long activeContracts, long overdueContracts,
                       long openReturnRequests, long openSupportRequests, BigDecimal unpaidAmount,
                       BigDecimal depositHeld) {
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
                unpaid, depositHeld(facilityId));
    }

    /**
     * Cọc đang giữ: cọc {@code Paid} của đơn chưa huỷ trừ khoản hoàn trên hoá đơn cọc, trừ hoá đơn phí trả kho đã
     * {@code Paid} mà không có giao dịch thu ({@code settleByDeposit} — đối trừ vào cọc).
     */
    private BigDecimal depositHeld(Long facilityId) {
        String ff = facilityId == null ? "" : " and f.id = ?";
        String sql = "select coalesce(sum(d.amount), 0) from ("
                + "select i.amount - coalesce((select sum(t.amount) from payment_transactions t "
                + "where t.invoice_id = i.id and " + REFUND + "), 0) as amount" + INVOICE_FROM
                + " where i.status = 'Paid' and i.type in " + DEPOSIT_TYPES + " and o.status <> 'Canceled'" + ff
                + " union all select -i.amount" + INVOICE_FROM + " where i.status = 'Paid' and i.type = 'Penalty' "
                + "and not exists (select 1 from payment_transactions t where t.invoice_id = i.id "
                + "and t.direction = 'PAY' and t.status = 'Success')" + ff + ") d";
        Object[] args = facilityId == null ? new Object[0] : new Object[] {facilityId, facilityId};
        return jdbc.queryForObject(sql, BigDecimal.class, args);
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

    /**
     * Một FS trong báo cáo nhân sự: việc đã hoàn tất trong khoảng (lịch check-in / trả kho {@code Done} theo
     * {@code arrived_at}, bàn giao {@code COMPLETED}, trả kho {@code COMPLETED} theo {@code completed_at}, hỗ trợ
     * {@code Resolved} theo {@code resolved_at}), giờ ca đã xếp và số ngày nghỉ phép đã duyệt rơi vào khoảng.
     */
    public record StaffReportRow(Long staffId, String name, String email, String status, long completedCheckins,
                                 long completedReturns, long handoversCompleted, long checkoutsCompleted,
                                 long supportResolved, BigDecimal shiftHours, long leaveDays) {
    }

    /**
     * Báo cáo nhân sự {@code from..to} (ngày VN, {@code start/end} = nửa đêm VN đầu / sau cuối khoảng). Có
     * {@code facilityId}: FS đang gán vào cơ sở hoặc có lịch hẹn / ca ở cơ sở trong khoảng, số liệu chỉ tính tại cơ sở
     * đó (nghỉ phép không theo cơ sở); không có: mọi tài khoản FS, toàn chuỗi.
     */
    public List<StaffReportRow> staffReport(Long facilityId, LocalDate from, LocalDate to, Instant start,
                                            Instant end) {
        boolean scoped = facilityId != null;
        String apFacility = scoped ? " and ap.facility_id = :facilityId" : "";
        String sql = """
                select a.id, a.name, a.email, a.status,
                  (select count(*) from appointments ap where ap.staff_id = a.id and ap.type = 'CHECKIN'
                     and ap.status = 'Done' and ap.arrived_at >= :start and ap.arrived_at < :end AP_F),
                  (select count(*) from appointments ap where ap.staff_id = a.id and ap.type = 'RETURN'
                     and ap.status = 'Done' and ap.arrived_at >= :start and ap.arrived_at < :end AP_F),
                  (select count(*) from handover_records h join appointments ap on ap.id = h.appointment_id
                     where ap.staff_id = a.id and h.result = 'COMPLETED'
                       and h.completed_at >= :start and h.completed_at < :end AP_F),
                  (select count(*) from checkout_records c join return_requests rr on rr.id = c.return_request_id
                     where rr.assigned_staff_id = a.id and c.result = 'COMPLETED'
                       and c.completed_at >= :start and c.completed_at < :end RR_F),
                  (select count(*) from support_requests sr where sr.assigned_staff_id = a.id
                     and sr.status = 'Resolved' and sr.resolved_at >= :start and sr.resolved_at < :end SR_F),
                  (select round(coalesce(sum(extract(epoch from (sh.end_time - sh.start_time))), 0) / 3600, 2)
                     from staff_shifts sh where sh.staff_id = a.id and sh.shift_date between :from and :to SH_F),
                  (select coalesce(sum(least(l.to_date, :to) - greatest(l.from_date, :from) + 1), 0)
                     from staff_leaves l where l.staff_id = a.id and l.status = 'Approved'
                       and l.from_date <= :to and l.to_date >= :from)
                from accounts a join roles r on r.id = a.role_id and r.name = 'FS'
                """.replace("AP_F", apFacility)
                .replace("RR_F", scoped ? " and rr.facility_id = :facilityId" : "")
                .replace("SR_F", scoped ? " and sr.facility_id = :facilityId" : "")
                .replace("SH_F", scoped ? " and sh.facility_id = :facilityId" : "")
                + (scoped ? """
                where exists (select 1 from account_facility_assignments f
                              where f.account_id = a.id and f.facility_id = :facilityId)
                   or exists (select 1 from appointments ap where ap.staff_id = a.id
                              and ap.facility_id = :facilityId and ap.date between :from and :to)
                   or exists (select 1 from staff_shifts sh where sh.staff_id = a.id
                              and sh.facility_id = :facilityId and sh.shift_date between :from and :to)
                """ : "")
                + " order by a.email";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("start", Timestamp.from(start)).addValue("end", Timestamp.from(end))
                .addValue("from", Date.valueOf(from)).addValue("to", Date.valueOf(to))
                .addValue("facilityId", facilityId);
        return new NamedParameterJdbcTemplate(jdbc).query(sql, params, (rs, n) -> new StaffReportRow(rs.getLong(1),
                rs.getString(2), rs.getString(3), rs.getString(4), rs.getLong(5), rs.getLong(6), rs.getLong(7),
                rs.getLong(8), rs.getLong(9), rs.getBigDecimal(10), rs.getLong(11)));
    }

    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
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
