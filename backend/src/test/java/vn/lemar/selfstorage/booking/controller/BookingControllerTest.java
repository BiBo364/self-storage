package vn.lemar.selfstorage.booking.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import vn.lemar.selfstorage.booking.application.AgreeResult;
import vn.lemar.selfstorage.booking.application.InvoiceView;
import vn.lemar.selfstorage.booking.application.ProposalQueryService;
import vn.lemar.selfstorage.booking.application.ProposalService;
import vn.lemar.selfstorage.booking.application.ProposalView;
import vn.lemar.selfstorage.booking.application.RejectResult;
import vn.lemar.selfstorage.booking.application.dto.CustomerProposalResponse;
import vn.lemar.selfstorage.booking.application.dto.FmRentalOrderPageResponse;
import vn.lemar.selfstorage.booking.application.dto.FmRentalOrderResponse;
import vn.lemar.selfstorage.booking.application.exception.ProposalConflictException;
import vn.lemar.selfstorage.booking.application.exception.ProposalForbiddenException;
import vn.lemar.selfstorage.booking.application.exception.ProposalNotFoundException;
import vn.lemar.selfstorage.booking.application.exception.ProposalValidationException;
import vn.lemar.selfstorage.booking.application.exception.RentalOrderNotFoundException;
import vn.lemar.selfstorage.booking.application.exception.RentalOrderValidationException;
import vn.lemar.selfstorage.booking.application.exception.StorageUnitNotFoundException;
import vn.lemar.selfstorage.identity.application.AccountJwtAuthenticationConverter;
import vn.lemar.selfstorage.identity.application.AccountQueryService;
import vn.lemar.selfstorage.identity.application.exception.ForbiddenException;
import vn.lemar.selfstorage.identity.config.JwtConfiguration;
import vn.lemar.selfstorage.identity.domain.Account;
import vn.lemar.selfstorage.identity.domain.AccountStatus;
import vn.lemar.selfstorage.identity.domain.Role;
import vn.lemar.selfstorage.identity.repository.AccountRepository;

/** HTTP contract của 5 route Flow 1.3: guard role theo namespace + đổi exception thành status code. */
@WebMvcTest(controllers = {ProposalController.class, FmRentalOrderController.class,
    RentalOrderProposalController.class})
@Import({SecurityConfig.class, JwtConfiguration.class, AccountJwtAuthenticationConverter.class})
@TestPropertySource(properties = "security.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=")
class BookingControllerTest {

    private static final String CUSTOMER = "customer@example.com";
    private static final String FM = "fm@example.com";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtEncoder jwtEncoder;

    @MockBean
    private ProposalService proposalService;
    @MockBean
    private ProposalQueryService proposalQueryService;
    @MockBean
    private AccountQueryService accountQueryService;
    @MockBean
    private AccountRepository accountRepository;

    // ---------------------------------------------------------------- khách

    @Test
    void customerRoutesRejectAnonymous() throws Exception {
        mockMvc.perform(get("/api/proposals")).andExpect(status().isUnauthorized());
    }

