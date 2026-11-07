package vn.lemar.selfstorage.tenancy.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import vn.lemar.selfstorage.booking.application.ContractInvoiceService.ContractBill;
import vn.lemar.selfstorage.checkout.application.CheckoutService;
import vn.lemar.selfstorage.checkout.application.CheckoutService.CheckoutView;
import vn.lemar.selfstorage.checkout.controller.CheckoutController;
import vn.lemar.selfstorage.identity.application.AccountJwtAuthenticationConverter;
import vn.lemar.selfstorage.identity.application.CurrentAccountProvider;
import vn.lemar.selfstorage.identity.config.JwtConfiguration;
import vn.lemar.selfstorage.identity.domain.Account;
import vn.lemar.selfstorage.identity.domain.AccountStatus;
import vn.lemar.selfstorage.identity.domain.Role;
import vn.lemar.selfstorage.identity.repository.AccountRepository;
import vn.lemar.selfstorage.overdue.application.OverdueService;
import vn.lemar.selfstorage.overdue.application.OverdueService.OverdueContract;
import vn.lemar.selfstorage.overdue.controller.OverdueController;
import vn.lemar.selfstorage.tenancy.application.TenancyException;
import vn.lemar.selfstorage.tenancy.application.TenancyService;
import vn.lemar.selfstorage.tenancy.application.TenancyService.RentalView;
import vn.lemar.selfstorage.tenancy.application.TenancyService.ReturnRequestView;

/** HTTP contract kho đang thuê, trả kho, quá hạn + map {@link TenancyException}. */
@WebMvcTest(controllers = {TenancyController.class, CheckoutController.class, OverdueController.class})
@Import({SecurityConfig.class, JwtConfiguration.class, AccountJwtAuthenticationConverter.class,
    TenancyExceptionHandler.class})
@TestPropertySource(properties = "security.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=")
class PostRentalControllerTest {

    private static final String CUSTOMER = "customer@example.com";
    private static final String FS = "fs@example.com";
    private static final String FM = "fm@example.com";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtEncoder jwtEncoder;

    @MockBean
    private TenancyService tenancy;
    @MockBean
    private CheckoutService checkout;
    @MockBean
    private OverdueService overdue;
    @MockBean
    private CurrentAccountProvider currentAccountProvider;
    @MockBean
    private AccountRepository accountRepository;

