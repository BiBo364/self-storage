package vn.lemar.selfstorage.booking.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import vn.lemar.selfstorage.booking.application.InvoiceListView;
import vn.lemar.selfstorage.booking.application.dto.CustomerProposalResponse;
import vn.lemar.selfstorage.booking.application.dto.FmRentalOrderResponse;

/**
 * Read model cho màn hình (danh sách proposal của khách, chi tiết đơn của FM). Đọc bằng SQL join thẳng
 * các bảng khoang/loại khoang/cơ sở/yêu cầu thay vì map entity của module khác — chỉ đọc, không ghi.
 */
@Repository
public class BookingReadRepository {

    private static final String EFFECTIVE_PRICE = "coalesce(u.monthly_price, t.monthly_price)";

    private final JdbcTemplate jdbc;

    public BookingReadRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @param status {@code null} = mọi trạng thái; giá trị PascalCase như trong DB */
    public List<CustomerProposalResponse> findCustomerProposals(Long customerId, String status, int maxRejection) {
        String sql = "select p.id, p.order_id, p.status, p.note, p.created_at, u.id as unit_id, u.code, u.size, "
                + "t.name as type_name, f.id as facility_id, f.name as facility_name, f.address, "
                + EFFECTIVE_PRICE + " as price, "
                + "(select count(*) from proposal_feedbacks r where r.order_id = p.order_id "
                + "and r.status = 'Rejected') as rejections "
                + "from proposal_feedbacks p "
                + "join storage_units u on u.id = p.unit_id "
                + "join unit_types t on t.id = u.unit_type_id "
                + "join facilities f on f.id = u.facility_id "
                + "where p.customer_id = ? and (cast(? as varchar) is null or p.status = ?) "
                + "order by p.created_at desc, p.id desc";
        return jdbc.query(sql, (rs, rowNum) -> new CustomerProposalResponse(
                rs.getLong("id"), rs.getLong("order_id"), rs.getString("status"), rs.getString("note"),
                instant(rs, "created_at"), rs.getLong("unit_id"), rs.getString("code"), rs.getString("size"),
                rs.getString("type_name"), rs.getLong("facility_id"), rs.getString("facility_name"),
                rs.getString("address"), rs.getBigDecimal("price"), rs.getLong("rejections"), maxRejection),
                customerId, status, status);
    }

    /** Hoá đơn của một khách, mới nhất trước; join đơn/khoang/cơ sở để FE hiển thị. */
    public List<InvoiceListView> findCustomerInvoices(Long customerId) {
        String sql = "select i.id, i.order_id, i.code, i.title, i.description, i.type, i.status, i.amount, "
                + "i.created_at, i.due_date, o.unit_id, u.code as unit_code, f.id as facility_id, "
                + "f.name as facility_name "
                + "from invoices i "
                + "left join rental_orders o on o.id = i.order_id "
                + "left join storage_units u on u.id = o.unit_id "
                + "left join facilities f on f.id = u.facility_id "
                + "where i.customer_id = ? "
                + "order by i.created_at desc, i.id desc";
        return jdbc.query(sql, (rs, rowNum) -> new InvoiceListView(
                rs.getLong("id"), (Long) rs.getObject("order_id"), rs.getString("code"), rs.getString("title"),
                rs.getString("description"), rs.getString("type"), rs.getString("status"),
                rs.getBigDecimal("amount"), instant(rs, "created_at"), instant(rs, "due_date"),
                (Long) rs.getObject("unit_id"), rs.getString("unit_code"), (Long) rs.getObject("facility_id"),
                rs.getString("facility_name")),
                customerId);
    }

    private static final String FACILITY_INVOICE_SELECT =
            "select i.id, i.order_id, i.code, i.title, i.description, i.type, i.status, i.amount, "
                    + "i.created_at, i.due_date, coalesce(o.unit_id, c.unit_id) as unit_id, "
                    + "u.code as unit_code, f.id as facility_id, f.name as facility_name, "
                    + "i.customer_id, a.email as customer_email, "
                    + "coalesce(r.customer_name, a.email) as customer_name, "
                    + "p.status as payment_status, p.vnp_txn_ref, p.created_at as payment_created_at, p.paid_at "
                    + "from invoices i "
                    + "left join rental_orders o on o.id = i.order_id "
                    + "left join rental_contracts c on c.id = i.contract_id "
                    + "left join storage_units u on u.id = coalesce(o.unit_id, c.unit_id) "
                    + "left join facilities f on f.id = u.facility_id "
                    + "join accounts a on a.id = i.customer_id "
                    + "left join rental_requests r on r.id = o.request_id "
                    + "left join lateral ("
                    + "  select pt.status, pt.vnp_txn_ref, pt.created_at, pt.paid_at "
                    + "  from payment_transactions pt where pt.invoice_id = i.id and pt.direction = 'PAY' "
                    + "  order by pt.created_at desc, pt.id desc limit 1"
                    + ") p on true ";

    private static final String FACILITY_INVOICE_WHERE =
            "where f.id = ? "
                    + "and (cast(? as varchar) is null or i.status = ?) "
                    + "and (cast(? as varchar) is null or i.type = ?) "
                    + "and (cast(? as varchar) is null or p.status = ?) "
                    + "and (cast(? as varchar) is null or "
                    + "(p.status = 'Pending' and p.created_at <= now() - (cast(? as bigint) * interval '1 minute'))) ";

