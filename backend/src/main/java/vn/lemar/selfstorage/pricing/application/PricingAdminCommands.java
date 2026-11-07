package vn.lemar.selfstorage.pricing.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Lệnh ghi của pricing cho BOM: policy và giá theo loại khoang. Caller kiểm quyền + ghi audit. */
@Service
public class PricingAdminCommands {

    private final JdbcTemplate jdbc;

    public PricingAdminCommands(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record PolicyRow(Long id, String key, String value, String description, Instant updatedAt) {
    }

    public record UnitTypeRow(Long id, String name, BigDecimal width, BigDecimal depth, BigDecimal height,
                              BigDecimal area, String description, BigDecimal monthlyPrice) {
    }

    @Transactional(readOnly = true)
    public List<PolicyRow> policies() {
        return jdbc.query("select id, key, value, description, updated_at from policies order by key",
                (rs, i) -> new PolicyRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getTimestamp(5).toInstant()));
    }

    @Transactional(readOnly = true)
    public Optional<PolicyRow> policy(String key) {
        return policies().stream().filter(p -> p.key().equals(key)).findFirst();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void updatePolicy(String key, String value) {
        jdbc.update("update policies set value = ?, updated_at = now() where key = ?", value, key);
    }

    @Transactional(readOnly = true)
    public List<UnitTypeRow> unitTypes() {
        return jdbc.query("select id, name, width, depth, height, area, description, monthly_price from unit_types "
                        + "order by monthly_price",
                (rs, i) -> new UnitTypeRow(rs.getLong(1), rs.getString(2), rs.getBigDecimal(3), rs.getBigDecimal(4),
                        rs.getBigDecimal(5), rs.getBigDecimal(6), rs.getString(7), rs.getBigDecimal(8)));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void updateUnitTypePrice(Long unitTypeId, BigDecimal monthlyPrice, Long actorAccountId) {
        jdbc.update("update unit_types set monthly_price = ?, updated_by = ?, updated_at = now() where id = ?",
                monthlyPrice, actorAccountId, unitTypeId);
    }
}
