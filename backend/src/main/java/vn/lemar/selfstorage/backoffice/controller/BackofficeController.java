package vn.lemar.selfstorage.backoffice.controller;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.lemar.selfstorage.ApiEnvelope;
import vn.lemar.selfstorage.backoffice.application.BackofficeService;
import vn.lemar.selfstorage.backoffice.application.BackofficeService.Report;
import vn.lemar.selfstorage.backoffice.repository.BackofficeReadRepository;
import vn.lemar.selfstorage.backoffice.repository.BackofficeReadRepository.AuditRow;
import vn.lemar.selfstorage.backoffice.repository.BackofficeReadRepository.ContractRow;
import vn.lemar.selfstorage.backoffice.repository.BackofficeReadRepository.InvoiceRow;
import vn.lemar.selfstorage.facility.application.FacilityAdminCommands.FacilityRow;
import vn.lemar.selfstorage.facility.application.FacilityAdminCommands.UnitRow;
import vn.lemar.selfstorage.identity.application.AccountAdminService;
import vn.lemar.selfstorage.identity.application.AccountAdminService.AccountRow;
import vn.lemar.selfstorage.identity.application.AccountAdminService.LoginEventRow;
import vn.lemar.selfstorage.identity.application.AccountAdminService.PermissionRow;
import vn.lemar.selfstorage.identity.application.CurrentAccountProvider;
import vn.lemar.selfstorage.pricing.application.PricingAdminCommands.PolicyRow;
import vn.lemar.selfstorage.pricing.application.PricingAdminCommands.UnitTypeRow;

/**
 * Back-office: FM ({@code /api/fm/**}, theo cơ sở), BOM ({@code /api/bom/**}, toàn chuỗi), Admin
 * ({@code /api/admin/**}, tài khoản / phân quyền / nhật ký). Quyền theo role do {@code SecurityConfig} chặn ở
 * namespace.
 */
@RestController
public class BackofficeController {

    private final BackofficeService backoffice;
    private final AccountAdminService accounts;
    private final BackofficeReadRepository readRepository;
    private final CurrentAccountProvider currentAccount;

    public BackofficeController(BackofficeService backoffice, AccountAdminService accounts,
                                BackofficeReadRepository readRepository, CurrentAccountProvider currentAccount) {
        this.backoffice = backoffice;
        this.accounts = accounts;
        this.readRepository = readRepository;
        this.currentAccount = currentAccount;
    }

    // ------------------------------------------------------------------ FM

    @GetMapping("/api/fm/invoices")
    public ResponseEntity<ApiEnvelope<List<InvoiceRow>>> fmInvoices(@RequestParam Long facilityId,
            @RequestParam(required = false) String status, @RequestParam(required = false) String type) {
        return ok("Hoá đơn của cơ sở", backoffice.facilityInvoices(facilityId, status, type));
    }

    @GetMapping("/api/fm/contracts")
    public ResponseEntity<ApiEnvelope<List<ContractRow>>> fmContracts(@RequestParam Long facilityId,
            @RequestParam(required = false) String status) {
        return ok("Hợp đồng của cơ sở", backoffice.facilityContracts(facilityId, status));
    }

    @Operation(summary = "Báo cáo cơ sở", description = "KPI, doanh thu theo tháng × loại hoá đơn, lấp đầy khoang.")
    @GetMapping("/api/fm/reports")
    public ResponseEntity<ApiEnvelope<Report>> fmReport(@RequestParam Long facilityId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ok("Báo cáo cơ sở", backoffice.facilityReport(facilityId, from, to));
    }

    @PostMapping("/api/fm/storage-units")
    public ResponseEntity<ApiEnvelope<UnitRow>> createUnit(@Valid @RequestBody UnitBody body) {
        return ok("Đã thêm khoang", backoffice.createUnit(body.facilityId(), body.code(), body.unitTypeId(),
                body.monthlyPrice()));
    }

    @PatchMapping("/api/fm/storage-units/{unitId}")
    public ResponseEntity<ApiEnvelope<UnitRow>> updateUnit(@PathVariable Long unitId,
                                                           @RequestBody UnitUpdateBody body) {
        return ok("Đã cập nhật khoang", backoffice.updateUnit(unitId, body.unitTypeId(), body.monthlyPrice()));
    }

    @PostMapping("/api/fm/storage-units/{unitId}/maintenance")
    public ResponseEntity<ApiEnvelope<UnitRow>> maintenance(@PathVariable Long unitId,
                                                            @Valid @RequestBody MaintenanceBody body) {
        return ok(body.maintenance() ? "Đã chuyển khoang sang bảo trì" : "Đã mở lại khoang",
                backoffice.setMaintenance(unitId, body.maintenance()));
    }

