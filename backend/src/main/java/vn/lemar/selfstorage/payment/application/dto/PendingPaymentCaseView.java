package vn.lemar.selfstorage.payment.application.dto;

import java.math.BigDecimal;
import java.time.Instant;

/** Case đối soát Pending cho ADMIN; read-only ngoài thao tác reconcile. */
public record PendingPaymentCaseView(Long caseId, Long transactionId, Long invoiceId,
                                     String vnpTxnRef, BigDecimal amount, Instant transactionCreatedAt,
                                     Instant detectedAt, Instant notifiedAt, long pendingAgeMinutes,
                                     String status, String resolution, String gatewayTransactionNo,
                                     Instant paidAt, String note, String evidenceUrl,
                                     Instant resolvedAt, String resolvedByEmail) {
}
