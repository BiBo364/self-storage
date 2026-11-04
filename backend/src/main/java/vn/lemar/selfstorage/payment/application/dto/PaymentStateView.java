package vn.lemar.selfstorage.payment.application.dto;

import java.time.Instant;

/** Trạng thái giao dịch PAY gần nhất của một hoá đơn — read model cho màn FM. */
public record PaymentStateView(Long invoiceId, String status, String vnpTxnRef,
                               Instant createdAt, Instant paidAt) {
}
