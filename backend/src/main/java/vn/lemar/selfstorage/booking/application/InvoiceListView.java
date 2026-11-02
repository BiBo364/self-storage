package vn.lemar.selfstorage.booking.application;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Một dòng hoá đơn của khách — đọc từ read model, không lộ entity hay dữ liệu thanh toán.
 *
 * @param type   giá trị PascalCase như trong DB ({@code Deposit}, ...)
 * @param status giá trị PascalCase như trong DB ({@code Unpaid}, ...)
 * @param unitId, unitCode, facilityId, facilityName null khi hoá đơn không gắn đơn thuê
 */
public record InvoiceListView(Long id, Long orderId, String code, String title, String description,
                              String type, String status, BigDecimal amount, Instant createdAt,
                              Instant dueDate, Long unitId, String unitCode, Long facilityId,
                              String facilityName) {
}
