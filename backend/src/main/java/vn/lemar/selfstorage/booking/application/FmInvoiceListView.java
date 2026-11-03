package vn.lemar.selfstorage.booking.application;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Một dòng hoá đơn cho FM — kèm trạng thái giao dịch gần nhất để FE highlight hoá đơn cần đối soát.
 *
 * @param paymentStatus         trạng thái PAY gần nhất; null khi chưa có giao dịch
 * @param pendingAgeMinutes     số phút từ lúc tạo giao dịch Pending; 0 khi không Pending
 * @param needsReconciliation   Pending quá cửa sổ 15 phút
 * @param duplicateRefundRequired giao dịch gần nhất là Duplicate — cần hoàn tiền thủ công
 */
public record FmInvoiceListView(Long id, Long orderId, String code, String title, String description,
                                String type, String status, BigDecimal amount, Instant createdAt,
                                Instant dueDate, Long unitId, String unitCode, Long facilityId,
                                String facilityName, Long customerId, String customerName,
                                String customerEmail, String paymentStatus, String vnpTxnRef,
                                Instant paymentCreatedAt, Instant paidAt, long pendingAgeMinutes,
                                boolean needsReconciliation, boolean duplicateRefundRequired) {
}
