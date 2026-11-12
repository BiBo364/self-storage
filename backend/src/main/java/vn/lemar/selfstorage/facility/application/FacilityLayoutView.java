package vn.lemar.selfstorage.facility.application;

import java.util.List;

/**
 * Sơ đồ cơ sở công khai: số tầng + khoang đã xếp vị trí, mỗi khoang chỉ kèm cờ còn trống ({@code available}) để khách
 * chọn cơ sở. Không lộ trạng thái chi tiết (giữ chỗ / đang thuê / bảo trì) — FM / BOM đọc qua back-office.
 */
public record FacilityLayoutView(Long facilityId, String code, String name, int floorCount, List<Slot> units) {

    /**
     * Một khoang trên sơ đồ; {@code aislePosition} 1 = sát cửa vào; {@code available} = khoang đang {@code Available}
     * (còn nhận thuê), mọi trạng thái khác là {@code false}.
     */
    public record Slot(String code, String unitTypeName, int floorNo, String aisleSide, int aislePosition,
                       boolean available) {
    }
}
