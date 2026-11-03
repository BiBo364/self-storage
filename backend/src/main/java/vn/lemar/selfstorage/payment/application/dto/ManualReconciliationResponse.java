package vn.lemar.selfstorage.payment.application.dto;

import java.time.Instant;
import vn.lemar.selfstorage.payment.domain.PaymentStatus;

/** Kết quả chốt thủ công một giao dịch. */
public record ManualReconciliationResponse(String txnRef, PaymentStatus status,
                                           String gatewayTransactionNo, Instant paidAt) {
}
