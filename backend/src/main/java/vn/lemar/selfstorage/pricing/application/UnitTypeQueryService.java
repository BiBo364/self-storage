package vn.lemar.selfstorage.pricing.application;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Public API của pricing cho module khác kiểm tra unit_types tồn tại.
 * Ràng buộc facility cung cấp loại nào nằm ở bảng facility_unit_type (V4).
 */
@Service
public class UnitTypeQueryService {

    private final JdbcTemplate jdbc;

    public UnitTypeQueryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public boolean exists(Long unitTypeId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists(select 1 from unit_types where id = ?)", Boolean.class, unitTypeId));
    }

    @Transactional(readOnly = true)
    public boolean existsForFacility(Long facilityId, Long unitTypeId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists(select 1 from facility_unit_type "
                        + "where facility_id = ? and unit_type_id = ?)",
                Boolean.class, facilityId, unitTypeId));
    }

    /**
     * Các loại khoang một cơ sở cung cấp (V4) — cho form public lọc dropdown.
     */
    @Transactional(readOnly = true)
    public List<UnitTypeOption> listByFacility(Long facilityId) {
        return jdbc.query(
                "select u.id, u.name, u.width, u.depth, u.height, u.area, u.monthly_price"
                        + " from unit_types u join facility_unit_type f"
                        + " on f.unit_type_id = u.id where f.facility_id = ? order by u.id",
                (rs, row) -> new UnitTypeOption(rs.getLong(1), rs.getString(2),
                        rs.getBigDecimal(3), rs.getBigDecimal(4), rs.getBigDecimal(5),
                        rs.getBigDecimal(6), rs.getBigDecimal(7)),
                facilityId);
    }

    /** Một loại khoang theo id. */
    @Transactional(readOnly = true)
    public java.util.Optional<UnitTypeOption> find(Long unitTypeId) {
        return jdbc.query(
                "select id, name, width, depth, height, area, monthly_price from unit_types where id = ?",
                (rs, row) -> new UnitTypeOption(rs.getLong(1), rs.getString(2),
                        rs.getBigDecimal(3), rs.getBigDecimal(4), rs.getBigDecimal(5),
                        rs.getBigDecimal(6), rs.getBigDecimal(7)),
                unitTypeId).stream().findFirst();
    }

    /** Một dòng trong dropdown loại khoang của form. */
    public record UnitTypeOption(Long id, String name, BigDecimal width, BigDecimal depth,
                                 BigDecimal height, BigDecimal area, BigDecimal monthlyPrice) {
    }
}
