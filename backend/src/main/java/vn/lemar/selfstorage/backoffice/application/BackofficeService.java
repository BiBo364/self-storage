package vn.lemar.selfstorage.backoffice.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.lemar.selfstorage.audit.application.AuditEntry;
import vn.lemar.selfstorage.audit.application.AuditRecorder;
import vn.lemar.selfstorage.audit.domain.AuditAction;
import vn.lemar.selfstorage.audit.domain.AuditEntityType;
import vn.lemar.selfstorage.backoffice.repository.BackofficeReadRepository;
import vn.lemar.selfstorage.backoffice.repository.BackofficeReadRepository.ContractRow;
import vn.lemar.selfstorage.backoffice.repository.BackofficeReadRepository.InvoiceRow;
import vn.lemar.selfstorage.backoffice.repository.BackofficeReadRepository.Kpis;
import vn.lemar.selfstorage.backoffice.repository.BackofficeReadRepository.OccupancyRow;
import vn.lemar.selfstorage.backoffice.repository.BackofficeReadRepository.RevenueRow;
import vn.lemar.selfstorage.backoffice.repository.BackofficeReadRepository.StaffReportRow;
import vn.lemar.selfstorage.facility.application.FacilityAdminCommands;
import vn.lemar.selfstorage.facility.application.FacilityAdminCommands.FacilityRow;
import vn.lemar.selfstorage.facility.application.FacilityAdminCommands.Coordinates;
import vn.lemar.selfstorage.facility.application.FacilityAdminCommands.UnitRow;
import vn.lemar.selfstorage.facility.application.StorageUnitListQuery;
import vn.lemar.selfstorage.facility.application.StorageUnitListView;
import vn.lemar.selfstorage.facility.application.UnitLocation;
import vn.lemar.selfstorage.identity.application.Access;
import vn.lemar.selfstorage.identity.application.CurrentAccountProvider;
import vn.lemar.selfstorage.pricing.application.PricingAdminCommands;
import vn.lemar.selfstorage.pricing.application.PricingAdminCommands.PolicyRow;
import vn.lemar.selfstorage.pricing.application.PricingAdminCommands.UnitTypeRow;
import vn.lemar.selfstorage.pricing.application.UnitTypeQueryService;

/**
 * Back-office (Flow 4/5): FM quản lý khoang / xem hoá đơn, hợp đồng, báo cáo của cơ sở; BOM quản lý cơ sở, policy, giá
 * và xem doanh thu toàn chuỗi. Ghi đi qua lệnh của module sở hữu bảng (facility, pricing); service này kiểm quyền và
 * ghi audit trong cùng transaction.
 */
@Service
public class BackofficeService {

    static final String PERM_FACILITY = "facility.access";
    static final int INVOICE_LIMIT = 500;
    static final int STAFF_REPORT_MAX_DAYS = 366;

    /** Mốc ngày của báo cáo tính theo giờ Việt Nam, không theo TZ của JVM (container mặc định UTC). */
    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final BackofficeReadRepository readRepository;
    private final FacilityAdminCommands facilities;
    private final PricingAdminCommands pricing;
    private final UnitTypeQueryService unitTypes;
    private final Access access;
    private final CurrentAccountProvider currentAccount;
    private final AuditRecorder auditRecorder;
    private final StorageUnitListQuery unitList;

    BackofficeService(BackofficeReadRepository readRepository, FacilityAdminCommands facilities,
                      PricingAdminCommands pricing, UnitTypeQueryService unitTypes, Access access,
                      CurrentAccountProvider currentAccount, AuditRecorder auditRecorder,
                      StorageUnitListQuery unitList) {
        this.readRepository = readRepository;
        this.unitList = unitList;
        this.facilities = facilities;
        this.pricing = pricing;
        this.unitTypes = unitTypes;
        this.access = access;
        this.currentAccount = currentAccount;
        this.auditRecorder = auditRecorder;
    }

