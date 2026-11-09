package vn.lemar.selfstorage.booking.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Hoá đơn ({@code specs/db-table-draft.md} › Invoice). B3 chỉ tạo hoá đơn {@code Deposit} gắn đơn thuê
 * và huỷ hoá đơn {@code Unpaid} trong Cancellation Cascade; thanh toán qua {@code InvoicePaymentService}.
 *
 * <p>CHECK của V1 buộc đúng một trong hai FK được set: hoá đơn cọc gắn {@code order_id}, hoá đơn tháng đầu
 * (Flow 2.3) và các hoá đơn sau khi có hợp đồng gắn {@code contract_id}.
 */
@Entity
@Table(name = "invoices")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Invoice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id")
    private Long orderId;

    @Column(name = "contract_id")
    private Long contractId;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(nullable = false, unique = true, length = 50)
    private String code;

    @Column(nullable = false)
    private String title;

    @Convert(converter = InvoiceTypeConverter.class)
    @Column(nullable = false, length = 20)
    private InvoiceType type;

    @Column
    private String description;

    @Convert(converter = InvoiceStatusConverter.class)
    @Column(nullable = false, length = 20)
    private InvoiceStatus status;

    @Convert(converter = InvoiceRefundStatusConverter.class)
    @Column(name = "refund_status", nullable = false, length = 20)
    private InvoiceRefundStatus refundStatus = InvoiceRefundStatus.NONE;

    @Column(name = "refund_reason", length = 50)
    private String refundReason;

    @Column(name = "refund_requested_at")
    private Instant refundRequestedAt;

    @Column(name = "refunded_at")
    private Instant refundedAt;

    /** Số tiền phải hoàn (V31); {@code null} = hoàn toàn bộ số tiền hoá đơn. */
    @Column(name = "refund_amount", precision = 12, scale = 2)
    private BigDecimal refundAmount;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "due_date")
    private Instant dueDate;

    private Invoice(RentalOrder order, InvoiceType type, String code, String title, String description,
                    BigDecimal amount, Instant dueDate) {
        this.orderId = order.getId();
        this.customerId = order.getCustomerId();
        this.code = code;
        this.title = title;
        this.type = type;
        this.description = description;
        this.status = InvoiceStatus.UNPAID;
        this.amount = amount;
        this.createdAt = Instant.now();
        this.dueDate = dueDate;
    }

    /** Spec 1.3 — khách đồng ý proposal: hoá đơn đặt cọc {@code Deposit}/{@code Unpaid} cho đơn. */
    public static Invoice deposit(RentalOrder order, String code, String title, String description,
                                  BigDecimal amount, Instant dueDate) {
        return new Invoice(order, InvoiceType.DEPOSIT, code, title, description, amount, dueDate);
    }

    /** Đổi khoang sau khi đã cọc — hoá đơn bổ sung phần chênh lệch. */
    public static Invoice depositSupplement(RentalOrder order, String code, String title, String description,
                                            BigDecimal amount, Instant dueDate) {
        return new Invoice(order, InvoiceType.DEPOSIT_SUPPLEMENT, code, title, description, amount, dueDate);
    }

    /**
     * Hoá đơn gắn hợp đồng ({@code contract_id}) — tháng đầu ở Flow 2.3, gia hạn/phí ở Flow 3/6.
     */
    public static Invoice forContract(Long contractId, Long customerId, InvoiceType type, String code, String title,
                                      String description, BigDecimal amount, Instant dueDate) {
        Invoice invoice = new Invoice();
        invoice.contractId = contractId;
        invoice.customerId = customerId;
        invoice.code = code;
        invoice.title = title;
        invoice.type = type;
        invoice.description = description;
        invoice.status = InvoiceStatus.UNPAID;
        invoice.amount = amount;
        invoice.createdAt = Instant.now();
        invoice.dueDate = dueDate;
        return invoice;
    }

    /** Cổng xác nhận thanh toán: chỉ từ {@code Unpaid}. */
    public void markPaid() {
        if (status != InvoiceStatus.UNPAID) {
            throw new IllegalStateException("Hoá đơn " + id + " không thanh toán được ở trạng thái " + status);
        }
        this.status = InvoiceStatus.PAID;
    }

    /** Cron quá {@code due_date}: chỉ từ {@code Unpaid}. */
    public void expire() {
        if (status != InvoiceStatus.UNPAID) {
            throw new IllegalStateException("Hoá đơn " + id + " không hết hạn được ở trạng thái " + status);
        }
        this.status = InvoiceStatus.EXPIRED;
    }

    /** Phí quá hạn cộng dồn: cập nhật số tiền/hạn của hoá đơn {@code Penalty} còn {@code Unpaid}. */
    public void reprice(BigDecimal newAmount, Instant newDueDate) {
        if (status != InvoiceStatus.UNPAID) {
            throw new IllegalStateException("Hoá đơn " + id + " không đổi được số tiền ở trạng thái " + status);
        }
        this.amount = newAmount;
        this.dueDate = newDueDate;
    }

    public boolean isUnpaid() {
        return status == InvoiceStatus.UNPAID;
    }

    /** Hoá đơn giữ khoang (cọc đầu hoặc cọc bổ sung khi đổi khoang). */
    public boolean isDepositKind() {
        return type == InvoiceType.DEPOSIT || type == InvoiceType.DEPOSIT_SUPPLEMENT;
    }

    /** Tiền đã vào nhưng phải trả lại toàn bộ (khoang mất, hoá đơn đã đóng…); không đổi {@code Invoice.status}. */
    public void markRefundRequired(String reason) {
        this.refundStatus = InvoiceRefundStatus.REQUIRED;
        this.refundReason = reason;
        this.refundRequestedAt = Instant.now();
        this.refundAmount = null;
    }

    /**
     * Phải trả lại một phần {@code owedAmount} (cọc dư khi đổi khoang, hoàn cọc khi trả kho…). Số phải hoàn cộng dồn
     * qua các lần đánh dấu; {@code refund_amount} rỗng nghĩa là cả hoá đơn.
     */
    public void markRefundRequired(String reason, BigDecimal owedAmount) {
        BigDecimal owed = refundStatus == InvoiceRefundStatus.NONE ? BigDecimal.ZERO : refundOwed();
        this.refundStatus = InvoiceRefundStatus.REQUIRED;
        this.refundReason = reason;
        this.refundRequestedAt = Instant.now();
        this.refundAmount = owed.add(owedAmount);
    }

    /** Tổng số phải hoàn đã đánh dấu. */
    public BigDecimal refundOwed() {
        return refundAmount == null ? amount : refundAmount;
    }

    /**
     * FM vừa ghi nhận hoàn thủ công, {@code totalRefunded} là tổng đã hoàn của hoá đơn: đủ số phải hoàn →
     * {@code Refunded}, chưa đủ → {@code Processing}. Hoá đơn chưa từng bị đánh dấu cần hoàn giữ {@code None}.
     */
    public void recordRefund(BigDecimal totalRefunded, Instant at) {
        if (refundStatus == InvoiceRefundStatus.NONE) {
            return;
        }
        if (totalRefunded.compareTo(refundOwed()) >= 0) {
            this.refundStatus = InvoiceRefundStatus.REFUNDED;
            this.refundedAt = at;
        } else {
            this.refundStatus = InvoiceRefundStatus.PROCESSING;
        }
    }

    /** Cancellation Cascade: chỉ hoá đơn {@code Unpaid} chuyển {@code Canceled}; đã {@code Expired} giữ nguyên. */
    public void cancel() {
        if (status != InvoiceStatus.UNPAID) {
            throw new IllegalStateException("Hoá đơn " + id + " không huỷ được ở trạng thái " + status);
        }
        this.status = InvoiceStatus.CANCELED;
    }

    /** Tiền thuê tháng đã quá hạn ({@code Expired}) được gộp vào quyết toán trả kho → {@code Canceled}. */
    public void foldExpiredRent() {
        if (status != InvoiceStatus.EXPIRED || type != InvoiceType.RENTAL) {
            throw new IllegalStateException("Hoá đơn " + id + " không phải tiền thuê đã quá hạn");
        }
        this.status = InvoiceStatus.CANCELED;
    }

    /** Tiền thuê tháng khách chưa trả và đã quá hạn — vẫn là nợ, trả kho thì trừ vào cọc. */
    public boolean isExpiredRent() {
        return status == InvoiceStatus.EXPIRED && type == InvoiceType.RENTAL;
    }
}
