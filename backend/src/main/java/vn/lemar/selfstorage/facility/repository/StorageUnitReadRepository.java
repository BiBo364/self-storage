package vn.lemar.selfstorage.facility.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import vn.lemar.selfstorage.facility.application.StorageUnitListView;

/** Read model danh sách khoang theo cơ sở — join {@code unit_types} để có tên/giá hiệu lực. */
@Repository
public class StorageUnitReadRepository {

    private final JdbcTemplate jdbc;

    public StorageUnitReadRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @param status PascalCase như DB, {@code null} = mọi trạng thái */
    public List<StorageUnitListView> findByFacility(Long facilityId, String status, Long unitTypeId) {
        StringBuilder sql = new StringBuilder(
                "select u.id, u.code, u.facility_id, u.unit_type_id, t.name as unit_type_name, u.size, "
                        + "t.area, u.status, coalesce(u.monthly_price, t.monthly_price) as monthly_price, "
                        + "u.monthly_price as unit_price, u.maintenance_started_at, u.floor_no, u.aisle_side, "
                        + "u.aisle_position "
                        + "from storage_units u join unit_types t on t.id = u.unit_type_id "
                        + "where u.facility_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(facilityId);
        if (status != null) {
            sql.append(" and u.status = ?");
            args.add(status);
        }
        if (unitTypeId != null) {
            sql.append(" and u.unit_type_id = ?");
            args.add(unitTypeId);
        }
        sql.append(" order by u.code");
        return jdbc.query(sql.toString(), (rs, rowNum) -> new StorageUnitListView(
                rs.getLong("id"), rs.getString("code"), rs.getLong("facility_id"), rs.getLong("unit_type_id"),
                rs.getString("unit_type_name"), rs.getString("size"), rs.getBigDecimal("area"),
                rs.getString("status"), rs.getBigDecimal("monthly_price"), rs.getBigDecimal("unit_price"),
                instant(rs.getTimestamp("maintenance_started_at")), rs.getObject("floor_no", Integer.class),
                rs.getString("aisle_side"), rs.getObject("aisle_position", Integer.class)),
                args.toArray());
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