    /** Báo cáo: KPI + doanh thu theo tháng × loại hoá đơn + lấp đầy. */
    public record Report(LocalDate from, LocalDate to, Kpis kpis, BigDecimal totalRevenue, List<RevenueRow> revenue,
                         List<OccupancyRow> occupancy) {
    }

    /** Một khoang BOM dựng trên sơ đồ lúc tạo cơ sở. */
    public record NewUnit(String code, Long unitTypeId, UnitLocation location) {
    }

    /** Báo cáo nhân sự: khoảng ngày đã áp mặc định, cơ sở ({@code null} = toàn chuỗi), một dòng mỗi FS. */
    public record StaffReport(LocalDate from, LocalDate to, Long facilityId, List<StaffReportRow> rows) {
    }

    // ------------------------------------------------------------------ FM (theo cơ sở)

    @Transactional(readOnly = true)
    public List<ContractRow> facilityContracts(Long facilityId, String status) {
        access.can(PERM_FACILITY, facilityId);
        return readRepository.contracts(facilityId, status);
    }

    @Transactional(readOnly = true)
    public Report facilityReport(Long facilityId, LocalDate from, LocalDate to) {
        access.can(PERM_FACILITY, facilityId);
        return report(facilityId, from, to);
    }

    @Transactional(readOnly = true)
    public StaffReport facilityStaffReport(Long facilityId, LocalDate from, LocalDate to) {
        access.can(PERM_FACILITY, facilityId);
        return staffReport(facilityId, from, to);
    }

    @Transactional
    public UnitRow createUnit(Long facilityId, String code, Long unitTypeId, BigDecimal monthlyPrice,
                              UnitLocation location) {
        access.can(PERM_FACILITY, facilityId);
        if (code == null || code.isBlank() || code.length() > 50) {
            throw new IllegalArgumentException("Mã khoang bắt buộc, tối đa 50 ký tự");
        }
        if (unitTypeId == null || !unitTypes.exists(unitTypeId)) {
            throw new IllegalArgumentException("Loại khoang không tồn tại");
        }
        requireVndPrice(monthlyPrice);
        requireWithinFloors(facilityId, location);
        Long id = facilities.createUnit(facilityId, code.trim(), unitTypeId, monthlyPrice, location);
        UnitRow unit = facilities.findUnit(id).orElseThrow();
        Map<String, Object> value = new HashMap<>();
        value.put("code", unit.code());
        value.put("unitTypeId", unitTypeId);
        value.put("monthlyPrice", monthlyPrice);
        putLocation(value, location);
        audit(AuditAction.STORAGE_UNIT_CREATED, AuditEntityType.STORAGE_UNIT, id, null, value);
        return unit;
    }

