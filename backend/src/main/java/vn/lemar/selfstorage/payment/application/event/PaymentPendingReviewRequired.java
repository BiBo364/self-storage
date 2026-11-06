package vn.lemar.selfstorage.payment.application.event;

import java.math.BigDecimal;
import java.time.Instant;

/** Một case Pending vừa được tạo — listener gửi notification cho ADMIN. */
public record PaymentPendingReviewRequired(Long caseId, Long transactionId, Long invoiceId,
                                          String vnpTxnRef, BigDecimal amount, Instant createdAt) {
}
