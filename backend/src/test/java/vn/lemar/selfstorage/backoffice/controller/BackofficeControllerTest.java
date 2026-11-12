package vn.lemar.selfstorage.backoffice.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import vn.lemar.selfstorage.SecurityConfig;
import vn.lemar.selfstorage.backoffice.application.BackofficeService;
import vn.lemar.selfstorage.backoffice.application.BackofficeService.Report;
import vn.lemar.selfstorage.backoffice.repository.BackofficeReadRepository;
import vn.lemar.selfstorage.backoffice.repository.BackofficeReadRepository.Kpis;
import vn.lemar.selfstorage.facility.application.FacilityAdminCommands.Coordinates;
import vn.lemar.selfstorage.facility.application.FacilityAdminCommands.FacilityRow;
import vn.lemar.selfstorage.facility.application.FacilityAdminCommands.UnitRow;
import vn.lemar.selfstorage.identity.application.AccountAdminService;
import vn.lemar.selfstorage.identity.application.AccountAdminService.AccountRow;
import vn.lemar.selfstorage.identity.application.AccountJwtAuthenticationConverter;
import vn.lemar.selfstorage.identity.application.CurrentAccountProvider;
import vn.lemar.selfstorage.identity.config.JwtConfiguration;
import vn.lemar.selfstorage.identity.domain.Account;
import vn.lemar.selfstorage.identity.domain.AccountStatus;
import vn.lemar.selfstorage.identity.domain.Role;
import vn.lemar.selfstorage.identity.repository.AccountRepository;
import vn.lemar.selfstorage.pricing.application.PricingAdminCommands.PolicyRow;
import vn.lemar.selfstorage.support.application.SupportService;
import vn.lemar.selfstorage.support.application.SupportService.SupportView;
import vn.lemar.selfstorage.support.controller.SupportController;
import vn.lemar.selfstorage.tenancy.controller.TenancyExceptionHandler;

/** HTTP contract back-office (FM / BOM / Admin) + hỗ trợ; role-bound theo namespace. */
@WebMvcTest(controllers = {BackofficeController.class, SupportController.class})
@Import({SecurityConfig.class, JwtConfiguration.class, AccountJwtAuthenticationConverter.class,
    BackofficeExceptionHandler.class, TenancyExceptionHandler.class})
