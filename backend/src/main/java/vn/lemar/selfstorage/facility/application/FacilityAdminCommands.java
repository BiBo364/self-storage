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

    /**
     * Cơ sở (cho BOM). {@code staffCount} = số FS {@code ACTIVE} đang được gán vào cơ sở (headcount);
     * {@code latitude}/{@code longitude} cùng {@code null} khi chưa ghim bản đồ (V34).
     */
    public record FacilityRow(Long id, String code, String name, String address, String operatingHours,
                              String status, Long fmAccountId, String fmEmail, boolean keyAccess,
                              boolean codeAccess, long unitCount, long rentedCount, long staffCount,
                              BigDecimal latitude, BigDecimal longitude, int floorCount) {
    }

    /** Toạ độ ghim bản đồ; {@code null} = chưa ghim. */
    public record Coordinates(BigDecimal latitude, BigDecimal longitude) {
    }

    @Transactional(readOnly = true)
    public List<FacilityRow> list() {
        return jdbc.query("""
                select f.id, f.code, f.name, f.address, f.operating_hours, f.status, f.fm_account_id, a.email,
                       f.enabled_key_access, f.enabled_code_access,
                       (select count(*) from storage_units u where u.facility_id = f.id) as units,
                       (select count(*) from storage_units u where u.facility_id = f.id and u.status = 'Rented')
                           as rented,
                       (select count(*) from account_facility_assignments s
                        join accounts sa on sa.id = s.account_id join roles r on r.id = sa.role_id
                        where s.facility_id = f.id and r.name = 'FS' and sa.status = 'ACTIVE') as staff,
                       f.latitude, f.longitude, f.floor_count
                from facilities f left join accounts a on a.id = f.fm_account_id order by f.code""",
                (rs, i) -> new FacilityRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), (Long) rs.getObject(7), rs.getString(8),
                        rs.getBoolean(9), rs.getBoolean(10), rs.getLong(11), rs.getLong(12), rs.getLong(13),
                        rs.getBigDecimal(14), rs.getBigDecimal(15), rs.getInt(16)));
    }

    @Transactional(readOnly = true)
    public Optional<FacilityRow> find(Long id) {
        return list().stream().filter(f -> f.id().equals(id)).findFirst();
    }

    /** @throws IllegalArgumentException mã cơ sở trùng */
    @Transactional(propagation = Propagation.MANDATORY)
    public Long createFacility(String code, String name, String address, String operatingHours,
                               Coordinates coordinates, int floorCount) {
        try {
            return jdbc.queryForObject("insert into facilities (code, name, address, operating_hours, latitude, "
                            + "longitude, floor_count) values (?, ?, ?, ?, ?, ?, ?) returning id", Long.class, code,
                    name, address, operatingHours, coordinates == null ? null : coordinates.latitude(),
                    coordinates == null ? null : coordinates.longitude(), floorCount);
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException("Mã cơ sở đã tồn tại");
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void updateFacility(Long id, String name, String address, String operatingHours, String status,
                               Coordinates coordinates, int floorCount) {
        jdbc.update("update facilities set name = ?, address = ?, operating_hours = ?, status = ?, latitude = ?, "
                        + "longitude = ?, floor_count = ? where id = ?", name, address, operatingHours, status,
                coordinates == null ? null : coordinates.latitude(),
                coordinates == null ? null : coordinates.longitude(), floorCount, id);
    }

    /** Tầng cao nhất đang có khoang xếp vị trí; 0 khi chưa có. */
    @Transactional(readOnly = true)
    public int highestPlacedFloor(Long facilityId) {
        Integer floor = jdbc.queryForObject("select max(floor_no) from storage_units where facility_id = ?",
                Integer.class, facilityId);
        return floor == null ? 0 : floor;
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

    /** Khoang (cho FM / BOM). Vị trí (V34) cùng {@code null} khi khoang chưa xếp lên sơ đồ. */
    public record UnitRow(Long id, String code, Long facilityId, Long unitTypeId, String status,
                          BigDecimal monthlyPrice, Instant maintenanceStartedAt, Integer floorNo, String aisleSide,
                          Integer aislePosition) {
    }

    @Transactional(readOnly = true)
    public Optional<UnitRow> findUnit(Long unitId) {
        return jdbc.query("select id, code, facility_id, unit_type_id, status, monthly_price, maintenance_started_at, "
                        + "floor_no, aisle_side, aisle_position from storage_units where id = ?",
                (rs, i) -> {
                    Timestamp ts = rs.getTimestamp(7);
                    return new UnitRow(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getLong(4),
                            rs.getString(5), rs.getBigDecimal(6), ts == null ? null : ts.toInstant(),
                            (Integer) rs.getObject(8, Integer.class), rs.getString(9),
                            (Integer) rs.getObject(10, Integer.class));
                }, unitId).stream().findFirst();
    }

    /**
     * @param location {@code null} = chưa xếp vị trí
     * @throws IllegalArgumentException mã khoang trùng trong cơ sở
     * @throws IllegalStateException    vị trí đã có khoang khác
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Long createUnit(Long facilityId, String code, Long unitTypeId, BigDecimal monthlyPrice,
                           UnitLocation location) {
        requireFreeSlot(facilityId, location, null);
        offerUnitType(facilityId, unitTypeId);
        try {
            return jdbc.queryForObject("insert into storage_units (code, facility_id, unit_type_id, monthly_price, "
                            + "floor_no, aisle_side, aisle_position) values (?, ?, ?, ?, ?, ?, ?) returning id",
                    Long.class, code, facilityId, unitTypeId, monthlyPrice,
                    location == null ? null : location.floorNo(), location == null ? null : location.aisleSide(),
                    location == null ? null : location.aislePosition());
        } catch (DuplicateKeyException e) {
            throw duplicate(e, location);
        }
    }

    /**
     * Đặt / đổi / xoá ({@code null}) vị trí khoang trên sơ đồ.
     *
     * @throws IllegalStateException vị trí đã có khoang khác
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void setUnitLocation(Long unitId, Long facilityId, UnitLocation location) {
        requireFreeSlot(facilityId, location, unitId);
        try {
            jdbc.update("update storage_units set floor_no = ?, aisle_side = ?, aisle_position = ?, updated_at = now() "
                            + "where id = ?", location == null ? null : location.floorNo(),
                    location == null ? null : location.aisleSide(),
                    location == null ? null : location.aislePosition(), unitId);
        } catch (DuplicateKeyException e) {
            throw duplicate(e, location);
        }
    }

    /** Kiểm trước để báo đúng mã khoang đang chiếm chỗ; unique index {@code uq_storage_units_slot} chặn khi đua. */
    private void requireFreeSlot(Long facilityId, UnitLocation location, Long exceptUnitId) {
        if (location == null) {
            return;
        }
        jdbc.query("select code from storage_units where facility_id = ? and floor_no = ? and aisle_side = ? "
                        + "and aisle_position = ? and id <> ?", (rs, i) -> rs.getString(1), facilityId,
                location.floorNo(), location.aisleSide(), location.aislePosition(),
                exceptUnitId == null ? -1L : exceptUnitId).stream().findFirst().ifPresent(code -> {
                    throw new IllegalStateException(location.label() + " đã có khoang " + code);
                });
    }

    private static RuntimeException duplicate(DuplicateKeyException e, UnitLocation location) {
        String message = e.getMostSpecificCause().getMessage();
        if (location != null && message != null && message.contains("uq_storage_units_slot")) {
            return new IllegalStateException(location.label() + " đã có khoang khác");
        }
        return new IllegalArgumentException("Mã khoang đã tồn tại trong cơ sở");
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
