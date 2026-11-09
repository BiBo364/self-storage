package vn.lemar.selfstorage.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Một lượt giao dịch với cổng thanh toán.
 *
 * <p>{@code invoiceId} để kiểu {@code Long} chứ không phải quan hệ JPA: bảng {@code invoices}
 * thuộc module khác, mà ranh giới module cấm import {@code domain} của nhau. Ràng buộc khoá
 * ngoại vẫn do DB giữ.
 *
 * <p>{@code vnpTxnRef} là cột UNIQUE và chính là chốt chặn idempotency khi VNPay gọi IPN lặp lại.
 */
@Entity
@Table(name = "payment_transactions")
public class PaymentTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "invoice_id", nullable = false)
    private Long invoiceId;

    @Column(name = "vnp_txn_ref", nullable = false, unique = true, length = 100)
    private String vnpTxnRef;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private PaymentDirection direction = PaymentDirection.PAY;

    @Convert(converter = PaymentStatusConverter.class)
    @Column(nullable = false, length = 20)
    private PaymentStatus status = PaymentStatus.PENDING;

    @Column(name = "gateway_transaction_no", unique = true, length = 100)
    private String gatewayTransactionNo;

    @Column(name = "transaction_content")
    private String transactionContent;

    @Column(name = "response_payload")
    private String responsePayload;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected PaymentTransaction() {
        // JPA
    }

    public PaymentTransaction(Long invoiceId, String vnpTxnRef, BigDecimal amount, String transactionContent) {
        // VNPay chỉ nhận mốc giây (yyyyMMddHHmmss); cắt sẵn để vnp_CreateDate và querydr dùng đúng một mốc.
        this.createdAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        this.invoiceId = invoiceId;
        this.vnpTxnRef = vnpTxnRef;
        this.amount = amount;
        this.transactionContent = transactionContent;
        this.direction = PaymentDirection.PAY;
        this.status = PaymentStatus.PENDING;
    }

    /**
     * Hoàn tiền FM đã chuyển trả khách ngoài hệ thống (payOS không có API hoàn tiền) — ghi nhận lại để đối soát:
     * {@code direction = REFUND}, {@code Success} ngay.
     */
    public static PaymentTransaction manualRefund(Long invoiceId, String ref, BigDecimal amount, String note,
                                                  Instant now) {
        PaymentTransaction refund = new PaymentTransaction(invoiceId, ref, amount, note);
        refund.direction = PaymentDirection.REFUND;
        refund.status = PaymentStatus.SUCCESS;
        refund.paidAt = now;
        return refund;
    }

    /** Ghi nhận cổng báo thành công. Chỉ gọi khi giao dịch còn {@code PENDING}. */
    public void markSucceeded(String gatewayTransactionNo, Instant paidAt, String responsePayload) {
        this.status = PaymentStatus.SUCCESS;
        this.gatewayTransactionNo = gatewayTransactionNo;
        this.paidAt = paidAt;
        this.responsePayload = responsePayload;
    }

    /** Ghi nhận cổng báo thất bại. Chỉ gọi khi giao dịch còn {@code PENDING}. */
    public void markFailed(String failureReason, String responsePayload) {
        this.status = PaymentStatus.FAILED;
        this.failureReason = failureReason;
        this.responsePayload = responsePayload;
    }

    /**
     * Giao dịch thành công thứ hai cho cùng invoice. Giữ dấu vết tiền đã vào nhưng không được
     * tính là Success thứ hai; trạng thái này chờ hoàn tiền thủ công.
     */
    public void markDuplicate(String gatewayTransactionNo, Instant paidAt, String reason,
            String responsePayload) {
        this.status = PaymentStatus.DUPLICATE;
        this.gatewayTransactionNo = gatewayTransactionNo;
        this.paidAt = paidAt;
        this.failureReason = reason;
        this.responsePayload = responsePayload;
    }

    /**
     * Phiên đã đóng {@code FAILED} (huỷ / hết hạn phía mình) nhưng cổng báo khách vẫn trả (quét QR cũ): tiền đã vào nên
     * ghi {@code SUCCESS} — số này tính vào "đã thu qua cổng" để FM ghi nhận hoàn được. Giữ lý do đóng cũ để tra cứu.
     */
    public void markLateSucceeded(String gatewayTransactionNo, Instant paidAt, String responsePayload) {
        this.failureReason = "Thanh toán muộn sau khi phiên đã đóng: " + failureReason;
        markSucceeded(gatewayTransactionNo, paidAt, responsePayload);
    }

    public boolean isPending() {
        return status == PaymentStatus.PENDING;
    }

    public Long getId() {
        return id;
    }

    public Long getInvoiceId() {
        return invoiceId;
    }

    public String getVnpTxnRef() {
        return vnpTxnRef;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getTransactionContent() {
        return transactionContent;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public PaymentDirection getDirection() {
        return direction;
    }

    public String getGatewayTransactionNo() {
        return gatewayTransactionNo;
    }

    public Instant getPaidAt() {
        return paidAt;
    }

    public String getFailureReason() {
        return failureReason;
    }

    /** Raw payload của cổng — dùng để đối soát. */
    public String getResponsePayload() {
        return responsePayload;
    }
}
