package vn.lemar.selfstorage.backoffice.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
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
import vn.lemar.selfstorage.backoffice.application.AccountRequestService;
import vn.lemar.selfstorage.backoffice.application.AccountRequestService.AccountRequestRow;
import vn.lemar.selfstorage.identity.application.AccountJwtAuthenticationConverter;
import vn.lemar.selfstorage.identity.application.CurrentAccountProvider;
import vn.lemar.selfstorage.identity.config.JwtConfiguration;
import vn.lemar.selfstorage.identity.domain.Account;
import vn.lemar.selfstorage.identity.domain.AccountStatus;
import vn.lemar.selfstorage.identity.domain.Role;
import vn.lemar.selfstorage.identity.repository.AccountRepository;

/** HTTP contract yêu cầu tạo tài khoản: BOM gửi / huỷ, ADMIN duyệt / từ chối; role theo namespace. */
@WebMvcTest(controllers = AccountRequestController.class)
@Import({SecurityConfig.class, JwtConfiguration.class, AccountJwtAuthenticationConverter.class,
    BackofficeExceptionHandler.class})
@TestPropertySource(properties = "security.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=")
class AccountRequestControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtEncoder jwtEncoder;

    @MockBean
    private AccountRequestService requests;
    @MockBean
    private CurrentAccountProvider currentAccountProvider;
    @MockBean
    private AccountRepository accountRepository;

    @Test
    void bomSubmitsListsAndCancels() throws Exception {
        login("bom@x.vn", "BOM", 2L);
        when(requests.submit("FS", "moi@x.vn", "Người Mới", null, List.of(1L), null, null))
                .thenReturn(row("Pending"));
        when(requests.submit(eq("FM"), eq("fm@x.vn"), eq("FM"), isNull(), eq(List.of(1L)), isNull(), isNull()))
                .thenThrow(new IllegalStateException("Cơ sở Q7 đã có FM"));
        when(requests.list(null)).thenReturn(List.of(row("Pending")));
        when(requests.cancel(5L)).thenReturn(row("Canceled"));

        mockMvc.perform(json(post("/api/bom/account-requests"), "bom@x.vn",
                        "{\"role\":\"FS\",\"email\":\"moi@x.vn\",\"name\":\"Người Mới\",\"facilityIds\":[1]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("Pending"));
        mockMvc.perform(json(post("/api/bom/account-requests"), "bom@x.vn",
                        "{\"role\":\"FM\",\"email\":\"fm@x.vn\",\"name\":\"FM\",\"facilityIds\":[1]}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("Cơ sở Q7 đã có FM"));
        mockMvc.perform(json(post("/api/bom/account-requests"), "bom@x.vn", "{\"role\":\"FS\",\"email\":\"\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(as(get("/api/bom/account-requests"), "bom@x.vn"))
                .andExpect(jsonPath("$.data[0].facilityCodes[0]").value("Q7"));
        mockMvc.perform(as(post("/api/bom/account-requests/5/cancel"), "bom@x.vn"))
                .andExpect(jsonPath("$.data.status").value("Canceled"));
        mockMvc.perform(as(get("/api/admin/account-requests"), "bom@x.vn")).andExpect(status().isForbidden());
    }

    @Test
    void adminApprovesAndRejects() throws Exception {
        login("admin@x.vn", "ADMIN", 1L);
        when(requests.list("Pending")).thenReturn(List.of(row("Pending")));
        when(requests.approve(5L, "Matkhau@123")).thenReturn(row("Approved"));
        when(requests.reject(6L, "Đủ người")).thenReturn(row("Rejected"));
        when(requests.reject(7L, "x")).thenThrow(new IllegalArgumentException("Không tìm thấy yêu cầu"));

        mockMvc.perform(as(get("/api/admin/account-requests?status=Pending"), "admin@x.vn"))
                .andExpect(status().isOk());
        mockMvc.perform(json(post("/api/admin/account-requests/5/approve"), "admin@x.vn",
                "{\"password\":\"Matkhau@123\"}")).andExpect(jsonPath("$.data.status").value("Approved"));
        mockMvc.perform(json(post("/api/admin/account-requests/5/approve"), "admin@x.vn", "{\"password\":\"ngan\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(json(post("/api/admin/account-requests/6/reject"), "admin@x.vn", "{\"reason\":\"Đủ người\"}"))
                .andExpect(jsonPath("$.data.status").value("Rejected"));
        mockMvc.perform(json(post("/api/admin/account-requests/7/reject"), "admin@x.vn", "{\"reason\":\"x\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(as(get("/api/bom/account-requests"), "admin@x.vn")).andExpect(status().isForbidden());
    }

    private static AccountRequestRow row(String status) {
        return new AccountRequestRow(5L, 2L, "bom@x.vn", "BOM", "FS", "moi@x.vn", "Người Mới", null, List.of(1L),
                List.of("Q7"), null, status, null, null, null, null, null, Instant.now(), null, null, null);
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
