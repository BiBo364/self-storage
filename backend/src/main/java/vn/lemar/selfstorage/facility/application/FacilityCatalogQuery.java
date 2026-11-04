package vn.lemar.selfstorage.facility.application;

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
                "select id, code, name, address, operating_hours from facilities "
                        + "where status = 'Active' order by id",
                (rs, rowNum) -> new FacilityRow(rs.getLong("id"), rs.getString("code"), rs.getString("name"),
                        rs.getString("address"), rs.getString("operating_hours")));

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
                        unitTypesByFacility.getOrDefault(facility.id(), List.of())))
                .toList();
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

    private record FacilityRow(Long id, String code, String name, String address, String operatingHours) {
    }
}
