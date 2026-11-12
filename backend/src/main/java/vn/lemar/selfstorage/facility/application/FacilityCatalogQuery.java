package vn.lemar.selfstorage.facility.application;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Đọc catalog cơ sở cho màn public và cơ sở của FM. Chỉ đọc, không ghi; module facility không phụ
 * thuộc identity nên phần xác định "FM hiện tại" do booking truyền {@code accountId} vào.
 */
@Service
public class FacilityCatalogQuery {

    private final JdbcTemplate jdbc;

    public FacilityCatalogQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Cơ sở đang hoạt động, order by id; kèm loại khoang cơ sở cung cấp (V4). */
    @Transactional(readOnly = true)
    public List<FacilityCatalogView> listActive() {
        List<FacilityRow> facilities = jdbc.query(
                "select id, code, name, address, operating_hours, latitude, longitude, floor_count from facilities "
                        + "where status = 'Active' order by id",
                (rs, rowNum) -> new FacilityRow(rs.getLong("id"), rs.getString("code"), rs.getString("name"),
                        rs.getString("address"), rs.getString("operating_hours"), rs.getBigDecimal("latitude"),
                        rs.getBigDecimal("longitude"), rs.getInt("floor_count")));

        Map<Long, List<Long>> unitTypesByFacility = new HashMap<>();
        jdbc.query("select facility_id, unit_type_id from facility_unit_type "
                        + "order by facility_id, unit_type_id",
                rs -> {
                    unitTypesByFacility.computeIfAbsent(rs.getLong("facility_id"), key -> new ArrayList<>())
                            .add(rs.getLong("unit_type_id"));
                });

        return facilities.stream()
                .map(facility -> new FacilityCatalogView(facility.id(), facility.code(), facility.name(),
                        facility.address(), facility.operatingHours(),
                        unitTypesByFacility.getOrDefault(facility.id(), List.of()), facility.latitude(),
                        facility.longitude(), facility.floorCount()))
                .toList();
    }

    /**
     * Sơ đồ một cơ sở (mọi trạng thái cơ sở — khách của cơ sở đã ngưng vẫn cần tìm khoang): khoang đã xếp vị trí,
     * order theo tầng, bên, thứ tự từ cửa vào.
     */
    @Transactional(readOnly = true)
    public Optional<FacilityLayoutView> layout(Long facilityId) {
        Optional<LayoutHeader> header = jdbc.query("select id, code, name, floor_count from facilities where id = ?",
                (rs, rowNum) -> new LayoutHeader(rs.getLong("id"), rs.getString("code"), rs.getString("name"),
                        rs.getInt("floor_count")), facilityId).stream().findFirst();
        return header.map(facility -> new FacilityLayoutView(facility.id(), facility.code(), facility.name(),
                facility.floorCount(), jdbc.query("""
                        select u.code, t.name, u.floor_no, u.aisle_side, u.aisle_position
                        from storage_units u join unit_types t on t.id = u.unit_type_id
                        where u.facility_id = ? and u.floor_no is not null
                        order by u.floor_no, u.aisle_side, u.aisle_position""",
                        (rs, i) -> new FacilityLayoutView.Slot(rs.getString(1), rs.getString(2), rs.getInt(3),
                                rs.getString(4), rs.getInt(5)), facilityId)));
    }

    /** Cơ sở FM đang phụ trách; rỗng nếu account chưa được gán. */
    @Transactional(readOnly = true)
    public Optional<ManagedFacilityView> findManagedBy(Long accountId) {
        return jdbc.query("select id, code, name, address, operating_hours, status from facilities "
                        + "where fm_account_id = ? order by id",
                (rs, rowNum) -> new ManagedFacilityView(rs.getLong("id"), rs.getString("code"),
                        rs.getString("name"), rs.getString("address"), rs.getString("operating_hours"),
                        rs.getString("status")),
                accountId).stream().findFirst();
    }

    private record LayoutHeader(Long id, String code, String name, int floorCount) {
    }

    private record FacilityRow(Long id, String code, String name, String address, String operatingHours,
                               BigDecimal latitude, BigDecimal longitude, int floorCount) {
    }
}