@TestPropertySource(properties = "security.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=")
class BackofficeControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtEncoder jwtEncoder;

    @MockBean
    private BackofficeService backoffice;
    @MockBean
    private AccountAdminService accounts;
    @MockBean
    private BackofficeReadRepository readRepository;
    @MockBean
    private SupportService support;
    @MockBean
    private CurrentAccountProvider currentAccountProvider;
    @MockBean
    private AccountRepository accountRepository;

    @Test
    void managerRoutes() throws Exception {
        login("fm@x.vn", "FM", 2L);
        UnitRow unit = new UnitRow(9L, "A-9", 1L, 1L, "Available", null, null, null, null, null);
        when(backoffice.facilityContracts(1L, "Active")).thenReturn(List.of());
        when(backoffice.facilityReport(eq(1L), any(), any())).thenReturn(report());
        when(backoffice.createUnit(1L, "A-9", 1L, null, null)).thenReturn(unit);
        when(backoffice.updateUnit(9L, null, new BigDecimal("1000"))).thenReturn(unit);
        when(backoffice.setMaintenance(9L, true)).thenThrow(new IllegalStateException("Đang có đơn"));
        when(backoffice.setAccessOptions(1L, true, false)).thenReturn(facility());
        when(support.facilityRequests(1L, false)).thenReturn(List.of(supportView()));
        when(support.assign(5L, 3L)).thenReturn(supportView());

        mockMvc.perform(as(get("/api/fm/contracts?facilityId=1&status=Active"), "fm@x.vn")).andExpect(status().isOk());
        mockMvc.perform(as(get("/api/fm/reports?facilityId=1&from=2026-01-01"), "fm@x.vn"))
                .andExpect(jsonPath("$.data.totalRevenue").value(1000));
        mockMvc.perform(json(post("/api/fm/storage-units"), "fm@x.vn",
                "{\"facilityId\":1,\"code\":\"A-9\",\"unitTypeId\":1}")).andExpect(jsonPath("$.data.id").value(9));
        mockMvc.perform(json(patch("/api/fm/storage-units/9"), "fm@x.vn", "{\"monthlyPrice\":1000}"))
                .andExpect(status().isOk());
        mockMvc.perform(json(post("/api/fm/storage-units/9/maintenance"), "fm@x.vn", "{\"maintenance\":true}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("Đang có đơn"));
        mockMvc.perform(json(put("/api/fm/facilities/1/access-options"), "fm@x.vn",
                "{\"keyAccess\":true,\"codeAccess\":false}")).andExpect(status().isOk());
        mockMvc.perform(as(get("/api/fm/support-requests?facilityId=1"), "fm@x.vn")).andExpect(status().isOk());
        mockMvc.perform(json(post("/api/fm/support-requests/5/assign"), "fm@x.vn", "{\"staffId\":3}"))
                .andExpect(status().isOk());
        mockMvc.perform(as(get("/api/bom/facilities"), "fm@x.vn")).andExpect(status().isForbidden());
    }

    @Test
    void managerHoldsPostReturnMaintenance() throws Exception {
        login("fm@x.vn", "FM", 2L);
        when(backoffice.holdMaintenance(9L)).thenReturn(
                new UnitRow(9L, "A-9", 1L, 1L, "Maintenance", null, null, null, null, null));
        when(backoffice.holdMaintenance(10L))
                .thenThrow(new IllegalStateException("Chỉ giữ bảo trì được cho khoang đang bảo trì sau trả kho"));

        mockMvc.perform(as(post("/api/fm/storage-units/9/maintenance-hold"), "fm@x.vn"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("Maintenance"));
        mockMvc.perform(as(post("/api/fm/storage-units/10/maintenance-hold"), "fm@x.vn"))
                .andExpect(status().isConflict());
        login("fs@x.vn", "FS", 3L);
        mockMvc.perform(as(post("/api/fm/storage-units/9/maintenance-hold"), "fs@x.vn"))
                .andExpect(status().isForbidden());
    }

    @Test
    void bomRoutes() throws Exception {
        login("bom@x.vn", "BOM", 3L);
        when(backoffice.listFacilities()).thenReturn(List.of(facility()));
        when(backoffice.createFacility("Q9", "Quận 9", null, null, new Coordinates(null, null), null, List.of()))
                .thenReturn(facility());
        when(backoffice.updateFacility(1L, "Mới", null, null, null, new Coordinates(null, null), null))
                .thenReturn(facility());
        when(accounts.get(4L)).thenReturn(account("FM"));
        when(backoffice.assignManager(1L, 4L, true)).thenReturn(facility());
        when(backoffice.policies()).thenReturn(List.of(new PolicyRow(1L, "k", "1", null, Instant.now())));
        when(backoffice.updatePolicy("k", "x")).thenThrow(new IllegalArgumentException("phải là số"));
        when(backoffice.updateUnitTypePrice(1L, new BigDecimal("500000"))).thenReturn(null);
        when(backoffice.chainReport(isNull(), any(), any())).thenReturn(report());
        when(accounts.list("FM", "ACTIVE", null, 0, 200)).thenReturn(List.of(account("FM")));

        mockMvc.perform(as(get("/api/bom/facilities"), "bom@x.vn")).andExpect(jsonPath("$.data[0].code").value("Q7"));
        mockMvc.perform(json(post("/api/bom/facilities"), "bom@x.vn", "{\"code\":\"Q9\",\"name\":\"Quận 9\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(json(patch("/api/bom/facilities/1"), "bom@x.vn", "{\"name\":\"Mới\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(json(put("/api/bom/facilities/1/manager"), "bom@x.vn", "{\"fmAccountId\":4}"))
                .andExpect(status().isOk());
        mockMvc.perform(as(get("/api/bom/managers"), "bom@x.vn")).andExpect(status().isOk());
        mockMvc.perform(as(get("/api/bom/policies"), "bom@x.vn")).andExpect(status().isOk());
        mockMvc.perform(json(put("/api/bom/policies/k"), "bom@x.vn", "{\"value\":\"x\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(as(get("/api/bom/unit-types"), "bom@x.vn")).andExpect(status().isOk());
        mockMvc.perform(json(put("/api/bom/unit-types/1/price"), "bom@x.vn", "{\"monthlyPrice\":500000}"))
                .andExpect(status().isOk());
        mockMvc.perform(as(get("/api/bom/reports"), "bom@x.vn")).andExpect(status().isOk());
        mockMvc.perform(as(get("/api/bom/invoices?status=Paid"), "bom@x.vn")).andExpect(status().isOk());
    }

    @Test
    void bomAssignsStaffFacilities() throws Exception {
        login("bom@x.vn", "BOM", 3L);
        when(accounts.list("FS", null, null, 0, 200)).thenReturn(List.of(account("FS")));
        when(accounts.setStaffFacilities(5L, List.of(2L), 3L)).thenReturn(account("FS"));
        when(accounts.setStaffFacilities(6L, List.of(), 3L))
                .thenThrow(new IllegalStateException("FS còn việc chưa đóng tại cơ sở Q7: lịch check-in #12"));

        mockMvc.perform(as(get("/api/bom/staff"), "bom@x.vn"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].role").value("FS"));
        mockMvc.perform(json(put("/api/bom/staff/5/facilities"), "bom@x.vn", "{\"facilityIds\":[2]}"))
                .andExpect(status().isOk());
        mockMvc.perform(json(put("/api/bom/staff/6/facilities"), "bom@x.vn", "{\"facilityIds\":[]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("FS còn việc chưa đóng tại cơ sở Q7: lịch check-in #12"));
        mockMvc.perform(json(put("/api/bom/staff/5/facilities"), "bom@x.vn", "{}"))
                .andExpect(status().isBadRequest());

        login("fm@x.vn", "FM", 2L);
        mockMvc.perform(json(put("/api/bom/staff/5/facilities"), "fm@x.vn", "{\"facilityIds\":[2]}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminRoutes() throws Exception {
        login("admin@x.vn", "ADMIN", 1L);
        when(accounts.list(isNull(), isNull(), eq("fs"), eq(0), eq(50))).thenReturn(List.of(account("FS")));
        // Body không gửi facilityIds → controller truyền null (gán cơ sở lúc tạo là tuỳ chọn).
        when(accounts.create("n@x.vn", "Test@1234", null, null, "FS", null, 1L)).thenReturn(account("FS"));
        when(accounts.update(4L, "FM", null, null, null, 1L)).thenReturn(account("FM"));
        when(accounts.setStaffFacilities(4L, List.of(1L), 1L)).thenReturn(account("FS"));
        when(accounts.loginEvents(isNull(), anyInt(), anyInt())).thenReturn(List.of());
        when(readRepository.auditLogs(any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(List.of());
        when(accounts.permissionMatrix()).thenReturn(List.of());
        when(accounts.setRolePermissions(eq("FS"), any(), anyLong())).thenReturn(List.of());

        mockMvc.perform(as(get("/api/admin/accounts?q=fs"), "admin@x.vn")).andExpect(status().isOk());
        mockMvc.perform(json(post("/api/admin/accounts"), "admin@x.vn",
                "{\"email\":\"n@x.vn\",\"password\":\"Test@1234\",\"role\":\"FS\"}")).andExpect(status().isOk());
        mockMvc.perform(json(post("/api/admin/accounts"), "admin@x.vn", "{\"email\":\"bad\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(json(patch("/api/admin/accounts/4"), "admin@x.vn", "{\"role\":\"FM\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(json(put("/api/admin/accounts/4/facilities"), "admin@x.vn", "{\"facilityIds\":[1]}"))
                .andExpect(status().isOk());
        mockMvc.perform(as(get("/api/admin/login-events"), "admin@x.vn")).andExpect(status().isOk());
        mockMvc.perform(as(get("/api/admin/audit-logs?action=X"), "admin@x.vn")).andExpect(status().isOk());
        mockMvc.perform(as(get("/api/admin/permissions"), "admin@x.vn")).andExpect(status().isOk());
        mockMvc.perform(json(put("/api/admin/roles/FS/permissions"), "admin@x.vn",
                "{\"rental_order.read\":\"FACILITY\"}")).andExpect(status().isOk());
    }

    @Test
    void supportRoutesForCustomerAndStaff() throws Exception {
        login("c@x.vn", "CUSTOMER", 6L);
        when(support.mine(6L)).thenReturn(List.of(supportView()));
        when(support.create(eq(6L), any())).thenReturn(supportView());
        when(support.cancel(5L, 6L)).thenReturn(supportView());
        mockMvc.perform(as(get("/api/customer/support-requests"), "c@x.vn")).andExpect(status().isOk());
        mockMvc.perform(json(post("/api/customer/support-requests"), "c@x.vn",
                "{\"facilityId\":1,\"category\":\"OTHER\",\"title\":\"t\",\"description\":\"d\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(as(post("/api/customer/support-requests/5/cancel"), "c@x.vn")).andExpect(status().isOk());

        login("fs@x.vn", "FS", 3L);
        when(support.staffRequests()).thenReturn(List.of(supportView()));
        when(support.resolve(eq(5L), any())).thenReturn(supportView());
        mockMvc.perform(as(get("/api/staff/support-requests"), "fs@x.vn")).andExpect(status().isOk());
        mockMvc.perform(json(post("/api/staff/support-requests/5/resolve"), "fs@x.vn",
                "{\"resolution\":\"Đã xử lý\",\"newKeyQuantity\":2}")).andExpect(status().isOk());
        login("fm@x.vn", "FM", 2L);
        mockMvc.perform(json(post("/api/fm/support-requests/5/resolve"), "fm@x.vn", "{\"resolution\":\"ok\"}"))
                .andExpect(status().isOk());
    }

    private static Report report() {
        return new Report(LocalDate.now(), LocalDate.now(),
                new Kpis(1, 2, 3, 0, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO), new BigDecimal("1000"), List.of(),
                List.of());
    }

    private static FacilityRow facility() {
        return new FacilityRow(1L, "Q7", "Quận 7", null, null, "Active", 4L, "fm@x.vn", true, true, 8, 2, 1, null,
                null, 1);
    }

    private static AccountRow account(String role) {
        return new AccountRow(4L, "a@x.vn", "A", null, role, "ACTIVE", true, Instant.now(), List.of(), null);
    }

    private static SupportView supportView() {
        return new SupportView(5L, 6L, "Khách", "c@x.vn", 1L, "Q7", null, null, null, "OTHER", "t", "d", List.of(),
                "Open", null, null, null, null, Instant.now(), null, null);
    }

    private void login(String email, String roleName, Long accountId) {
        Role role = mock(Role.class);
        when(role.getName()).thenReturn(roleName);
        Account account = mock(Account.class);
        when(account.getEmail()).thenReturn(email);
        when(account.getRole()).thenReturn(role);
        when(account.getStatus()).thenReturn(AccountStatus.ACTIVE);
        when(account.isLoginAllowed()).thenReturn(true);
        when(accountRepository.findWithRoleByEmail(email)).thenReturn(Optional.of(account));
        when(currentAccountProvider.getCurrentAccountId()).thenReturn(accountId);
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String email) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(email));
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String email, String body) {
        return as(request, email).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private String tokenFor(String email) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer("self-storage").issuedAt(now)
                .expiresAt(now.plusSeconds(60)).claim("email", email).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }
}