    @Test
    void customerRentalRoutes() throws Exception {
        login(CUSTOMER, "CUSTOMER", 6L);
        when(tenancy.rentals(6L)).thenReturn(List.of(rental()));
        when(tenancy.rental(7L, 6L)).thenReturn(rental());
        when(tenancy.requestExtension(7L, 6L, 2)).thenReturn(new ContractBill(9L, "INV-EXT-1", "Extension",
                "Unpaid", "Gia hạn", new BigDecimal("200000"), Instant.now(), Instant.now()));
        when(tenancy.requestReturn(eq(7L), eq(6L), any(), eq("x"))).thenReturn(returnView("Pending"));
        when(tenancy.myReturns(6L)).thenReturn(List.of(returnView("Pending")));
        when(tenancy.cancelReturn(3L, 6L)).thenReturn(returnView("Canceled"));
        when(checkout.forCustomer(3L, 6L)).thenReturn(checkoutView());

        mockMvc.perform(as(get("/api/customer/rentals"), CUSTOMER))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].contractId").value(7));
        mockMvc.perform(as(get("/api/customer/rentals/7"), CUSTOMER)).andExpect(status().isOk());
        mockMvc.perform(json(post("/api/customer/rentals/7/extensions"), CUSTOMER, "{\"months\":2}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.code").value("INV-EXT-1"));
        mockMvc.perform(json(post("/api/customer/rentals/7/extensions"), CUSTOMER, "{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(json(post("/api/customer/rentals/7/return-request"), CUSTOMER,
                        "{\"preferredDate\":\"2026-11-20\",\"note\":\"x\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("Pending"));
        mockMvc.perform(as(get("/api/customer/return-requests"), CUSTOMER)).andExpect(status().isOk());
        mockMvc.perform(as(post("/api/customer/return-requests/3/cancel"), CUSTOMER))
                .andExpect(jsonPath("$.data.status").value("Canceled"));
        mockMvc.perform(as(get("/api/customer/return-requests/3/checkout"), CUSTOMER))
                .andExpect(jsonPath("$.data.result").value("IN_PROGRESS"));
    }

    @Test
    void tenancyErrorsMapToStatus() throws Exception {
        login(CUSTOMER, "CUSTOMER", 6L);
        when(tenancy.rental(1L, 6L)).thenThrow(TenancyException.notFound("Không tìm thấy hợp đồng thuê"));
        when(tenancy.rental(2L, 6L)).thenThrow(TenancyException.conflict("Đã có yêu cầu"));
        when(tenancy.rental(3L, 6L)).thenThrow(TenancyException.invalid("Sai"));
        when(tenancy.rental(4L, 6L)).thenThrow(new TenancyException(TenancyException.Kind.FORBIDDEN, "Cấm"));

        mockMvc.perform(as(get("/api/customer/rentals/1"), CUSTOMER)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Không tìm thấy hợp đồng thuê"));
        mockMvc.perform(as(get("/api/customer/rentals/2"), CUSTOMER)).andExpect(status().isConflict());
        mockMvc.perform(as(get("/api/customer/rentals/3"), CUSTOMER)).andExpect(status().isBadRequest());
        mockMvc.perform(as(get("/api/customer/rentals/4"), CUSTOMER)).andExpect(status().isForbidden());
    }

    @Test
    void managerAndStaffRoutes() throws Exception {
        login(FM, "FM", 2L);
        when(tenancy.facilityReturns(1L)).thenReturn(List.of(returnView("Pending")));
        when(tenancy.assign(3L, 5L)).thenReturn(returnView("Assigned"));
        when(overdue.facilityOverdue(1L)).thenReturn(List.of(new OverdueContract(7L, "HD-1", 4L, "A-01",
                LocalDate.now(), 5, 2, new BigDecimal("40000"), null, false)));

        mockMvc.perform(as(get("/api/fm/return-requests?facilityId=1"), FM)).andExpect(status().isOk());
        mockMvc.perform(json(post("/api/fm/return-requests/3/assign"), FM, "{\"staffId\":5}"))
                .andExpect(jsonPath("$.data.status").value("Assigned"));
        mockMvc.perform(as(get("/api/fm/overdue-contracts?facilityId=1"), FM))
                .andExpect(jsonPath("$.data[0].chargedDays").value(2));

        login(FS, "FS", 5L);
        when(tenancy.staffReturns()).thenReturn(List.of(returnView("Assigned")));
        when(checkout.forStaff(3L)).thenReturn(checkoutView());
        when(checkout.inspect(eq(3L), any())).thenReturn(checkoutView());
        when(checkout.confirm(3L)).thenReturn(checkoutView());
        mockMvc.perform(as(get("/api/staff/return-requests"), FS)).andExpect(status().isOk());
        mockMvc.perform(as(get("/api/staff/return-requests/3/checkout"), FS)).andExpect(status().isOk());
        mockMvc.perform(json(put("/api/staff/return-requests/3/checkout"), FS,
                "{\"unitEmptied\":true,\"returnedKeys\":2}")).andExpect(status().isOk());
        mockMvc.perform(json(put("/api/staff/return-requests/3/checkout"), FS, "{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(as(post("/api/staff/return-requests/3/checkout/confirm"), FS)).andExpect(status().isOk());
    }

    private static RentalView rental() {
        return new RentalView(7L, "HD-1", 4L, 1L, "A-01", 1L, "Active", new BigDecimal("100000"),
                new BigDecimal("500000"), 3, LocalDate.now(), LocalDate.now().plusMonths(3), 90, false, List.of(),
                List.of(), null, true, true);
    }

    private static ReturnRequestView returnView(String status) {
        return new ReturnRequestView(3L, 7L, "A-01", 1L, LocalDate.now(), null, status, null, null, null, null,
                Instant.now());
    }

    private static CheckoutView checkoutView() {
        return new CheckoutView(returnView("Assigned"), Instant.now(), "IN_PROGRESS", true, false, BigDecimal.ZERO,
                null, 2, List.of(), null, Instant.now(), new BigDecimal("500000"), new BigDecimal("100000"),
                new BigDecimal("200000"), 2, BigDecimal.ZERO, null, null, null, null);
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
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("self-storage")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(60))
                .claim("email", email)
                .build();
        return jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }
}
