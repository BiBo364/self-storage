package vn.lemar.selfstorage.facility.application;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lệnh ghi của facility cho back-office (BOM quản lý cơ sở, FM quản lý khoang). Facility là module nền nên không tự
 * kiểm quyền / ghi audit — caller (backoffice) làm việc đó trong cùng transaction ({@code MANDATORY}).
 */
@Service
public class FacilityAdminCommands {

    private final JdbcTemplate jdbc;

    public FacilityAdminCommands(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Cơ sở (cho BOM). */
    public record FacilityRow(Long id, String code, String name, String address, String operatingHours,
                              String status, Long fmAccountId, String fmEmail, boolean keyAccess,
                              boolean codeAccess, long unitCount, long rentedCount) {
    }

    @Transactional(readOnly = true)
    public List<FacilityRow> list() {
        return jdbc.query("""
                select f.id, f.code, f.name, f.address, f.operating_hours, f.status, f.fm_account_id, a.email,
                       f.enabled_key_access, f.enabled_code_access,
                       (select count(*) from storage_units u where u.facility_id = f.id) as units,
                       (select count(*) from storage_units u where u.facility_id = f.id and u.status = 'Rented')
                           as rented
                from facilities f left join accounts a on a.id = f.fm_account_id order by f.code""",
                (rs, i) -> new FacilityRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), (Long) rs.getObject(7), rs.getString(8),
                        rs.getBoolean(9), rs.getBoolean(10), rs.getLong(11), rs.getLong(12)));
    }

    @Transactional(readOnly = true)
    public Optional<FacilityRow> find(Long id) {
        return list().stream().filter(f -> f.id().equals(id)).findFirst();
    }

    /** @throws IllegalArgumentException mã cơ sở trùng */
    @Transactional(propagation = Propagation.MANDATORY)
    public Long createFacility(String code, String name, String address, String operatingHours) {
        try {
            return jdbc.queryForObject("insert into facilities (code, name, address, operating_hours) "
                    + "values (?, ?, ?, ?) returning id", Long.class, code, name, address, operatingHours);
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException("Mã cơ sở đã tồn tại");
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void updateFacility(Long id, String name, String address, String operatingHours, String status) {
        jdbc.update("update facilities set name = ?, address = ?, operating_hours = ?, status = ? where id = ?",
                name, address, operatingHours, status, id);
    }

    /** Gán FM (1–1): FM đang phụ trách cơ sở khác bị gỡ khỏi cơ sở đó. {@code null} = bỏ FM. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void assignManager(Long facilityId, Long fmAccountId) {
        if (fmAccountId != null) {
            jdbc.update("update facilities set fm_account_id = null where fm_account_id = ? and id <> ?",
                    fmAccountId, facilityId);
        }
        jdbc.update("update facilities set fm_account_id = ? where id = ?", fmAccountId, facilityId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void setAccessOptions(Long facilityId, boolean keyAccess, boolean codeAccess) {
        jdbc.update("update facilities set enabled_key_access = ?, enabled_code_access = ? where id = ?",
                keyAccess, codeAccess, facilityId);
    }

    /** Khoang (cho FM / BOM). */
    public record UnitRow(Long id, String code, Long facilityId, Long unitTypeId, String status,
                          BigDecimal monthlyPrice, Instant maintenanceStartedAt) {
    }

    @Transactional(readOnly = true)
    public Optional<UnitRow> findUnit(Long unitId) {
        return jdbc.query("select id, code, facility_id, unit_type_id, status, monthly_price, maintenance_started_at "
                        + "from storage_units where id = ?",
                (rs, i) -> {
                    Timestamp ts = rs.getTimestamp(7);
                    return new UnitRow(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getLong(4),
                            rs.getString(5), rs.getBigDecimal(6), ts == null ? null : ts.toInstant());
                }, unitId).stream().findFirst();
    }

    /** @throws IllegalArgumentException mã khoang trùng trong cơ sở */
    @Transactional(propagation = Propagation.MANDATORY)
    public Long createUnit(Long facilityId, String code, Long unitTypeId, BigDecimal monthlyPrice) {
        offerUnitType(facilityId, unitTypeId);
        try {
            return jdbc.queryForObject("insert into storage_units (code, facility_id, unit_type_id, monthly_price) "
                    + "values (?, ?, ?, ?) returning id", Long.class, code, facilityId, unitTypeId, monthlyPrice);
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException("Mã khoang đã tồn tại trong cơ sở");
        }
    }

    /** Đổi loại / giá riêng; {@code monthlyPrice} null = dùng giá của loại khoang. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void updateUnit(Long unitId, Long unitTypeId, BigDecimal monthlyPrice) {
        jdbc.update("update storage_units set unit_type_id = ?, monthly_price = ?, updated_at = now() where id = ?",
                unitTypeId, monthlyPrice, unitId);
        findUnit(unitId).ifPresent(unit -> offerUnitType(unit.facilityId(), unitTypeId));
    }

    /** Cơ sở có khoang loại này thì cũng "cung cấp" loại đó trên form đặt kho ({@code facility_unit_type}). */
    private void offerUnitType(Long facilityId, Long unitTypeId) {
        jdbc.update("insert into facility_unit_type (facility_id, unit_type_id) values (?, ?) on conflict do nothing",
                facilityId, unitTypeId);
    }

    /**
     * Bảo trì thủ công: {@code Available -> Maintenance} hoặc {@code Maintenance -> Available} (không đụng bảo trì sau
     * trả kho đang chạy cron — {@code maintenance_started_at} được xoá khi mở lại tay).
     *
     * @return {@code true} khi trạng thái thực sự đổi
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean setMaintenance(Long unitId, boolean maintenance) {
        return maintenance
                ? jdbc.update("update storage_units set status = 'Maintenance', updated_at = now() "
                        + "where id = ? and status = 'Available'", unitId) == 1
                : jdbc.update("update storage_units set status = 'Available', maintenance_started_at = null, "
                        + "updated_at = now() where id = ? and status = 'Maintenance'", unitId) == 1;
    }

    /**
     * FM giữ bảo trì sau trả kho: khoang {@code Maintenance} đang chạy cron ({@code maintenance_started_at} có giá
     * trị) → xoá mốc, thành bảo trì thủ công (cron không tự mở lại). Một câu {@code update} có điều kiện nên không
     * đua với cron mở lại khoang.
     *
     * @return {@code true} khi thực sự đổi
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean holdMaintenance(Long unitId) {
        return jdbc.update("update storage_units set maintenance_started_at = null, updated_at = now() "
                + "where id = ? and status = 'Maintenance' and maintenance_started_at is not null", unitId) == 1;
    }

    /** Khoang đang được đơn chưa cọc trỏ tới (đề xuất / hoá đơn cọc đang chờ) — chặn bảo trì tay. */
    @Transactional(readOnly = true)
    public boolean hasPendingOrders(Long unitId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from rental_orders o where o.unit_id = ?
                    and o.status in ('Pending', 'Deposited', 'Scheduled', 'InProgress'))""", Boolean.class, unitId));
    }
}
