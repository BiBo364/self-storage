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
 * và huỷ hoá đơn {@code Unpaid} trong Cancellation Cascade; thanh toán thuộc B4.
 *
 * <p>Cột {@code contract_id} không map: hoá đơn cọc gắn {@code order_id}, CHECK của V1 buộc đúng một
 * trong hai FK được set.
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

    /** Cancellation Cascade: chỉ hoá đơn {@code Unpaid} chuyển {@code Canceled}; đã {@code Expired} giữ nguyên. */
    /** Cổng thanh toán đã xác nhận thu tiền (Flow 1.4). */
    public void markPaid() {
        this.status = InvoiceStatus.PAID;
    }

    /** Tiền đã vào nhưng không thể giữ khoang; không đổi Invoice.status. */
    public void markRefundRequired(String reason) {
        this.refundStatus = InvoiceRefundStatus.REQUIRED;
        this.refundReason = reason;
        this.refundRequestedAt = Instant.now();
    }

    public void cancel() {
        if (status != InvoiceStatus.UNPAID) {
            throw new IllegalStateException("Hoá đơn " + id + " không huỷ được ở trạng thái " + status);
        }
        this.status = InvoiceStatus.CANCELED;
    }
}
