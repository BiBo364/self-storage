package vn.lemar.selfstorage.facility.application;

import java.util.List;

/**
 * Sơ đồ cơ sở công khai: số tầng + khoang đã xếp vị trí. Không có trạng thái khoang (đang thuê / trống) — khách và FS
 * chỉ cần biết khoang nằm ở đâu; FM / BOM đọc trạng thái qua danh sách khoang của back-office.
 */
public record FacilityLayoutView(Long facilityId, String code, String name, int floorCount, List<Slot> units) {

    /** Một khoang trên sơ đồ; {@code aislePosition} 1 = sát cửa vào. */
    public record Slot(String code, String unitTypeName, int floorNo, String aisleSide, int aislePosition) {
    }
}
