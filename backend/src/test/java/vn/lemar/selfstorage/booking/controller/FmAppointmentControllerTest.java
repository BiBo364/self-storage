package vn.lemar.selfstorage.booking.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
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
import vn.lemar.selfstorage.booking.application.AppointmentQueryService;
import vn.lemar.selfstorage.booking.application.AppointmentService;
import vn.lemar.selfstorage.booking.application.dto.FmAppointmentResponse;
import vn.lemar.selfstorage.booking.application.dto.FmFacilityStaffResponse;
import vn.lemar.selfstorage.booking.application.exception.AppointmentConflictException;
import vn.lemar.selfstorage.booking.application.exception.AppointmentNotFoundException;
import vn.lemar.selfstorage.booking.application.exception.AppointmentValidationException;
import vn.lemar.selfstorage.identity.application.AccountJwtAuthenticationConverter;
import vn.lemar.selfstorage.identity.application.AccountQueryService;
import vn.lemar.selfstorage.identity.application.exception.FacilityNotFoundException;
import vn.lemar.selfstorage.identity.application.exception.ForbiddenException;
import vn.lemar.selfstorage.identity.config.JwtConfiguration;
import vn.lemar.selfstorage.identity.domain.Account;
import vn.lemar.selfstorage.identity.domain.AccountStatus;
import vn.lemar.selfstorage.identity.domain.Role;
import vn.lemar.selfstorage.identity.repository.AccountRepository;
import vn.lemar.selfstorage.staffing.application.StaffAvailability.DayWork;
import vn.lemar.selfstorage.staffing.application.StaffAvailability.ShiftTime;

/** HTTP contract của màn lịch hẹn FM: guard role {@code /api/fm/**} + đổi exception thành status code. */
@WebMvcTest(controllers = FmAppointmentController.class)
@Import({SecurityConfig.class, JwtConfiguration.class, AccountJwtAuthenticationConverter.class,
        BookingExceptionHandler.class})
@TestPropertySource(properties = "security.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=")
class FmAppointmentControllerTest {

    private static final String FM = "fm@example.com";
    private static final String CUSTOMER = "customer@example.com";
    private static final LocalDate DAY = LocalDate.of(2026, 11, 2);

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtEncoder jwtEncoder;

    @MockBean
    private AppointmentQueryService queryService;
    @MockBean
    private AppointmentService appointmentService;
    @MockBean
    private AccountQueryService accountQueryService;
    @MockBean
    private AccountRepository accountRepository;

    @Test
    void customersCannotUseFmRoutes() throws Exception {
        authenticate(CUSTOMER, "CUSTOMER");

        mockMvc.perform(as(get("/api/fm/appointments?facilityId=1&date=2026-11-02"), CUSTOMER))
                .andExpect(status().isForbidden());
        mockMvc.perform(as(post("/api/fm/appointments/1/staff"), CUSTOMER).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"staffId\":4}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void staffCannotUseFmRoutes() throws Exception {
        authenticate("fs@example.com", "FS");

        mockMvc.perform(as(get("/api/fm/appointments?facilityId=1&date=2026-11-02"), "fs@example.com"))
                .andExpect(status().isForbidden());
        mockMvc.perform(as(post("/api/fm/appointments/1/staff"), "fs@example.com")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"staffId\":4}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void dayListReturnsAppointments() throws Exception {
        authenticate(FM, "FM");
        when(queryService.listForFacility(1L, DAY)).thenReturn(List.of(appointment(null)));

        mockMvc.perform(as(get("/api/fm/appointments?facilityId=1&date=2026-11-02"), FM))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(FmAppointmentController.LIST_MESSAGE))
                .andExpect(jsonPath("$.data[0].unitCode").value("A-01"))
                .andExpect(jsonPath("$.data[0].customerName").value("Khách Demo"))
                .andExpect(jsonPath("$.data[0].staffId").doesNotExist());
    }

    @Test
    void unknownFacilityMapsToNotFound() throws Exception {
        authenticate(FM, "FM");
        when(queryService.listForFacility(99L, DAY)).thenThrow(new FacilityNotFoundException());

        mockMvc.perform(as(get("/api/fm/appointments?facilityId=99&date=2026-11-02"), FM))
                .andExpect(status().isNotFound());
    }

    @Test
    void dayListRequiresDate() throws Exception {
        authenticate(FM, "FM");

        mockMvc.perform(as(get("/api/fm/appointments?facilityId=1"), FM))
                .andExpect(status().isBadRequest());
    }

    @Test
    void staffListReturnsFacilityStaff() throws Exception {
        authenticate(FM, "FM");
        LocalDate day = LocalDate.parse("2026-11-12");
        ShiftTime shift = new ShiftTime(LocalTime.of(7, 0), LocalTime.of(21, 0));
        when(queryService.staffOfFacility(1L, day)).thenReturn(List.of(new FmFacilityStaffResponse(4L, "FS Quận 7",
                "fs1@lemar.vn", "0901234567", Instant.parse("2026-11-01T00:00:00Z"), day, shift, List.of(shift),
                false, new DayWork(1, 0), 2)));

        mockMvc.perform(as(get("/api/fm/facilities/1/staff?date=2026-11-12"), FM))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(FmAppointmentController.STAFF_MESSAGE))
                .andExpect(jsonPath("$.data[0].name").value("FS Quận 7"))
                .andExpect(jsonPath("$.data[0].email").value("fs1@lemar.vn"))
                .andExpect(jsonPath("$.data[0].shift.startTime").value("07:00"))
                .andExpect(jsonPath("$.data[0].openWork.checkins").value(1))
                .andExpect(jsonPath("$.data[0].openSupport").value(2));
    }

