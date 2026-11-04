package vn.lemar.selfstorage.payment.application.dto;

import java.math.BigDecimal;
import java.time.Instant;

/** Giao dịch PAY đã thành công — nguồn để booking sửa lại state nếu listener sau IPN bị lỗi. */
public record SuccessfulPayment(Long invoiceId, String vnpTxnRef, BigDecimal amount, Instant paidAt) {
}
