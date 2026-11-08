package vn.lemar.selfstorage.payment.repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Read/write model cho case đối soát Pending; không phải state machine của transaction. */
@Repository
public class PaymentReconciliationCaseRepository {

    private final JdbcTemplate jdbc;

    public PaymentReconciliationCaseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Pending quá hạn chưa có case. */
    public List<PendingRow> findWithoutCase(Instant cutoff, int limit) {
        String sql = "select pt.id, pt.invoice_id, pt.vnp_txn_ref, pt.amount, pt.created_at "
                + "from payment_transactions pt "
                + "left join payment_reconciliation_cases c on c.transaction_id = pt.id "
                + "where pt.direction = 'PAY' and pt.status = 'Pending' "
                + "and pt.created_at <= ? and c.id is null "
                + "order by pt.created_at, pt.id limit ?";
        return jdbc.query(sql, (rs, rowNum) -> new PendingRow(
                        rs.getLong("id"), rs.getLong("invoice_id"), rs.getString("vnp_txn_ref"),
                        rs.getBigDecimal("amount"), rs.getTimestamp("created_at").toInstant()),
                java.sql.Timestamp.from(cutoff), limit);
    }

    /** Insert idempotent theo transaction; trả id nếu tạo mới. */
    public java.util.Optional<Long> createIfAbsent(Long transactionId, Long invoiceId, Instant detectedAt) {
        List<Long> ids = jdbc.queryForList("""
                insert into payment_reconciliation_cases (transaction_id, invoice_id, detected_at)
                values (?, ?, ?)
                on conflict (transaction_id) do nothing
                returning id
                """, Long.class, transactionId, invoiceId, java.sql.Timestamp.from(detectedAt));
        return ids.stream().findFirst();
    }

