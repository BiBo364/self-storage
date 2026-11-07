package vn.lemar.selfstorage.facility.application;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Một khoang trong màn hình danh sách của FM.
 *
 * @param status       giá trị PascalCase như trong DB ({@code Available}, ...)
 * @param monthlyPrice giá hiệu lực: giá riêng của khoang, không có thì giá của loại khoang
 * @param unitPrice    giá riêng của khoang ({@code storage_units.monthly_price}); {@code null} = dùng giá loại khoang
 * @param maintenanceStartedAt mốc bảo trì sau trả kho (cron tự mở lại); {@code null} = không bảo trì hoặc bảo trì thủ
 *                             công
 */
public record StorageUnitListView(Long id, String code, Long facilityId, Long unitTypeId, String unitTypeName,
                                  String size, BigDecimal area, String status, BigDecimal monthlyPrice,
                                  BigDecimal unitPrice, Instant maintenanceStartedAt) {
}