    @GetMapping("/api/fm/facilities/{facilityId}/access-options")
    public ResponseEntity<ApiEnvelope<FacilityRow>> fmFacility(@PathVariable Long facilityId) {
        return ok("Cơ sở", backoffice.facility(facilityId));
    }

    @PutMapping("/api/fm/facilities/{facilityId}/access-options")
    public ResponseEntity<ApiEnvelope<FacilityRow>> fmAccessOptions(@PathVariable Long facilityId,
                                                                    @Valid @RequestBody AccessBody body) {
        return ok("Đã cập nhật hình thức truy cập",
                backoffice.setAccessOptions(facilityId, body.keyAccess(), body.codeAccess()));
    }

    // ------------------------------------------------------------------ BOM

    @GetMapping("/api/bom/facilities")
    public ResponseEntity<ApiEnvelope<List<FacilityRow>>> facilities() {
        return ok("Danh sách cơ sở", backoffice.listFacilities());
    }

    @PostMapping("/api/bom/facilities")
    public ResponseEntity<ApiEnvelope<FacilityRow>> createFacility(@Valid @RequestBody FacilityBody body) {
        return ok("Đã tạo cơ sở", backoffice.createFacility(body.code(), body.name(), body.address(),
                body.operatingHours()));
    }

    @PatchMapping("/api/bom/facilities/{id}")
    public ResponseEntity<ApiEnvelope<FacilityRow>> updateFacility(@PathVariable Long id,
                                                                   @RequestBody FacilityUpdateBody body) {
        return ok("Đã cập nhật cơ sở", backoffice.updateFacility(id, body.name(), body.address(),
                body.operatingHours(), body.status()));
    }

    @PutMapping("/api/bom/facilities/{id}/manager")
    public ResponseEntity<ApiEnvelope<FacilityRow>> assignManager(@PathVariable Long id,
                                                                  @RequestBody ManagerBody body) {
        boolean isFm = body.fmAccountId() != null && "FM".equals(accounts.get(body.fmAccountId()).role());
        return ok("Đã gán FM", backoffice.assignManager(id, body.fmAccountId(), isFm));
    }

    @PutMapping("/api/bom/facilities/{id}/access-options")
    public ResponseEntity<ApiEnvelope<FacilityRow>> bomAccessOptions(@PathVariable Long id,
                                                                     @Valid @RequestBody AccessBody body) {
        return fmAccessOptions(id, body);
    }

    @GetMapping("/api/bom/managers")
    public ResponseEntity<ApiEnvelope<List<AccountRow>>> managers() {
        return ok("Tài khoản FM", accounts.list("FM", "ACTIVE", null, 0, 200));
    }

    @GetMapping("/api/bom/policies")
    public ResponseEntity<ApiEnvelope<List<PolicyRow>>> policies() {
        return ok("Chính sách", backoffice.policies());
    }

    @PutMapping("/api/bom/policies/{key}")
    public ResponseEntity<ApiEnvelope<PolicyRow>> updatePolicy(@PathVariable String key,
                                                               @Valid @RequestBody PolicyBody body) {
        return ok("Đã cập nhật chính sách", backoffice.updatePolicy(key, body.value()));
    }

    @GetMapping("/api/bom/unit-types")
    public ResponseEntity<ApiEnvelope<List<UnitTypeRow>>> unitTypes() {
        return ok("Loại khoang", backoffice.unitTypes());
    }

    @PutMapping("/api/bom/unit-types/{id}/price")
    public ResponseEntity<ApiEnvelope<UnitTypeRow>> unitTypePrice(@PathVariable Long id,
                                                                  @Valid @RequestBody PriceBody body) {
        return ok("Đã cập nhật giá", backoffice.updateUnitTypePrice(id, body.monthlyPrice()));
    }

    @PatchMapping("/api/bom/storage-units/{unitId}")
    public ResponseEntity<ApiEnvelope<UnitRow>> bomUpdateUnit(@PathVariable Long unitId,
                                                              @RequestBody UnitUpdateBody body) {
        return updateUnit(unitId, body);
    }

    @GetMapping("/api/bom/reports")
    public ResponseEntity<ApiEnvelope<Report>> bomReport(@RequestParam(required = false) Long facilityId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ok("Báo cáo doanh thu", backoffice.chainReport(facilityId, from, to));
    }