    @Test
    void proposalRoutesAreOwnershipNotRoleBound() throws Exception {
        // Contract #113: /api/proposals nằm trong /api/** — không chặn theo role; service lọc theo customer_id.
        authenticate(FM, "FM");
        when(accountQueryService.requireIdByEmail(FM)).thenReturn(3L);
        when(proposalQueryService.listForCustomer(3L, null)).thenReturn(List.of());

        mockMvc.perform(as(get("/api/proposals"), FM))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void listReturnsProposalsOfCurrentCustomer() throws Exception {
        authenticate(CUSTOMER, "CUSTOMER");
        when(accountQueryService.requireIdByEmail(CUSTOMER)).thenReturn(6L);
        when(proposalQueryService.listForCustomer(6L, "Pending")).thenReturn(List.of(new CustomerProposalResponse(
                1L, 1L, "Pending", null, Instant.parse("2026-10-13T08:00:00Z"), 1L, "A-01", null, "Small", 1L,
                "Self Storage Quận 7", "123 Nguyễn Văn Linh", new BigDecimal("500000.00"), 1, 3)));

        mockMvc.perform(as(get("/api/proposals?status=Pending"), CUSTOMER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(ProposalController.LIST_MESSAGE))
                .andExpect(jsonPath("$.data[0].unitCode").value("A-01"))
                .andExpect(jsonPath("$.data[0].rejectionCount").value(1))
                .andExpect(jsonPath("$.data[0].maxRejectionCount").value(3));
    }

    @Test
    void listWithUnknownStatusIsBadRequest() throws Exception {
        authenticate(CUSTOMER, "CUSTOMER");
        when(accountQueryService.requireIdByEmail(CUSTOMER)).thenReturn(6L);
        when(proposalQueryService.listForCustomer(6L, "Expired"))
                .thenThrow(new ProposalValidationException("Trạng thái không hợp lệ: Expired"));

        mockMvc.perform(as(get("/api/proposals?status=Expired"), CUSTOMER))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Trạng thái không hợp lệ: Expired"));
    }

    @Test
    void agreeReturnsProposalAndDepositInvoice() throws Exception {
        authenticate(CUSTOMER, "CUSTOMER");
        when(accountQueryService.requireIdByEmail(CUSTOMER)).thenReturn(6L);
        when(proposalService.agree(1L, 6L)).thenReturn(new AgreeResult(AgreeResult.Outcome.AGREED,
                proposal("Agreed"), new InvoiceView(5L, 1L, "INV-DEP-Q7-261013-ABCDEF", "Đặt cọc khoang chứa A-01",
                "Thanh toán đặt cọc khoang chứa A-01 để đảm bảo giữ chỗ.", "Deposit", "Unpaid",
                new BigDecimal("500000.00"), Instant.parse("2026-10-16T08:00:00Z"))));

        mockMvc.perform(as(post("/api/proposals/1/agree"), CUSTOMER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(ProposalController.AGREE_MESSAGE))
                .andExpect(jsonPath("$.data.proposal.status").value("Agreed"))
                .andExpect(jsonPath("$.data.invoice.code").value("INV-DEP-Q7-261013-ABCDEF"))
                .andExpect(jsonPath("$.data.invoice.amount").value(500000.00));
    }

    @Test
    void agreeOnUnavailableUnitIsConflictWithSpecMessage() throws Exception {
        authenticate(CUSTOMER, "CUSTOMER");
        when(accountQueryService.requireIdByEmail(CUSTOMER)).thenReturn(6L);
        when(proposalService.agree(1L, 6L))
                .thenReturn(new AgreeResult(AgreeResult.Outcome.UNIT_UNAVAILABLE, proposal("Canceled"), null));

        mockMvc.perform(as(post("/api/proposals/1/agree"), CUSTOMER))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Khoang đã có người đặt cọc / không khả dụng"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @Test
    void agreeMapsBusinessErrorsToStatusCodes() throws Exception {
        authenticate(CUSTOMER, "CUSTOMER");
        when(accountQueryService.requireIdByEmail(CUSTOMER)).thenReturn(6L);
        when(proposalService.agree(2L, 6L)).thenThrow(new ProposalForbiddenException());
        when(proposalService.agree(3L, 6L)).thenThrow(new ProposalNotFoundException());
        when(proposalService.agree(4L, 6L)).thenThrow(new ProposalConflictException("Đề xuất này đã được xử lý"));

        mockMvc.perform(as(post("/api/proposals/2/agree"), CUSTOMER)).andExpect(status().isForbidden());
        mockMvc.perform(as(post("/api/proposals/3/agree"), CUSTOMER)).andExpect(status().isNotFound());
        mockMvc.perform(as(post("/api/proposals/4/agree"), CUSTOMER))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Đề xuất này đã được xử lý"));
    }

    @Test
    void rejectWithoutBodyIsBadRequest() throws Exception {
        authenticate(CUSTOMER, "CUSTOMER");
        when(accountQueryService.requireIdByEmail(CUSTOMER)).thenReturn(6L);
        when(proposalService.reject(1L, 6L, null))
                .thenThrow(new ProposalValidationException("Vui lòng nhập lý do từ chối"));

        mockMvc.perform(as(post("/api/proposals/1/reject"), CUSTOMER))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Vui lòng nhập lý do từ chối"));
    }

    @Test
    void rejectReportsWhenTheOrderGotCanceled() throws Exception {
        authenticate(CUSTOMER, "CUSTOMER");
        when(accountQueryService.requireIdByEmail(CUSTOMER)).thenReturn(6L);
        when(proposalService.reject(1L, 6L, "Không hợp")).thenReturn(new RejectResult(proposal("Rejected"), true));
        when(proposalService.reject(2L, 6L, "Không hợp")).thenReturn(new RejectResult(proposal("Rejected"), false));

        mockMvc.perform(as(post("/api/proposals/1/reject"), CUSTOMER).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"Không hợp\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(ProposalController.REJECT_CANCELED_MESSAGE))
                .andExpect(jsonPath("$.data.orderCanceled").value(true));
        mockMvc.perform(as(post("/api/proposals/2/reject"), CUSTOMER).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"Không hợp\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(ProposalController.REJECT_MESSAGE));
    }

    // ---------------------------------------------------------------- FM

    @Test
    void fmRoutesRejectCustomers() throws Exception {
        authenticate(CUSTOMER, "CUSTOMER");

        mockMvc.perform(as(get("/api/fm/rental-orders/1"), CUSTOMER)).andExpect(status().isForbidden());
    }

    @Test
    void listReturnsOrdersForFacilityManager() throws Exception {
        authenticate(FM, "FM");
        when(proposalQueryService.ordersForFacilityManager(1L, null, 0, 20))
                .thenReturn(new FmRentalOrderPageResponse(List.of(), 0, 20, 0));

        mockMvc.perform(as(get("/api/fm/rental-orders?facilityId=1"), FM))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(FmRentalOrderController.LIST_MESSAGE))
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    void listMapsInvalidFilterToBadRequest() throws Exception {
        authenticate(FM, "FM");
        when(proposalQueryService.ordersForFacilityManager(1L, "Bogus", 0, 20))
                .thenThrow(new RentalOrderValidationException("Trạng thái đơn không hợp lệ: Bogus"));

        mockMvc.perform(as(get("/api/fm/rental-orders?facilityId=1&status=Bogus"), FM))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Trạng thái đơn không hợp lệ: Bogus"));
    }

    @Test
    void detailReturnsOrderForFacilityManager() throws Exception {
        authenticate(FM, "FM");
        when(proposalQueryService.orderForFacilityManager(1L)).thenReturn(new FmRentalOrderResponse(1L, "Pending",
                null, null, null, 1L, new FmRentalOrderResponse.Customer(6L, "Khách", CUSTOMER, "0900000000"),
                new FmRentalOrderResponse.Requested(1L, "Small", LocalDate.of(2026, 10, 20), 3), 1, 3,
                List.of(new FmRentalOrderResponse.Proposal(1L, 2L, "A-01", "Rejected", "Không hợp", Instant.now())),
                List.of(new FmRentalOrderResponse.AvailableUnit(2L, "A-01", null, new BigDecimal("500000.00"), true))));

        mockMvc.perform(as(get("/api/fm/rental-orders/1"), FM))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(FmRentalOrderController.DETAIL_MESSAGE))
                .andExpect(jsonPath("$.data.proposals[0].note").value("Không hợp"))
                .andExpect(jsonPath("$.data.availableUnits[0].previouslyRejected").value(true));
    }

    @Test
    void detailMapsMissingOrderAndOtherFacility() throws Exception {
        authenticate(FM, "FM");
        when(proposalQueryService.orderForFacilityManager(9L)).thenThrow(new RentalOrderNotFoundException());
        when(proposalQueryService.orderForFacilityManager(8L))
                .thenThrow(new ForbiddenException("Bạn không được gán vào cơ sở này"));

        mockMvc.perform(as(get("/api/fm/rental-orders/9"), FM)).andExpect(status().isNotFound());
        mockMvc.perform(as(get("/api/fm/rental-orders/8"), FM)).andExpect(status().isForbidden());
    }

    @Test
    void reproposeRequiresUnitId() throws Exception {
        authenticate(FM, "FM");

        mockMvc.perform(as(post("/api/rental-orders/1/proposals"), FM).contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void reproposeReturnsNewPendingProposal() throws Exception {
        authenticate(FM, "FM");
        when(accountQueryService.requireIdByEmail(FM)).thenReturn(3L);
        when(proposalService.repropose(1L, 2L, null, 3L)).thenReturn(proposal("Pending"));

        mockMvc.perform(as(post("/api/rental-orders/1/proposals"), FM).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"unitId\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(RentalOrderProposalController.REPROPOSE_MESSAGE))
                .andExpect(jsonPath("$.data.status").value("Pending"));
    }

    @Test
    void reproposeMapsUnknownUnitToNotFound() throws Exception {
        authenticate(FM, "FM");
        when(accountQueryService.requireIdByEmail(FM)).thenReturn(3L);
        when(proposalService.repropose(1L, 99L, null, 3L)).thenThrow(new StorageUnitNotFoundException());
        when(proposalService.repropose(1L, 98L, null, 3L))
                .thenThrow(new ProposalConflictException("Khoang C-01 không còn trống"));

        mockMvc.perform(as(post("/api/rental-orders/1/proposals"), FM).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"unitId\":99}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(as(post("/api/rental-orders/1/proposals"), FM).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"unitId\":98}"))
                .andExpect(status().isConflict());
    }

    // ---------------------------------------------------------------- helpers

    private static ProposalView proposal(String status) {
        return new ProposalView(1L, 1L, 6L, 1L, status, null, Instant.parse("2026-10-13T08:00:00Z"));
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String email) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(email));
    }

    private void authenticate(String email, String roleName) {
        Role role = mock(Role.class);
        when(role.getName()).thenReturn(roleName);
        Account account = mock(Account.class);
        when(account.getEmail()).thenReturn(email);
        when(account.getRole()).thenReturn(role);
        when(account.getStatus()).thenReturn(AccountStatus.ACTIVE);
        when(account.isLoginAllowed()).thenReturn(true);
        when(accountRepository.findWithRoleByEmail(email)).thenReturn(Optional.of(account));
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