    public List<CaseRow> findOpenPage(Instant cutoff, long offset, int limit) {
        String sql = "select c.id, c.transaction_id, c.invoice_id, c.detected_at, c.notified_at, "
                + "c.status, c.resolution, c.gateway_transaction_no, c.paid_at, c.note, c.evidence_url, "
                + "c.resolved_at, c.resolved_by_email, pt.vnp_txn_ref, pt.amount, pt.created_at "
                + "from payment_reconciliation_cases c "
                + "join payment_transactions pt on pt.id = c.transaction_id "
                + "where c.status = 'Open' and pt.status = 'Pending' and pt.created_at <= ? "
                + "order by c.detected_at, c.id limit ? offset ?";
        return jdbc.query(sql, (rs, rowNum) -> new CaseRow(
                        rs.getLong("id"), rs.getLong("transaction_id"), rs.getLong("invoice_id"),
                        rs.getString("vnp_txn_ref"), rs.getBigDecimal("amount"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("detected_at").toInstant(),
                        rs.getTimestamp("notified_at") == null ? null : rs.getTimestamp("notified_at").toInstant(),
                        rs.getString("status"), rs.getString("resolution"),
                        rs.getString("gateway_transaction_no"),
                        rs.getTimestamp("paid_at") == null ? null : rs.getTimestamp("paid_at").toInstant(),
                        rs.getString("note"), rs.getString("evidence_url"),
                        rs.getTimestamp("resolved_at") == null ? null : rs.getTimestamp("resolved_at").toInstant(),
                        rs.getString("resolved_by_email")),
                java.sql.Timestamp.from(cutoff), limit, offset);
    }

    public List<CaseRow> findHistoryPage(long offset, int limit) {
        String sql = "select c.id, c.transaction_id, c.invoice_id, c.detected_at, c.notified_at, "
                + "c.status, c.resolution, c.gateway_transaction_no, c.paid_at, c.note, c.evidence_url, "
                + "c.resolved_at, c.resolved_by_email, pt.vnp_txn_ref, pt.amount, pt.created_at "
                + "from payment_reconciliation_cases c "
                + "join payment_transactions pt on pt.id = c.transaction_id "
                + "where c.status = 'Resolved' "
                + "order by c.resolved_at desc nulls last, c.id desc limit ? offset ?";
        return jdbc.query(sql, (rs, rowNum) -> new CaseRow(
                        rs.getLong("id"), rs.getLong("transaction_id"), rs.getLong("invoice_id"),
                        rs.getString("vnp_txn_ref"), rs.getBigDecimal("amount"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("detected_at").toInstant(),
                        rs.getTimestamp("notified_at") == null ? null : rs.getTimestamp("notified_at").toInstant(),
                        rs.getString("status"), rs.getString("resolution"),
                        rs.getString("gateway_transaction_no"),
                        rs.getTimestamp("paid_at") == null ? null : rs.getTimestamp("paid_at").toInstant(),
                        rs.getString("note"), rs.getString("evidence_url"),
                        rs.getTimestamp("resolved_at") == null ? null : rs.getTimestamp("resolved_at").toInstant(),
                        rs.getString("resolved_by_email")),
                limit, offset);
    }

    public long countResolved() {
        Long count = jdbc.queryForObject(
                "select count(*) from payment_reconciliation_cases where status = 'Resolved'",
                Long.class);
        return count == null ? 0 : count;
    }

    public long countOpen(Instant cutoff) {
        Long count = jdbc.queryForObject("""
                select count(*)
                from payment_reconciliation_cases c
                join payment_transactions pt on pt.id = c.transaction_id
                where c.status = 'Open' and pt.status = 'Pending' and pt.created_at <= ?
                """, Long.class, java.sql.Timestamp.from(cutoff));
        return count == null ? 0 : count;
    }

    /**
     * Các invoice đang có nghĩa vụ hoàn tiền. Đọc từ invoice, không từ case: một khoản cần hoàn vẫn
     * phải hiện dù chưa từng có case đối soát.
     */
    public List<RefundRow> findRefundRequiredPage(long offset, int limit) {
        String sql = """
                select i.id as invoice_id, i.code, i.order_id, i.amount, i.refund_status,
                       i.refund_reason, i.refund_requested_at,
                       pt.vnp_txn_ref, pt.gateway_transaction_no, pt.paid_at
                from invoices i
                left join lateral (
                    select pt.vnp_txn_ref, pt.gateway_transaction_no, pt.paid_at
                    from payment_transactions pt
                    where pt.invoice_id = i.id
                      and pt.direction = 'PAY'
                      and pt.status = 'Success'
                    order by pt.paid_at desc nulls last, pt.id desc
                    limit 1
                ) pt on true
                where i.refund_status = 'Required'
                order by i.refund_requested_at nulls last, i.id
                limit ? offset ?
                """;
        return jdbc.query(sql, (rs, rowNum) -> new RefundRow(
                        rs.getLong("invoice_id"), rs.getString("code"),
                        rs.getObject("order_id", Long.class), rs.getBigDecimal("amount"),
                        rs.getString("refund_status"), rs.getString("refund_reason"),
                        rs.getTimestamp("refund_requested_at") == null
                                ? null : rs.getTimestamp("refund_requested_at").toInstant(),
                        rs.getString("vnp_txn_ref"), rs.getString("gateway_transaction_no"),
                        rs.getTimestamp("paid_at") == null ? null : rs.getTimestamp("paid_at").toInstant()),
                limit, offset);
    }

    public long countRefundRequired() {
        Long count = jdbc.queryForObject(
                "select count(*) from invoices where refund_status = 'Required'", Long.class);
        return count == null ? 0 : count;
    }

    public void markNotified(Long caseId) {
        jdbc.update("update payment_reconciliation_cases set notified_at = now(), updated_at = now() "
                + "where id = ? and notified_at is null", caseId);
    }

    public void resolveByTransaction(Long transactionId, String resolution, String gatewayTransactionNo,
                                     Instant paidAt, String note, String evidenceUrl, String actorEmail) {
        jdbc.update("""
                update payment_reconciliation_cases
                set status = 'Resolved', resolved_at = now(), resolved_by_email = ?,
                    resolution = ?, gateway_transaction_no = ?, paid_at = ?, note = ?,
                    evidence_url = ?, updated_at = now()
                where transaction_id = ? and status = 'Open'
                """, actorEmail, resolution, gatewayTransactionNo, paidAt == null ? null
                        : java.sql.Timestamp.from(paidAt), note, evidenceUrl, transactionId);
    }

    public record PendingRow(Long transactionId, Long invoiceId, String vnpTxnRef,
                             BigDecimal amount, Instant createdAt) {
    }

    public record CaseRow(Long id, Long transactionId, Long invoiceId, String vnpTxnRef,
                          BigDecimal amount, Instant transactionCreatedAt, Instant detectedAt,
                          Instant notifiedAt, String status, String resolution,
                          String gatewayTransactionNo, Instant paidAt, String note,
                          String evidenceUrl, Instant resolvedAt, String resolvedByEmail) {
    }

    public record RefundRow(Long invoiceId, String invoiceCode, Long orderId, BigDecimal amount,
                            String refundStatus, String refundReason, Instant refundRequestedAt,
                            String vnpTxnRef, String gatewayTransactionNo, Instant paidAt) {
    }
}
