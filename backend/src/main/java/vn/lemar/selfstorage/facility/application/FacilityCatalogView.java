package vn.lemar.selfstorage.facility.application;

import java.math.BigDecimal;
import java.util.List;

/**
 * Một cơ sở trong catalog public. {@code unitTypeIds} để FE biết cơ sở cung cấp loại khoang nào
 * mà không phải gọi {@code GET /api/unit-types} cho từng cơ sở. {@code latitude}/{@code longitude} cùng
 * {@code null} khi cơ sở chưa ghim bản đồ.
 */
public record FacilityCatalogView(Long id, String code, String name, String address, String operatingHours,
                                  List<Long> unitTypeIds, BigDecimal latitude, BigDecimal longitude,
                                  int floorCount) {
}
