package vn.lemar.selfstorage.booking.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import vn.lemar.selfstorage.booking.application.StaffAppointmentActionService;
import vn.lemar.selfstorage.booking.application.StaffAppointmentQueryService;
import vn.lemar.selfstorage.booking.application.dto.StaffAppointmentResponse;
import vn.lemar.selfstorage.booking.application.exception.AppointmentForbiddenException;
import vn.lemar.selfstorage.booking.application.exception.AppointmentNotFoundException;
import vn.lemar.selfstorage.identity.application.AccountJwtAuthenticationConverter;
import vn.lemar.selfstorage.identity.application.AccountQueryService;
import vn.lemar.selfstorage.identity.config.JwtConfiguration;
import vn.lemar.selfstorage.identity.domain.Account;
import vn.lemar.selfstorage.identity.domain.AccountStatus;
import vn.lemar.selfstorage.identity.domain.Role;
import vn.lemar.selfstorage.identity.repository.AccountRepository;

/** HTTP contract của lịch làm việc FS: role {@code /api/staff/**} + envelope. */
@WebMvcTest(controllers = StaffAppointmentController.class)
@Import({SecurityConfig.class, JwtConfiguration.class, AccountJwtAuthenticationConverter.class,
        BookingExceptionHandler.class})
@TestPropertySource(properties = "security.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=")
class StaffAppointmentControllerTest {

    private static final String FS = "fs@example.com";
    private static final String FM = "fm@example.com";
    private static final String CUSTOMER = "customer@example.com";
    private static final LocalDate DAY = LocalDate.of(2026, 11, 4);

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtEncoder jwtEncoder;

    @MockBean
    private StaffAppointmentQueryService queryService;
    @MockBean
    private StaffAppointmentActionService actionService;
    @MockBean
    private AccountQueryService accountQueryService;
    @MockBean
    private AccountRepository accountRepository;

    @Test
    void fsSeesOwnSchedule() throws Exception {
        authenticate(FS, "FS");
        when(queryService.listForCurrentStaff(DAY)).thenReturn(List.of(appointment()));

        mockMvc.perform(as(get("/api/staff/appointments?date=2026-11-04"), FS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(StaffAppointmentController.MESSAGE))
                .andExpect(jsonPath("$.data[0].customer.name").value("Khách Demo"))
                .andExpect(jsonPath("$.data[0].facility.name").value("Self Storage Quận 7"))
                .andExpect(jsonPath("$.data[0].arrivedAt").doesNotExist());
        verify(queryService).listForCurrentStaff(DAY);
    }

    @Test
    void missingDateUsesToday() throws Exception {
        authenticate(FS, "FS");
        when(queryService.listForCurrentStaff(null)).thenReturn(List.of());

        mockMvc.perform(as(get("/api/staff/appointments"), FS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
        verify(queryService).listForCurrentStaff(null);
    }

    @Test
    void fmCannotUseStaffRoutes() throws Exception {
        authenticate(FM, "FM");

        mockMvc.perform(as(get("/api/staff/appointments"), FM)).andExpect(status().isForbidden());
    }

    @Test
    void customerCannotUseStaffRoutes() throws Exception {
        authenticate(CUSTOMER, "CUSTOMER");

        mockMvc.perform(as(get("/api/staff/appointments"), CUSTOMER))
                .andExpect(status().isForbidden());
    }

    @Test
    void missingTokenIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/staff/appointments")).andExpect(status().isUnauthorized());
    }

    @Test
    void fsArrivesAppointment() throws Exception {
        authenticate(FS, "FS");
        when(actionService.arrive(1L))
                .thenReturn(new StaffAppointmentActionService.ActionResult(appointment("Done"), false, false));

        mockMvc.perform(as(post("/api/staff/appointments/1/arrive"), FS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(StaffAppointmentController.ARRIVE_MESSAGE))
                .andExpect(jsonPath("$.data.status").value("Done"))
                .andExpect(jsonPath("$.data.arrivedAt").exists());
    }

    @Test
    void fsCancelsAppointment() throws Exception {
        authenticate(FS, "FS");
        when(actionService.cancel(1L, "Khách yêu cầu huỷ"))
                .thenReturn(new StaffAppointmentActionService.ActionResult(appointment("Canceled"), false, false));

        mockMvc.perform(as(post("/api/staff/appointments/1/cancel")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Khách yêu cầu huỷ\"}"), FS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(StaffAppointmentController.CANCEL_MESSAGE))
                .andExpect(jsonPath("$.data.status").value("Canceled"));
    }

    @Test
    void cancelRequiresReason() throws Exception {
        authenticate(FS, "FS");

        mockMvc.perform(as(post("/api/staff/appointments/1/cancel")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"), FS))
                .andExpect(status().isBadRequest());
    }

    @Test
    void actionErrorsMapToStatusCodes() throws Exception {
        authenticate(FS, "FS");
        when(actionService.arrive(9L)).thenThrow(new AppointmentNotFoundException());
        when(actionService.arrive(8L))
                .thenThrow(new AppointmentForbiddenException("Bạn không phải FS phụ trách lịch này"));
        when(actionService.arrive(7L))
                .thenReturn(new StaffAppointmentActionService.ActionResult(appointment("Pending"), true, false));
        when(actionService.cancel(6L, "x"))
                .thenReturn(new StaffAppointmentActionService.ActionResult(appointment("Pending"), true, false));

        mockMvc.perform(as(post("/api/staff/appointments/9/arrive"), FS)).andExpect(status().isNotFound());
        mockMvc.perform(as(post("/api/staff/appointments/8/arrive"), FS)).andExpect(status().isForbidden());
        mockMvc.perform(as(post("/api/staff/appointments/7/arrive"), FS)).andExpect(status().isConflict());
        mockMvc.perform(as(post("/api/staff/appointments/6/cancel")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}"), FS))
                .andExpect(status().isConflict());
    }

    @Test
    void fmCannotUseStaffActions() throws Exception {
        authenticate(FM, "FM");

        mockMvc.perform(as(post("/api/staff/appointments/1/arrive"), FM)).andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------- helpers

    private static StaffAppointmentResponse appointment() {
        return appointment("Pending");
    }

    private static StaffAppointmentResponse appointment(String status) {
        Instant arrivedAt = "Done".equals(status) ? Instant.parse("2026-11-04T02:00:00Z") : null;
        return new StaffAppointmentResponse(1L, "CHECKIN", status, DAY,
                Instant.parse("2026-11-04T01:00:00Z"), Instant.parse("2026-11-04T03:00:00Z"), arrivedAt,
                5L, "A-01", new StaffAppointmentResponse.Facility(1L, "Self Storage Quận 7"),
                new StaffAppointmentResponse.Customer("Khách Demo", "customer1@lemar.vn", "0900000000"),
                false, "Canceled".equals(status) ? "Khách yêu cầu huỷ" : null);
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
