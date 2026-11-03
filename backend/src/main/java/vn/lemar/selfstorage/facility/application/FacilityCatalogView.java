package vn.lemar.selfstorage.facility.application;

import java.util.List;

/**
 * Một cơ sở trong catalog public. {@code unitTypeIds} để FE biết cơ sở cung cấp loại khoang nào
 * mà không phải gọi {@code GET /api/unit-types} cho từng cơ sở.
 */
public record FacilityCatalogView(Long id, String code, String name, String address, String operatingHours,
                                  List<Long> unitTypeIds) {
}