    @Test
    void assignRequiresStaffId() throws Exception {
        authenticate(FM, "FM");

        mockMvc.perform(as(post("/api/fm/appointments/1/staff"), FM).contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void assignReturnsAppointmentWithStaff() throws Exception {
        authenticate(FM, "FM");
        when(accountQueryService.requireIdByEmail(FM)).thenReturn(3L);
        when(appointmentService.assignStaff(1L, 4L, 3L)).thenReturn(appointment(4L));

        mockMvc.perform(as(post("/api/fm/appointments/1/staff"), FM).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"staffId\":4}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(FmAppointmentController.ASSIGN_MESSAGE))
                .andExpect(jsonPath("$.data.staffId").value(4))
                .andExpect(jsonPath("$.data.orderStatus").value("InProgress"));
    }

    @Test
    void assignMapsBusinessErrorsToStatusCodes() throws Exception {
        authenticate(FM, "FM");
        when(accountQueryService.requireIdByEmail(FM)).thenReturn(3L);
        when(appointmentService.assignStaff(9L, 4L, 3L)).thenThrow(new AppointmentNotFoundException());
        when(appointmentService.assignStaff(8L, 4L, 3L))
                .thenThrow(new ForbiddenException("Bạn không được gán vào cơ sở này"));
        when(appointmentService.assignStaff(7L, 4L, 3L))
                .thenThrow(new AppointmentConflictException("Lịch hẹn đã có FS phụ trách"));
        when(appointmentService.assignStaff(6L, 4L, 3L))
                .thenThrow(new AppointmentValidationException("Nhân viên không phải FS của cơ sở này"));

        mockMvc.perform(assign(9L)).andExpect(status().isNotFound());
        mockMvc.perform(assign(8L)).andExpect(status().isForbidden());
        mockMvc.perform(assign(7L)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Lịch hẹn đã có FS phụ trách"));
        mockMvc.perform(assign(6L)).andExpect(status().isBadRequest());
    }

    @Test
    void reassignReturnsUpdatedAppointmentAndMapsConflicts() throws Exception {
        authenticate(FM, "FM");
        when(accountQueryService.requireIdByEmail(FM)).thenReturn(3L);
        when(appointmentService.reassignStaff(1L, 4L, 3L)).thenReturn(appointment(4L));
        when(appointmentService.reassignStaff(2L, 4L, 3L))
                .thenThrow(new AppointmentConflictException("FS này đang phụ trách lịch hẹn"));

        mockMvc.perform(as(put("/api/fm/appointments/1/staff"), FM).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"staffId\":4}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(FmAppointmentController.REASSIGN_MESSAGE))
                .andExpect(jsonPath("$.data.staffId").value(4));
        mockMvc.perform(as(put("/api/fm/appointments/2/staff"), FM).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"staffId\":4}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("FS này đang phụ trách lịch hẹn"));
        mockMvc.perform(as(put("/api/fm/appointments/1/staff"), FM).contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------- helpers

    private MockHttpServletRequestBuilder assign(Long appointmentId) {
        return as(post("/api/fm/appointments/" + appointmentId + "/staff"), FM)
                .contentType(MediaType.APPLICATION_JSON).content("{\"staffId\":4}");
    }

    private static FmAppointmentResponse appointment(Long staffId) {
        return new FmAppointmentResponse(1L, "CHECKIN", "Pending", DAY, Instant.parse("2026-11-02T01:00:00Z"),
                Instant.parse("2026-11-02T03:00:00Z"), 5L, staffId == null ? "Scheduled" : "InProgress", "A-01",
                "Khách Demo", "customer1@lemar.vn", staffId, staffId == null ? null : "fs1@lemar.vn",
                1, false, null, null);
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
