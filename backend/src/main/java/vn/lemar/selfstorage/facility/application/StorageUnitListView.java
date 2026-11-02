package vn.lemar.selfstorage.facility.application;

import java.math.BigDecimal;

/**
 * Một khoang trong màn hình danh sách của FM.
 *
 * @param status       giá trị PascalCase như trong DB ({@code Available}, ...)
 * @param monthlyPrice giá hiệu lực: giá riêng của khoang, không có thì giá của loại khoang
 */
public record StorageUnitListView(Long id, String code, Long facilityId, Long unitTypeId, String unitTypeName,
                                  String size, BigDecimal area, String status, BigDecimal monthlyPrice) {
}
