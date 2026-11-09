package vn.lemar.selfstorage.payment.application.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Thông báo thanh toán đã được cổng ký và đã kiểm chữ ký (vd webhook payOS).
 *
 * @param txnRef        mã giao dịch phía mình ({@code orderCode} của payOS)
 * @param amount        số tiền VND cổng báo
 * @param transactionNo mã tham chiếu giao dịch ngân hàng / cổng
 * @param paidAt        thời điểm cổng ghi nhận; {@code null} nếu không đọc được
 * @param success       cổng báo thanh toán thành công
 * @param payload       body gốc để lưu đối soát
 */
public record GatewayNotification(String txnRef, BigDecimal amount, String transactionNo, Instant paidAt,
                                  boolean success, String payload) {
}
