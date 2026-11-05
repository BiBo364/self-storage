package vn.lemar.selfstorage.payment.application.dto;

import java.math.BigDecimal;
import java.time.Instant;

/** Khoản cần hoàn tiền, đọc từ invoice; chỉ để hiển thị cho ADMIN. */
public record RefundCaseView(Long invoiceId, String invoiceCode, Long orderId, BigDecimal amount,
                             String refundStatus, String refundReason, Instant refundRequestedAt,
                             String vnpTxnRef, String gatewayTransactionNo, Instant paidAt) {
}
