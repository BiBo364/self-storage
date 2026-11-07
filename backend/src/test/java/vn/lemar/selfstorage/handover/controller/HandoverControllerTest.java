package vn.lemar.selfstorage.handover.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
import vn.lemar.selfstorage.booking.application.HandoverOrderService.ContractInvoice;
import vn.lemar.selfstorage.handover.application.ContractDocumentService;
import vn.lemar.selfstorage.handover.application.HandoverQueryService;
import vn.lemar.selfstorage.handover.application.HandoverWorkflowService;
import vn.lemar.selfstorage.handover.application.HandoverWorkflowService.ConfirmOutcome;
import vn.lemar.selfstorage.handover.application.HandoverWorkflowService.RejectOutcome;
import vn.lemar.selfstorage.handover.application.dto.HandoverView;
import vn.lemar.selfstorage.handover.application.exception.HandoverConflictException;
import vn.lemar.selfstorage.handover.application.exception.HandoverForbiddenException;
import vn.lemar.selfstorage.handover.application.exception.HandoverNotFoundException;
import vn.lemar.selfstorage.handover.application.exception.HandoverValidationException;
import vn.lemar.selfstorage.handover.domain.AccessChoice;
import vn.lemar.selfstorage.handover.repository.HandoverReadRepository;
import vn.lemar.selfstorage.handover.repository.HandoverReadRepository.OverrideRequestRow;
import vn.lemar.selfstorage.identity.application.AccountJwtAuthenticationConverter;
import vn.lemar.selfstorage.identity.application.AccountQueryService;
import vn.lemar.selfstorage.identity.application.CurrentAccountProvider;
import vn.lemar.selfstorage.identity.config.JwtConfiguration;
import vn.lemar.selfstorage.identity.domain.Account;
import vn.lemar.selfstorage.identity.domain.AccountStatus;
import vn.lemar.selfstorage.identity.domain.Role;
import vn.lemar.selfstorage.identity.repository.AccountRepository;

/** HTTP contract Flow 2.2–2.5: FS (`/api/staff`), khách (`/api/customer`), hợp đồng + FM duyệt mốc tính tiền. */
@WebMvcTest(controllers = {StaffHandoverController.class, CustomerHandoverController.class,
    ContractController.class})
@Import({SecurityConfig.class, JwtConfiguration.class, AccountJwtAuthenticationConverter.class,
    HandoverExceptionHandler.class})
@TestPropertySource(properties = "security.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=")
class HandoverControllerTest {

    private static final String CUSTOMER = "customer@example.com";
    private static final String FS = "fs@example.com";
    private static final String FM = "fm@example.com";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtEncoder jwtEncoder;

    @MockBean
    private HandoverQueryService queryService;
    @MockBean
    private HandoverWorkflowService workflow;
    @MockBean
    private ContractDocumentService documentService;
    @MockBean
    private HandoverReadRepository readRepository;
    @MockBean
    private CurrentAccountProvider currentAccountProvider;
    @MockBean
    private AccountQueryService accountQueryService;
    @MockBean
    private AccountRepository accountRepository;

    // ------------------------------------------------------------------ FS

