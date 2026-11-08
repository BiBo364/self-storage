package vn.lemar.selfstorage.backoffice.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import vn.lemar.selfstorage.facility.application.FacilityAdminCommands;
import vn.lemar.selfstorage.facility.application.FacilityAdminCommands.FacilityRow;
import vn.lemar.selfstorage.facility.application.FacilityAdminCommands.UnitRow;
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

    private final BackofficeReadRepository readRepository;
    private final FacilityAdminCommands facilities;
    private final PricingAdminCommands pricing;
    private final UnitTypeQueryService unitTypes;
    private final Access access;
    private final CurrentAccountProvider currentAccount;
    private final AuditRecorder auditRecorder;

    BackofficeService(BackofficeReadRepository readRepository, FacilityAdminCommands facilities,
                      PricingAdminCommands pricing, UnitTypeQueryService unitTypes, Access access,
                      CurrentAccountProvider currentAccount, AuditRecorder auditRecorder) {
        this.readRepository = readRepository;
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

    // ------------------------------------------------------------------ FM (theo cơ sở)

    @Transactional(readOnly = true)
    public List<InvoiceRow> facilityInvoices(Long facilityId, String status, String type) {
        access.can(PERM_FACILITY, facilityId);
        return readRepository.invoices(facilityId, status, type, INVOICE_LIMIT);
    }

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

    @Transactional
    public UnitRow createUnit(Long facilityId, String code, Long unitTypeId, BigDecimal monthlyPrice) {
        access.can(PERM_FACILITY, facilityId);
        if (code == null || code.isBlank() || code.length() > 50) {
            throw new IllegalArgumentException("Mã khoang bắt buộc, tối đa 50 ký tự");
        }
        if (unitTypeId == null || !unitTypes.exists(unitTypeId)) {
            throw new IllegalArgumentException("Loại khoang không tồn tại");
        }
        requireVndPrice(monthlyPrice);
        Long id = facilities.createUnit(facilityId, code.trim(), unitTypeId, monthlyPrice);
        UnitRow unit = facilities.findUnit(id).orElseThrow();
        Map<String, Object> value = new HashMap<>();
        value.put("code", unit.code());
        value.put("unitTypeId", unitTypeId);
        value.put("monthlyPrice", monthlyPrice);
        audit(AuditAction.STORAGE_UNIT_CREATED, AuditEntityType.STORAGE_UNIT, id, null, value);
        return unit;
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

    @Transactional
    public FacilityRow createFacility(String code, String name, String address, String operatingHours) {
        if (code == null || code.isBlank() || name == null || name.isBlank()) {
            throw new IllegalArgumentException("Mã và tên cơ sở bắt buộc");
        }
        Long id = facilities.createFacility(code.trim().toUpperCase(), name.trim(), address, operatingHours);
        audit(AuditAction.FACILITY_CREATED, AuditEntityType.FACILITY, id, null,
                Map.of("code", code.trim().toUpperCase(), "name", name.trim()));
        return facilities.find(id).orElseThrow();
    }

    @Transactional
    public FacilityRow updateFacility(Long id, String name, String address, String operatingHours, String status) {
        FacilityRow before = facilities.find(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy cơ sở"));
        if (status != null && !"Active".equals(status) && !"Inactive".equals(status)) {
            throw new IllegalArgumentException("Trạng thái cơ sở không hợp lệ");
        }
        facilities.updateFacility(id, name == null ? before.name() : name.trim(),
                address == null ? before.address() : address,
                operatingHours == null ? before.operatingHours() : operatingHours,
                status == null ? before.status() : status);
        FacilityRow after = facilities.find(id).orElseThrow();
        audit(AuditAction.FACILITY_UPDATED, AuditEntityType.FACILITY, id,
                Map.of("name", before.name(), "status", before.status()),
                Map.of("name", after.name(), "status", after.status()));
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

    @Transactional(readOnly = true)
    public List<InvoiceRow> chainInvoices(Long facilityId, String status, String type) {
        return readRepository.invoices(facilityId, status, type, INVOICE_LIMIT);
    }

    // ------------------------------------------------------------------ internals

    private Report report(Long facilityId, LocalDate from, LocalDate to) {
        LocalDate end = to == null ? LocalDate.now() : to;
        LocalDate start = from == null ? end.withDayOfMonth(1).minusMonths(5) : from;
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("Khoảng ngày không hợp lệ");
        }
        List<RevenueRow> revenue = readRepository.revenue(facilityId, start, end);
        BigDecimal total = revenue.stream().map(RevenueRow::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Report(start, end, readRepository.kpis(facilityId), total, revenue,
                readRepository.occupancy(facilityId));
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