    @GetMapping("/api/bom/invoices")
    public ResponseEntity<ApiEnvelope<List<InvoiceRow>>> bomInvoices(@RequestParam(required = false) Long facilityId,
            @RequestParam(required = false) String status, @RequestParam(required = false) String type) {
        return ok("Hoá đơn toàn chuỗi", backoffice.chainInvoices(facilityId, status, type));
    }

    // ------------------------------------------------------------------ Admin

    @GetMapping("/api/admin/accounts")
    public ResponseEntity<ApiEnvelope<List<AccountRow>>> accounts(@RequestParam(required = false) String role,
            @RequestParam(required = false) String status, @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return ok("Tài khoản", accounts.list(role, status, q, Math.max(page, 0), Math.min(Math.max(size, 1), 200)));
    }

    @PostMapping("/api/admin/accounts")
    public ResponseEntity<ApiEnvelope<AccountRow>> createAccount(@Valid @RequestBody AccountBody body) {
        return ok("Đã tạo tài khoản", accounts.create(body.email(), body.password(), body.name(), body.phone(),
                body.role(), me()));
    }

    @PatchMapping("/api/admin/accounts/{id}")
    public ResponseEntity<ApiEnvelope<AccountRow>> updateAccount(@PathVariable Long id,
                                                                 @RequestBody AccountUpdateBody body) {
        return ok("Đã cập nhật tài khoản", accounts.update(id, body.role(), body.status(), body.name(),
                body.phone(), me()));
    }

    @PutMapping("/api/admin/accounts/{id}/facilities")
    public ResponseEntity<ApiEnvelope<AccountRow>> staffFacilities(@PathVariable Long id,
                                                                   @Valid @RequestBody FacilitiesBody body) {
        return ok("Đã gán cơ sở", accounts.setStaffFacilities(id, body.facilityIds(), me()));
    }

    @GetMapping("/api/admin/login-events")
    public ResponseEntity<ApiEnvelope<List<LoginEventRow>>> loginEvents(@RequestParam(required = false) String email,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return ok("Lịch sử đăng nhập", accounts.loginEvents(email, Math.max(page, 0),
                Math.min(Math.max(size, 1), 200)));
    }

    @GetMapping("/api/admin/audit-logs")
    public ResponseEntity<ApiEnvelope<List<AuditRow>>> auditLogs(@RequestParam(required = false) String action,
            @RequestParam(required = false) String entityType, @RequestParam(required = false) String entityId,
            @RequestParam(required = false) String actor, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ok("Nhật ký thao tác", readRepository.auditLogs(action, entityType, entityId, actor,
                Math.max(page, 0), Math.min(Math.max(size, 1), 200)));
    }

    @GetMapping("/api/admin/permissions")
    public ResponseEntity<ApiEnvelope<List<PermissionRow>>> permissions() {
        return ok("Ma trận phân quyền", accounts.permissionMatrix());
    }

    @PutMapping("/api/admin/roles/{role}/permissions")
    public ResponseEntity<ApiEnvelope<List<PermissionRow>>> setPermissions(@PathVariable String role,
                                                                           @RequestBody Map<String, String> scopes) {
        return ok("Đã cập nhật quyền của " + role, accounts.setRolePermissions(role, scopes, me()));
    }

    private Long me() {
        return currentAccount.getCurrentAccountId();
    }

    private static <T> ResponseEntity<ApiEnvelope<T>> ok(String message, T data) {
        return ResponseEntity.ok(ApiEnvelope.ok(message, data));
    }

    public record UnitBody(@NotNull Long facilityId, @NotBlank @Size(max = 50) String code, @NotNull Long unitTypeId,
                           BigDecimal monthlyPrice) {
    }

    public record UnitUpdateBody(Long unitTypeId, BigDecimal monthlyPrice) {
    }

    public record MaintenanceBody(@NotNull Boolean maintenance) {
    }

    public record AccessBody(@NotNull Boolean keyAccess, @NotNull Boolean codeAccess) {
    }

    public record FacilityBody(@NotBlank @Size(max = 50) String code, @NotBlank @Size(max = 255) String name,
                               String address, String operatingHours) {
    }

    public record FacilityUpdateBody(String name, String address, String operatingHours, String status) {
    }

    public record ManagerBody(Long fmAccountId) {
    }

    public record PolicyBody(@NotBlank String value) {
    }

    public record PriceBody(@NotNull BigDecimal monthlyPrice) {
    }

    public record AccountBody(@NotBlank @Email String email, @NotBlank @Size(min = 8, max = 100) String password,
                              String name, String phone, @NotBlank String role) {
    }

    public record AccountUpdateBody(String role, String status, String name, String phone) {
    }

    public record FacilitiesBody(@NotNull List<Long> facilityIds) {
    }
}
