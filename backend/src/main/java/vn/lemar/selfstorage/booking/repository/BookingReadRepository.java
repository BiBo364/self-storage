package vn.lemar.selfstorage.booking.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
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

    /**
     * Trạng thái đơn/proposal cho từng request trong hàng đợi FM — một query cho cả trang,
     * không N+1. Request chưa convert không có entry trong map.
     */
    public Map<Long, QueueEnrichment> findQueueEnrichments(List<Long> requestIds) {
        if (requestIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", Collections.nCopies(requestIds.size(), "?"));
        String sql = "select o.request_id, o.id as order_id, o.status as order_status, o.needs_reproposal, "
                + "p.id as last_proposal_id, u.code as last_unit_code, p.status as last_proposal_status, "
                + "p.note as last_note, p.created_at as last_created_at, "
                + "(select count(*) from proposal_feedbacks x where x.order_id = o.id "
                + "and x.status = 'Rejected') as rejection_count, "
                + "(select count(*) from proposal_feedbacks x where x.order_id = o.id "
                + "and x.status = 'Pending') as pending_count "
                + "from rental_orders o "
                + "left join lateral (select p.id, p.unit_id, p.status, p.note, p.created_at "
                + "from proposal_feedbacks p where p.order_id = o.id "
                + "order by p.created_at desc, p.id desc limit 1) p on true "
                + "left join storage_units u on u.id = p.unit_id "
                + "where o.request_id in (" + placeholders + ")";
        Map<Long, QueueEnrichment> result = new HashMap<>();
        jdbc.query(sql, (RowCallbackHandler) rs -> result.put(rs.getLong("request_id"), new QueueEnrichment(
                rs.getLong("request_id"), rs.getLong("order_id"), rs.getString("order_status"),
                rs.getBoolean("needs_reproposal"), (Long) rs.getObject("last_proposal_id"),
                rs.getString("last_unit_code"), rs.getString("last_proposal_status"), rs.getString("last_note"),
                instant(rs, "last_created_at"), rs.getLong("rejection_count"), rs.getLong("pending_count"))),
                requestIds.toArray());
        return result;
    }

    /** Đơn + thông tin form yêu cầu; rỗng nếu đơn không tồn tại. */
    public Optional<OrderRow> findOrder(Long orderId) {
        String sql = "select o.id, o.status, o.cancel_reason, o.expires_at, o.unit_id, o.customer_id, "
                + "o.needs_reproposal, r.id as request_id, r.status as request_status, r.facility_id, r.customer_name, "
                + "r.normalized_customer_email, r.customer_phone, r.unit_type_id, t.name as type_name, "
                + "r.start_date, r.period "
                + "from rental_orders o join rental_requests r on r.id = o.request_id "
                + "join unit_types t on t.id = r.unit_type_id where o.id = ?";
        return jdbc.query(sql, (rs, rowNum) -> new OrderRow(
                rs.getLong("id"), rs.getString("status"), rs.getString("cancel_reason"), instant(rs, "expires_at"),
                (Long) rs.getObject("unit_id"), rs.getLong("customer_id"), rs.getLong("facility_id"),
                rs.getLong("request_id"), rs.getString("request_status"), rs.getBoolean("needs_reproposal"),
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

    /** Một dòng đơn thuê đã join form yêu cầu và trạng thái request. */
    public record OrderRow(Long id, String status, String cancelReason, Instant expiresAt, Long unitId,
                           Long customerId, Long facilityId, Long requestId, String requestStatus,
                           boolean needsReproposal, FmRentalOrderResponse.Customer customer,
                           FmRentalOrderResponse.Requested requested) {
    }

    /** Trạng thái đơn/proposal gắn với một request đã convert. */
    public record QueueEnrichment(Long requestId, Long orderId, String orderStatus, boolean needsReproposal,
                                  Long lastProposalId, String lastProposalUnitCode, String lastProposalStatus,
                                  String lastProposalNote, Instant lastProposalCreatedAt, long rejectionCount,
                                  long pendingProposalCount) {
    }
}