    /** Một trang hoá đơn của cơ sở, mới nhất trước; join đơn/hợp đồng/khoang/khách/payment. */
    public List<FacilityInvoiceRow> findFacilityInvoices(Long facilityId, String status, String type,
                                                         String paymentStatus, Long olderThanMinutes,
                                                         long offset, int limit) {
        String sql = FACILITY_INVOICE_SELECT + FACILITY_INVOICE_WHERE
                + "order by i.created_at desc, i.id desc limit ? offset ?";
        return jdbc.query(sql, (rs, rowNum) -> new FacilityInvoiceRow(
                rs.getLong("id"), (Long) rs.getObject("order_id"), rs.getString("code"),
                rs.getString("title"), rs.getString("description"), rs.getString("type"),
                rs.getString("status"), rs.getBigDecimal("amount"), instant(rs, "created_at"),
                instant(rs, "due_date"), (Long) rs.getObject("unit_id"), rs.getString("unit_code"),
                rs.getLong("facility_id"), rs.getString("facility_name"), rs.getLong("customer_id"),
                rs.getString("customer_name"), rs.getString("customer_email"),
                rs.getString("payment_status"), rs.getString("vnp_txn_ref"),
                instant(rs, "payment_created_at"), instant(rs, "paid_at")),
                facilityId, status, status, type, type, paymentStatus, paymentStatus,
                olderThanMinutes, olderThanMinutes, limit, offset);
    }

    public long countFacilityInvoices(Long facilityId, String status, String type,
                                      String paymentStatus, Long olderThanMinutes) {
        String sql = "select count(*) from (" + FACILITY_INVOICE_SELECT + FACILITY_INVOICE_WHERE + ") counted";
        Long count = jdbc.queryForObject(sql, Long.class, facilityId, status, status, type, type,
                paymentStatus, paymentStatus, olderThanMinutes, olderThanMinutes);
        return count == null ? 0 : count;
    }

    /** Đơn + thông tin form yêu cầu; rỗng nếu đơn không tồn tại. */
    public Optional<OrderRow> findOrder(Long orderId) {
        String sql = "select o.id, o.status, o.cancel_reason, o.expires_at, o.unit_id, o.customer_id, "
                + "r.facility_id, r.customer_name, r.normalized_customer_email, r.customer_phone, "
                + "r.unit_type_id, t.name as type_name, r.start_date, r.period "
                + "from rental_orders o join rental_requests r on r.id = o.request_id "
                + "join unit_types t on t.id = r.unit_type_id where o.id = ?";
        return jdbc.query(sql, (rs, rowNum) -> new OrderRow(
                rs.getLong("id"), rs.getString("status"), rs.getString("cancel_reason"), instant(rs, "expires_at"),
                (Long) rs.getObject("unit_id"), rs.getLong("customer_id"), rs.getLong("facility_id"),
                new FmRentalOrderResponse.Customer(rs.getLong("customer_id"), rs.getString("customer_name"),
                        rs.getString("normalized_customer_email"), rs.getString("customer_phone")),
                new FmRentalOrderResponse.Requested(rs.getLong("unit_type_id"), rs.getString("type_name"),
                        rs.getDate("start_date").toLocalDate(), rs.getInt("period"))),
                orderId).stream().findFirst();
    }

    public List<FmRentalOrderResponse.Proposal> findOrderProposals(Long orderId) {
        return jdbc.query("select p.id, p.unit_id, u.code, p.status, p.note, p.created_at "
                        + "from proposal_feedbacks p join storage_units u on u.id = p.unit_id "
                        + "where p.order_id = ? order by p.created_at desc, p.id desc",
                (rs, rowNum) -> new FmRentalOrderResponse.Proposal(rs.getLong("id"), rs.getLong("unit_id"),
                        rs.getString("code"), rs.getString("status"), rs.getString("note"),
                        instant(rs, "created_at")),
                orderId);
    }

    /** Khoang {@code Available} cùng cơ sở, cùng loại; đánh dấu khoang khách đã từ chối trong đơn. */
    public List<FmRentalOrderResponse.AvailableUnit> findAvailableUnits(Long orderId, Long facilityId,
                                                                        Long unitTypeId) {
        return jdbc.query("select u.id, u.code, u.size, " + EFFECTIVE_PRICE + " as price, "
                        + "exists(select 1 from proposal_feedbacks r where r.order_id = ? and r.unit_id = u.id "
                        + "and r.status = 'Rejected') as rejected "
                        + "from storage_units u join unit_types t on t.id = u.unit_type_id "
                        + "where u.facility_id = ? and u.unit_type_id = ? and u.status = 'Available' order by u.code",
                (rs, rowNum) -> new FmRentalOrderResponse.AvailableUnit(rs.getLong("id"), rs.getString("code"),
                        rs.getString("size"), rs.getBigDecimal("price"), rs.getBoolean("rejected")),
                orderId, facilityId, unitTypeId);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    /** Một dòng hoá đơn của cơ sở, kèm giao dịch PAY gần nhất. */
    public record FacilityInvoiceRow(Long id, Long orderId, String code, String title, String description,
                                     String type, String status, java.math.BigDecimal amount,
                                     Instant createdAt, Instant dueDate, Long unitId, String unitCode,
                                     Long facilityId, String facilityName, Long customerId,
                                     String customerName, String customerEmail, String paymentStatus,
                                     String vnpTxnRef, Instant paymentCreatedAt, Instant paidAt) {
    }

    /** Một dòng đơn thuê đã join form yêu cầu. */
    public record OrderRow(Long id, String status, String cancelReason, Instant expiresAt, Long unitId,
                           Long customerId, Long facilityId, FmRentalOrderResponse.Customer customer,
                           FmRentalOrderResponse.Requested requested) {
    }
}