    /**
     * Đặt / đổi / xoá ({@code location} null) vị trí khoang trên sơ đồ. Đổi được ở mọi trạng thái khoang — vị trí là
     * dữ liệu mô tả, không đổi nghiệp vụ thuê.
     */
    @Transactional
    public UnitRow setUnitLocation(Long unitId, UnitLocation location) {
        UnitRow before = facilities.findUnit(unitId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy khoang"));
        access.can(PERM_FACILITY, before.facilityId());
        requireWithinFloors(before.facilityId(), location);
        facilities.setUnitLocation(unitId, before.facilityId(), location);
        UnitRow after = facilities.findUnit(unitId).orElseThrow();
        Map<String, Object> oldValue = new HashMap<>();
        putLocation(oldValue, before.floorNo() == null ? null
                : new UnitLocation(before.floorNo(), before.aisleSide(), before.aislePosition()));
        Map<String, Object> newValue = new HashMap<>();
        putLocation(newValue, location);
        audit(AuditAction.STORAGE_UNIT_UPDATED, AuditEntityType.STORAGE_UNIT, unitId, oldValue, newValue);
        return after;
    }

    private void requireWithinFloors(Long facilityId, UnitLocation location) {
        if (location == null) {
            return;
        }
        int floors = facilities.find(facilityId).map(FacilityRow::floorCount)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy cơ sở"));
        if (location.floorNo() > floors) {
            throw new IllegalArgumentException("Cơ sở chỉ có " + floors + " tầng");
        }
    }

    private static void putLocation(Map<String, Object> value, UnitLocation location) {
        value.put("floorNo", location == null ? null : location.floorNo());
        value.put("aisleSide", location == null ? null : location.aisleSide());
        value.put("aislePosition", location == null ? null : location.aislePosition());
    }

    /** Đổi loại khoang / giá riêng ({@code monthlyPrice} null = dùng giá loại khoang). */
    @Transactional
    public UnitRow updateUnit(Long unitId, Long unitTypeId, BigDecimal monthlyPrice) {
        UnitRow before = facilities.findUnit(unitId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy khoang"));
        access.can(PERM_FACILITY, before.facilityId());
        Long typeId = unitTypeId == null ? before.unitTypeId() : unitTypeId;
        if (!unitTypes.exists(typeId)) {
            throw new IllegalArgumentException("Loại khoang không tồn tại");
        }
        requireVndPrice(monthlyPrice);
        facilities.updateUnit(unitId, typeId, monthlyPrice);
        UnitRow after = facilities.findUnit(unitId).orElseThrow();
        Map<String, Object> oldValue = new HashMap<>();
        oldValue.put("unitTypeId", before.unitTypeId());
        oldValue.put("monthlyPrice", before.monthlyPrice());
        Map<String, Object> newValue = new HashMap<>();
        newValue.put("unitTypeId", after.unitTypeId());
        newValue.put("monthlyPrice", after.monthlyPrice());
        audit(AuditAction.STORAGE_UNIT_UPDATED, AuditEntityType.STORAGE_UNIT, unitId, oldValue, newValue);
        return after;
    }

    /** Bật / tắt bảo trì thủ công; khoang đang có đơn trỏ tới thì không bật được. */
    @Transactional
    public UnitRow setMaintenance(Long unitId, boolean maintenance) {
        UnitRow before = facilities.findUnit(unitId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy khoang"));
        access.can(PERM_FACILITY, before.facilityId());
        if (maintenance && facilities.hasPendingOrders(unitId)) {
            throw new IllegalStateException("Khoang đang có đơn thuê chưa hoàn tất — không chuyển bảo trì được");
        }
        if (!facilities.setMaintenance(unitId, maintenance)) {
            throw new IllegalStateException(maintenance ? "Chỉ khoang Available mới chuyển bảo trì được"
                    : "Khoang không ở trạng thái bảo trì");
        }
        audit(maintenance ? AuditAction.STORAGE_UNIT_MAINTENANCE : AuditAction.STORAGE_UNIT_AVAILABLE,
                AuditEntityType.STORAGE_UNIT, unitId, Map.of("status", before.status()),
                Map.of("status", maintenance ? "Maintenance" : "Available", "reason", "Thủ công"));
        return facilities.findUnit(unitId).orElseThrow();
    }

    /** FM giữ bảo trì sau trả kho: bỏ mốc {@code maintenance_started_at} để cron không tự mở lại khoang. */
    @Transactional
    public UnitRow holdMaintenance(Long unitId) {
        UnitRow before = facilities.findUnit(unitId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy khoang"));
        access.can(PERM_FACILITY, before.facilityId());
        if (!facilities.holdMaintenance(unitId)) {
            throw new IllegalStateException("Chỉ giữ bảo trì được cho khoang đang bảo trì sau trả kho");
        }
        Map<String, Object> oldValue = new HashMap<>();
        oldValue.put("status", before.status());
        oldValue.put("maintenanceStartedAt",
                before.maintenanceStartedAt() == null ? null : before.maintenanceStartedAt().toString());
        Map<String, Object> newValue = new HashMap<>();
        newValue.put("status", "Maintenance");
        newValue.put("maintenanceStartedAt", null);
        newValue.put("reason", "FM giữ bảo trì");
        audit(AuditAction.STORAGE_UNIT_MAINTENANCE, AuditEntityType.STORAGE_UNIT, unitId, oldValue, newValue);
        return facilities.findUnit(unitId).orElseThrow();
    }

    /** Cơ sở (kèm hình thức truy cập đang bật) cho FM / BOM. */
    @Transactional(readOnly = true)
    public FacilityRow facility(Long facilityId) {
        access.can(PERM_FACILITY, facilityId);
        return facilities.find(facilityId).orElseThrow(() -> new IllegalArgumentException("Không tìm thấy cơ sở"));
    }

    @Transactional
    public FacilityRow setAccessOptions(Long facilityId, boolean keyAccess, boolean codeAccess) {
        access.can(PERM_FACILITY, facilityId);
        if (!keyAccess && !codeAccess) {
            throw new IllegalArgumentException("Cơ sở phải bật ít nhất một hình thức truy cập");
        }
        FacilityRow before = facilities.find(facilityId).orElseThrow();
        facilities.setAccessOptions(facilityId, keyAccess, codeAccess);
        audit(AuditAction.FACILITY_UPDATED, AuditEntityType.FACILITY, facilityId,
                Map.of("keyAccess", before.keyAccess(), "codeAccess", before.codeAccess()),
                Map.of("keyAccess", keyAccess, "codeAccess", codeAccess));
        return facilities.find(facilityId).orElseThrow();
    }

    // ------------------------------------------------------------------ BOM (toàn chuỗi)

    @Transactional(readOnly = true)
    public List<FacilityRow> listFacilities() {
        return facilities.list();
    }

    /** BOM: mọi khoang của một cơ sở kèm trạng thái + vị trí — vẽ sơ đồ toàn chuỗi. */
    @Transactional(readOnly = true)
    public List<StorageUnitListView> facilityUnits(Long facilityId) {
        if (facilities.find(facilityId).isEmpty()) {
            throw new IllegalArgumentException("Không tìm thấy cơ sở");
        }
        return unitList.listByFacility(facilityId, null, null);
    }

    /**
     * Tạo cơ sở kèm ghim bản đồ, số tầng và sơ đồ khoang ban đầu trong một transaction: khoang sai (mã trùng, loại
     * không tồn tại, tầng vượt số tầng, trùng vị trí) thì không tạo gì.
     *
     * @param floorCount {@code null} = 1 tầng
     * @param units      {@code null} / rỗng = chưa có khoang
     */
    @Transactional
    public FacilityRow createFacility(String code, String name, String address, String operatingHours,
                                      Coordinates coordinates, Integer floorCount, List<NewUnit> units) {
        if (code == null || code.isBlank() || name == null || name.isBlank()) {
            throw new IllegalArgumentException("Mã và tên cơ sở bắt buộc");
        }
        int floors = requireFloorCount(floorCount == null ? 1 : floorCount);
        List<NewUnit> layout = units == null ? List.of() : units;
        requireDistinctUnits(layout, floors);
        String facilityCode = code.trim().toUpperCase();
        Long id = facilities.createFacility(facilityCode, name.trim(), address, operatingHours,
                requireCoordinates(coordinates), floors);
        for (NewUnit unit : layout) {
            Long unitId = facilities.createUnit(id, unit.code().trim(), unit.unitTypeId(), null, unit.location());
            Map<String, Object> value = new HashMap<>();
            value.put("code", unit.code().trim());
            value.put("unitTypeId", unit.unitTypeId());
            putLocation(value, unit.location());
            audit(AuditAction.STORAGE_UNIT_CREATED, AuditEntityType.STORAGE_UNIT, unitId, null, value);
        }
        Map<String, Object> value = new HashMap<>();
        value.put("code", facilityCode);
        value.put("name", name.trim());
        value.put("floorCount", floors);
        value.put("unitCount", layout.size());
        value.put("latitude", coordinates == null ? null : coordinates.latitude());
        value.put("longitude", coordinates == null ? null : coordinates.longitude());
        audit(AuditAction.FACILITY_CREATED, AuditEntityType.FACILITY, id, null, value);
        return facilities.find(id).orElseThrow();
    }

    private void requireDistinctUnits(List<NewUnit> units, int floors) {
        Set<String> codes = new HashSet<>();
        Set<UnitLocation> slots = new HashSet<>();
        for (NewUnit unit : units) {
            if (unit.code() == null || unit.code().isBlank() || unit.code().trim().length() > 50) {
                throw new IllegalArgumentException("Mã khoang bắt buộc, tối đa 50 ký tự");
            }
            if (!codes.add(unit.code().trim().toUpperCase())) {
                throw new IllegalArgumentException("Mã khoang " + unit.code().trim() + " bị lặp trên sơ đồ");
            }
            if (unit.unitTypeId() == null || !unitTypes.exists(unit.unitTypeId())) {
                throw new IllegalArgumentException("Loại khoang của " + unit.code().trim() + " không tồn tại");
            }
            if (unit.location() == null) {
                throw new IllegalArgumentException("Khoang " + unit.code().trim() + " chưa có vị trí trên sơ đồ");
            }
            if (unit.location().floorNo() > floors) {
                throw new IllegalArgumentException("Khoang " + unit.code().trim() + " ở tầng "
                        + unit.location().floorNo() + " nhưng cơ sở chỉ có " + floors + " tầng");
            }
            if (!slots.add(unit.location())) {
                throw new IllegalArgumentException(unit.location().label() + " có hai khoang trên sơ đồ");
            }
        }
    }

    private static int requireFloorCount(int floorCount) {
        if (floorCount < 1 || floorCount > UnitLocation.MAX_FLOORS) {
            throw new IllegalArgumentException("Số tầng phải từ 1 đến " + UnitLocation.MAX_FLOORS);
        }
        return floorCount;
    }

    /** {@code null} = chưa ghim; có thì đủ cả vĩ độ và kinh độ, trong miền hợp lệ. */
    private static Coordinates requireCoordinates(Coordinates coordinates) {
        if (coordinates == null || coordinates.latitude() == null && coordinates.longitude() == null) {
            return null;
        }
        if (coordinates.latitude() == null || coordinates.longitude() == null) {
            throw new IllegalArgumentException("Toạ độ cần đủ vĩ độ và kinh độ");
        }
        if (coordinates.latitude().abs().compareTo(BigDecimal.valueOf(90)) > 0
                || coordinates.longitude().abs().compareTo(BigDecimal.valueOf(180)) > 0) {
            throw new IllegalArgumentException("Toạ độ ngoài phạm vi (vĩ độ ±90, kinh độ ±180)");
        }
        return new Coordinates(coordinates.latitude().setScale(6, RoundingMode.HALF_UP),
                coordinates.longitude().setScale(6, RoundingMode.HALF_UP));
    }

    /**
     * PATCH: trường {@code null} giữ nguyên. Toạ độ đổi khi gửi cả hai; giảm số tầng xuống dưới tầng đang có khoang
     * → 409.
     */
    @Transactional
    public FacilityRow updateFacility(Long id, String name, String address, String operatingHours, String status,
                                      Coordinates coordinates, Integer floorCount) {
        FacilityRow before = facilities.find(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy cơ sở"));
        if (status != null && !"Active".equals(status) && !"Inactive".equals(status)) {
            throw new IllegalArgumentException("Trạng thái cơ sở không hợp lệ");
        }
        Coordinates pin = requireCoordinates(coordinates);
        if (pin == null) {
            pin = before.latitude() == null ? null : new Coordinates(before.latitude(), before.longitude());
        }
        int floors = floorCount == null ? before.floorCount() : requireFloorCount(floorCount);
        int highest = facilities.highestPlacedFloor(id);
        if (floors < highest) {
            throw new IllegalStateException("Tầng " + highest + " đang có khoang — không giảm số tầng xuống " + floors
                    + " được");
        }
        facilities.updateFacility(id, name == null ? before.name() : name.trim(),
                address == null ? before.address() : address,
                operatingHours == null ? before.operatingHours() : operatingHours,
                status == null ? before.status() : status, pin, floors);
        FacilityRow after = facilities.find(id).orElseThrow();
        Map<String, Object> oldValue = new HashMap<>();
        oldValue.put("name", before.name());
        oldValue.put("status", before.status());
        oldValue.put("floorCount", before.floorCount());
        oldValue.put("latitude", before.latitude());
        oldValue.put("longitude", before.longitude());
        Map<String, Object> newValue = new HashMap<>();
        newValue.put("name", after.name());
        newValue.put("status", after.status());
        newValue.put("floorCount", after.floorCount());
        newValue.put("latitude", after.latitude());
        newValue.put("longitude", after.longitude());
        audit(AuditAction.FACILITY_UPDATED, AuditEntityType.FACILITY, id, oldValue, newValue);
        return after;
    }

    @Transactional
    public FacilityRow assignManager(Long facilityId, Long fmAccountId, boolean isFm) {
        FacilityRow before = facilities.find(facilityId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy cơ sở"));
        if (fmAccountId != null && !isFm) {
            throw new IllegalArgumentException("Tài khoản được gán phải có role FM và đang hoạt động");
        }
        facilities.assignManager(facilityId, fmAccountId);
        Map<String, Object> oldValue = new HashMap<>();
        oldValue.put("fmAccountId", before.fmAccountId());
        Map<String, Object> newValue = new HashMap<>();
        newValue.put("fmAccountId", fmAccountId);
        audit(AuditAction.FACILITY_UPDATED, AuditEntityType.FACILITY, facilityId, oldValue, newValue);
        return facilities.find(facilityId).orElseThrow();
    }

    @Transactional(readOnly = true)
    public List<PolicyRow> policies() {
        return pricing.policies();
    }

    /**
     * Policy số (giá trị hiện tại là số) chỉ nhận số ≥ 0; policy đang là số nguyên (đọc bằng
     * {@code PricingQuery.intPolicy}) chỉ nhận số nguyên trong miền {@code int} — "2.5" hay "1e2" sẽ làm mọi chỗ đọc
     * policy đó lỗi.
     */
    @Transactional
    public PolicyRow updatePolicy(String key, String value) {
        PolicyRow before = pricing.policy(key)
                .orElseThrow(() -> new IllegalArgumentException("Không có policy " + key));
        if (value == null || value.isBlank() || value.length() > 255) {
            throw new IllegalArgumentException("Giá trị policy bắt buộc, tối đa 255 ký tự");
        }
        if (isNumber(before.value()) && (!isNumber(value) || new BigDecimal(value.trim()).signum() < 0)) {
            throw new IllegalArgumentException("Policy " + key + " phải là số không âm");
        }
        if (isInteger(before.value()) && !isInteger(value)) {
            throw new IllegalArgumentException("Policy " + key + " phải là số nguyên không âm");
        }
        if (isBoolean(before.value()) && !isBoolean(value)) {
            throw new IllegalArgumentException("Policy " + key + " chỉ nhận true hoặc false");
        }
        pricing.updatePolicy(key, value.trim());
        audit(AuditAction.POLICY_UPDATED, AuditEntityType.POLICY, before.id(), Map.of("value", before.value()),
                Map.of("key", key, "value", value.trim()));
        return pricing.policy(key).orElseThrow();
    }

    @Transactional(readOnly = true)
    public List<UnitTypeRow> unitTypes() {
        return pricing.unitTypes();
    }

    @Transactional
    public UnitTypeRow updateUnitTypePrice(Long unitTypeId, BigDecimal monthlyPrice) {
        UnitTypeRow before = pricing.unitTypes().stream().filter(u -> u.id().equals(unitTypeId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy loại khoang"));
        if (monthlyPrice == null) {
            throw new IllegalArgumentException("Giá thuê phải lớn hơn 0");
        }
        requireVndPrice(monthlyPrice);
        pricing.updateUnitTypePrice(unitTypeId, monthlyPrice, currentAccount.getCurrentAccountId());
        audit(AuditAction.UNIT_TYPE_UPDATED, AuditEntityType.UNIT_TYPE, unitTypeId,
                Map.of("monthlyPrice", before.monthlyPrice()), Map.of("monthlyPrice", monthlyPrice));
        return pricing.unitTypes().stream().filter(u -> u.id().equals(unitTypeId)).findFirst().orElseThrow();
    }

    @Transactional(readOnly = true)
    public Report chainReport(Long facilityId, LocalDate from, LocalDate to) {
        return report(facilityId, from, to);
    }

    /** BOM: báo cáo nhân sự toàn chuỗi ({@code facilityId} null) hoặc một cơ sở. */
    @Transactional(readOnly = true)
    public StaffReport chainStaffReport(Long facilityId, LocalDate from, LocalDate to) {
        if (facilityId != null && facilities.find(facilityId).isEmpty()) {
            throw new IllegalArgumentException("Không tìm thấy cơ sở");
        }
        return staffReport(facilityId, from, to);
    }

    @Transactional(readOnly = true)
    public List<InvoiceRow> chainInvoices(Long facilityId, String status, String type) {
        return readRepository.invoices(facilityId, status, type, INVOICE_LIMIT);
    }

    // ------------------------------------------------------------------ internals

    private Report report(Long facilityId, LocalDate from, LocalDate to) {
        LocalDate end = to == null ? LocalDate.now(ZONE) : to;
        LocalDate start = from == null ? end.withDayOfMonth(1).minusMonths(5) : from;
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("Khoảng ngày không hợp lệ");
        }
        List<RevenueRow> revenue = readRepository.revenue(facilityId, start, end);
        BigDecimal total = revenue.stream().map(RevenueRow::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Report(start, end, readRepository.kpis(facilityId), total, revenue,
                readRepository.occupancy(facilityId));
    }

    /** Mặc định tháng hiện tại (giờ VN); tối đa 366 ngày. */
    private StaffReport staffReport(Long facilityId, LocalDate from, LocalDate to) {
        LocalDate today = LocalDate.now(ZONE);
        LocalDate start = from == null ? (to == null ? today : to).withDayOfMonth(1) : from;
        LocalDate end = to == null ? start.withDayOfMonth(start.lengthOfMonth()) : to;
        if (start.isAfter(end) || ChronoUnit.DAYS.between(start, end) >= STAFF_REPORT_MAX_DAYS) {
            throw new IllegalArgumentException("Khoảng ngày không hợp lệ (tối đa " + STAFF_REPORT_MAX_DAYS + " ngày)");
        }
        return new StaffReport(start, end, facilityId, readRepository.staffReport(facilityId, start, end,
                start.atStartOfDay(ZONE).toInstant(), end.plusDays(1).atStartOfDay(ZONE).toInstant()));
    }

    /**
     * Giá thuê ({@code null} = dùng giá loại khoang) phải là số nguyên VND {@code > 0}: giá 0 sinh hoá đơn cọc 0 đ
     * không thanh toán được; cổng (payOS) chỉ nhận số nguyên nên phần lẻ làm số tiền thu ≠ số tiền hoá đơn.
     */
    private static void requireVndPrice(BigDecimal value) {
        if (value == null) {
            return;
        }
        if (value.signum() <= 0) {
            throw new IllegalArgumentException("Giá thuê phải lớn hơn 0");
        }
        if (value.stripTrailingZeros().scale() > 0) {
            throw new IllegalArgumentException("Giá thuê phải là số nguyên (VND)");
        }
    }

    private static boolean isInteger(String value) {
        String trimmed = value.trim();
        if (!trimmed.matches("[0-9]{1,10}")) {
            return false;
        }
        return Long.parseLong(trimmed) <= Integer.MAX_VALUE;
    }

    private static boolean isBoolean(String value) {
        String trimmed = value.trim();
        return "true".equals(trimmed) || "false".equals(trimmed);
    }

    private static boolean isNumber(String value) {
        try {
            new BigDecimal(value.trim());
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private void audit(AuditAction action, AuditEntityType type, Long id, Object oldValue, Object newValue) {
        auditRecorder.record(new AuditEntry(action, type, String.valueOf(id), oldValue, newValue, null,
                currentAccount.getCurrentAccountId()));
    }
}