    @Test
    void staffQueueAndDetail() throws Exception {
        login(FS, "FS", 3L);
        when(queryService.staffQueue()).thenReturn(List.of(view(1L, "VERIFY_IDENTITY")));
        when(queryService.forStaff(1L)).thenReturn(view(1L, "VERIFY_IDENTITY"));

        mockMvc.perform(as(get("/api/staff/handovers"), FS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].step").value("VERIFY_IDENTITY"));
        mockMvc.perform(as(get("/api/staff/handovers/1"), FS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(1));
    }

    @Test
    void staffActionsReturnUpdatedView() throws Exception {
        login(FS, "FS", 3L);
        when(queryService.view(1L)).thenReturn(view(1L, "INSPECTION"));

        mockMvc.perform(json(post("/api/staff/handovers/1/identity"), FS, "{\"verified\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.step").value("INSPECTION"));
        verify(workflow).verifyIdentity(1L, true, null);

        mockMvc.perform(json(put("/api/staff/handovers/1/inspection"), FS,
                        "{\"notes\":\"Sạch\",\"photos\":[\"https://img/1.jpg\"]}"))
                .andExpect(status().isOk());
        verify(workflow).recordInspection(1L, "Sạch", List.of("https://img/1.jpg"));

        mockMvc.perform(json(post("/api/staff/handovers/1/keys"), FS, "{\"quantity\":2}"))
                .andExpect(status().isOk());
        verify(workflow).handOverKeys(1L, 2);

        mockMvc.perform(as(post("/api/staff/handovers/1/not-signed"), FS))
                .andExpect(status().isOk());
        verify(workflow).customerDidNotSign(1L);
    }

    @Test
    void staffContractActions() throws Exception {
        login(FS, "FS", 3L);
        when(workflow.signContract(7L, "https://img/s.pdf")).thenReturn(new ContractInvoice(9L, "INV-RNT-Q7-1",
                "Unpaid", new BigDecimal("500000"), Instant.parse("2026-11-07T00:00:00Z")));

        mockMvc.perform(json(post("/api/staff/contracts/7/sign"), FS, "{\"pdfUrl\":\"https://img/s.pdf\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.code").value("INV-RNT-Q7-1"));
        mockMvc.perform(json(post("/api/staff/contracts/7/start-date-override"), FS,
                        "{\"requestedDate\":\"2026-11-20\",\"reason\":\"Khách dời ngày\"}"))
                .andExpect(status().isOk());
        verify(workflow).requestStartDateOverride(7L, LocalDate.parse("2026-11-20"), "Khách dời ngày");

        mockMvc.perform(json(post("/api/staff/contracts/7/start-date-override"), FS, "{\"requestedDate\":null}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(json(post("/api/staff/contracts/7/sign"), FS, "{\"pdfUrl\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void domainErrorsMapToHttpStatus() throws Exception {
        login(FS, "FS", 3L);
        doThrow(new HandoverConflictException("Phiên bàn giao đã đóng")).when(workflow).customerDidNotSign(1L);
        doThrow(new HandoverForbiddenException("Bạn không phải FS phụ trách lịch này")).when(workflow)
                .customerDidNotSign(2L);
        doThrow(new HandoverNotFoundException("Không tìm thấy phiên bàn giao")).when(workflow).customerDidNotSign(3L);
        doThrow(new HandoverValidationException("Số chìa giao phải từ 1 đến 10")).when(workflow)
                .handOverKeys(1L, 99);

        mockMvc.perform(as(post("/api/staff/handovers/1/not-signed"), FS))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Phiên bàn giao đã đóng"));
        mockMvc.perform(as(post("/api/staff/handovers/2/not-signed"), FS)).andExpect(status().isForbidden());
        mockMvc.perform(as(post("/api/staff/handovers/3/not-signed"), FS)).andExpect(status().isNotFound());
        mockMvc.perform(json(post("/api/staff/handovers/1/keys"), FS, "{\"quantity\":99}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void customerCannotCallStaffRoutes() throws Exception {
        login(CUSTOMER, "CUSTOMER", 6L);
        mockMvc.perform(as(get("/api/staff/handovers"), CUSTOMER)).andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ Customer

    @Test
    void customerReadsAndAgrees() throws Exception {
        login(CUSTOMER, "CUSTOMER", 6L);
        when(queryService.forCustomer(6L)).thenReturn(List.of(view(1L, "INSPECTION")));
        when(queryService.forCustomer(1L, 6L)).thenReturn(view(1L, "SIGN_CONTRACT"));

        mockMvc.perform(as(get("/api/customer/handovers"), CUSTOMER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(1));
        mockMvc.perform(as(get("/api/customer/handovers/1"), CUSTOMER))
                .andExpect(status().isOk());
        mockMvc.perform(as(post("/api/customer/handovers/1/agree"), CUSTOMER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.step").value("SIGN_CONTRACT"));
        verify(workflow).agreeInspection(eq(1L), eq(6L), isNull());
        mockMvc.perform(json(post("/api/customer/handovers/1/agree"), CUSTOMER, "{\"note\":\"Cửa kẹt\"}"))
                .andExpect(status().isOk());
        verify(workflow).agreeInspection(1L, 6L, "Cửa kẹt");
    }

    @Test
    void customerRejectAndConfirm() throws Exception {
        login(CUSTOMER, "CUSTOMER", 6L);
        when(workflow.reject(1L, 6L, "Ẩm", false)).thenReturn(new RejectOutcome(true, false, "Cần xác nhận"));
        when(workflow.confirm(1L, 6L, AccessChoice.ACCESS_CODE))
                .thenReturn(new ConfirmOutcome(true, "123456", "Bàn giao hoàn tất"));
        when(workflow.rotateAccessCode(7L, 6L)).thenReturn("654321");

        mockMvc.perform(json(post("/api/customer/handovers/1/reject"), CUSTOMER, "{\"reason\":\"Ẩm\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.requiresConfirmation").value(true));
        mockMvc.perform(json(post("/api/customer/handovers/1/reject"), CUSTOMER, "{\"reason\":\"\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(json(post("/api/customer/handovers/1/confirm"), CUSTOMER, "{\"accessType\":\"AccessCode\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessCode").value("123456"));
        mockMvc.perform(json(post("/api/customer/handovers/1/confirm"), CUSTOMER, "{\"accessType\":\"Magic\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(as(post("/api/customer/contracts/7/access-code"), CUSTOMER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessCode").value("654321"));
    }

    // ------------------------------------------------------------------ Contract + FM

    @Test
    void contractDocumentIsHtml() throws Exception {
        login(CUSTOMER, "CUSTOMER", 6L);
        when(documentService.render(7L)).thenReturn("<html>HD-Q7</html>");

        mockMvc.perform(as(get("/api/contracts/7/document"), CUSTOMER))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string("<html>HD-Q7</html>"));
    }

    @Test
    void managerReviewsStartDateOverrides() throws Exception {
        login(FM, "FM", 2L);
        when(readRepository.findPendingOverridesForManager(2L)).thenReturn(List.of(new OverrideRequestRow(7L,
                "HD-Q7-1", 4L, "A-01", "Khách", LocalDate.now(), LocalDate.now().plusDays(3), "Dời", "Q7")));

        mockMvc.perform(as(get("/api/fm/contracts/start-date-overrides"), FM))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].contractCode").value("HD-Q7-1"));
        mockMvc.perform(as(post("/api/fm/contracts/7/start-date-override/approve"), FM))
                .andExpect(status().isOk());
        verify(workflow).reviewStartDateOverride(eq(7L), eq(true), isNull());
        mockMvc.perform(json(post("/api/fm/contracts/7/start-date-override/reject"), FM, "{\"reason\":\"Không\"}"))
                .andExpect(status().isOk());
        verify(workflow).reviewStartDateOverride(7L, false, "Không");
    }

    // ------------------------------------------------------------------ helpers

    private static HandoverView view(Long id, String step) {
        return new HandoverView(id, 4L, 5L, "IN_PROGRESS", step, "A-01", "Small", 1L, "Q7", "Addr", 6L, "Khách",
                CUSTOMER, "0900", LocalDate.now(), Instant.now(), Instant.now(), Instant.now(), "Verified", null,
                "Pending", null, null, List.of(), null, null, null, null, null, null, null, List.of(), true, true, 0,
                2);
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
        when(accountQueryService.requireIdByEmail(any())).thenReturn(accountId);
        when(queryService.view(anyLong())).thenReturn(view(1L, "INSPECTION"));
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
