package vn.lemar.selfstorage.staffing.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import vn.lemar.selfstorage.identity.application.AccountJwtAuthenticationConverter;
import vn.lemar.selfstorage.identity.config.JwtConfiguration;
import vn.lemar.selfstorage.identity.domain.Account;
import vn.lemar.selfstorage.identity.domain.AccountStatus;
import vn.lemar.selfstorage.identity.domain.Role;
import vn.lemar.selfstorage.identity.repository.AccountRepository;
import vn.lemar.selfstorage.staffing.application.StaffLeaveService;
import vn.lemar.selfstorage.staffing.application.StaffLeaveService.LeaveView;
import vn.lemar.selfstorage.staffing.application.StaffProfileService;
import vn.lemar.selfstorage.staffing.application.StaffProfileService.ProfileSummary;
import vn.lemar.selfstorage.staffing.application.StaffProfileService.ProfileView;
import vn.lemar.selfstorage.staffing.application.StaffShiftService;
import vn.lemar.selfstorage.staffing.application.StaffShiftService.ShiftView;
import vn.lemar.selfstorage.staffing.application.StaffingException;

/** HTTP contract nhân sự: role-bound theo namespace, định dạng giờ {@code HH:mm}, lỗi nghiệp vụ → đúng mã HTTP. */
@WebMvcTest(controllers = StaffingController.class)
@Import({SecurityConfig.class, JwtConfiguration.class, AccountJwtAuthenticationConverter.class,
    StaffingExceptionHandler.class})
@TestPropertySource(properties = "security.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=")
class StaffingControllerTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 12);

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtEncoder jwtEncoder;

    @MockBean
    private StaffShiftService shifts;
    @MockBean
    private StaffLeaveService leaves;
    @MockBean
    private StaffProfileService profiles;
    @MockBean
    private AccountRepository accountRepository;

    @Test
    void managerShiftAndLeaveRoutes() throws Exception {
        login("fm@x.vn", "FM");
        when(shifts.facilityShifts(1L, DAY, null)).thenReturn(List.of(shift()));
        when(shifts.create(any())).thenReturn(shift());
        when(shifts.update(eq(7L), any())).thenThrow(StaffingException.conflict("Ca chồng giờ"));
        when(leaves.facilityLeaves(1L, "Pending")).thenReturn(List.of(leave()));
        when(leaves.approve(3L, "ok")).thenReturn(leave());
        when(leaves.reject(3L, null)).thenReturn(leave());
        when(profiles.forFacilityManager(5L)).thenReturn(summary());

        mockMvc.perform(as(get("/api/fm/staff-shifts?facilityId=1&from=2026-11-12"), "fm@x.vn"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].startTime").value("07:00"))
                .andExpect(jsonPath("$.data[0].endTime").value("21:00"));
        mockMvc.perform(json(post("/api/fm/staff-shifts"), "fm@x.vn", "{\"facilityId\":1,\"staffId\":5,"
                + "\"shiftDate\":\"2026-11-12\",\"startTime\":\"07:00\",\"endTime\":\"21:00\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(7));
        mockMvc.perform(json(post("/api/fm/staff-shifts"), "fm@x.vn", "{\"facilityId\":1}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(json(put("/api/fm/staff-shifts/7"), "fm@x.vn",
                "{\"shiftDate\":\"2026-11-12\",\"startTime\":\"08:00\",\"endTime\":\"12:00\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("Ca chồng giờ"));
        mockMvc.perform(as(delete("/api/fm/staff-shifts/7"), "fm@x.vn")).andExpect(status().isOk());
        verify(shifts).delete(7L);
        mockMvc.perform(as(get("/api/fm/staff-leaves?facilityId=1&status=Pending"), "fm@x.vn"))
                .andExpect(jsonPath("$.data[0].status").value("Pending"));
        mockMvc.perform(json(post("/api/fm/staff-leaves/3/approve"), "fm@x.vn", "{\"note\":\"ok\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(as(post("/api/fm/staff-leaves/3/reject"), "fm@x.vn")).andExpect(status().isOk());
        mockMvc.perform(as(get("/api/fm/staff/5/profile"), "fm@x.vn"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.idNumber").doesNotExist());
    }

    @Test
    void staffOwnRoutes() throws Exception {
        login("fs@x.vn", "FS");
        when(shifts.myShifts(null, null)).thenReturn(List.of(shift()));
        when(leaves.myLeaves()).thenReturn(List.of(leave()));
        when(leaves.request(DAY, DAY, "Ốm")).thenReturn(leave());
        when(leaves.cancel(3L)).thenThrow(StaffingException.notFound("Không tìm thấy đơn nghỉ phép"));
        when(profiles.mine()).thenReturn(summary());

        mockMvc.perform(as(get("/api/staff/shifts"), "fs@x.vn")).andExpect(status().isOk());
        mockMvc.perform(as(get("/api/staff/leaves"), "fs@x.vn")).andExpect(status().isOk());
        mockMvc.perform(json(post("/api/staff/leaves"), "fs@x.vn",
                "{\"fromDate\":\"2026-11-12\",\"toDate\":\"2026-11-12\",\"reason\":\"Ốm\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(as(post("/api/staff/leaves/3/cancel"), "fs@x.vn")).andExpect(status().isNotFound());
        mockMvc.perform(as(get("/api/staff/profile"), "fs@x.vn"))
                .andExpect(jsonPath("$.data.position").value("Nhân viên kho"))
                .andExpect(jsonPath("$.data.idNumber").doesNotExist());
        mockMvc.perform(as(get("/api/fm/staff-shifts?facilityId=1"), "fs@x.vn")).andExpect(status().isForbidden());
    }

    @Test
    void adminAndBomProfileRoutes() throws Exception {
        login("admin@x.vn", "ADMIN");
        when(profiles.get(5L)).thenReturn(full());
        when(profiles.update(eq(5L), any())).thenReturn(full());
        mockMvc.perform(as(get("/api/admin/accounts/5/profile"), "admin@x.vn"))
                .andExpect(jsonPath("$.data.idNumber").value("079123456789"));
        mockMvc.perform(json(put("/api/admin/accounts/5/profile"), "admin@x.vn",
                "{\"position\":\"Nhân viên kho\",\"idNumber\":\"079123456789\"}")).andExpect(status().isOk());

        login("bom@x.vn", "BOM");
        doThrow(StaffingException.invalid("Số giấy tờ chỉ gồm chữ và số")).when(profiles).update(eq(6L), any());
        mockMvc.perform(as(get("/api/bom/staff/5/profile"), "bom@x.vn")).andExpect(status().isOk());
        mockMvc.perform(json(put("/api/bom/staff/5/profile"), "bom@x.vn", "{}")).andExpect(status().isOk());
        mockMvc.perform(json(put("/api/bom/staff/6/profile"), "bom@x.vn", "{\"idNumber\":\"!\"}"))
                .andExpect(status().isBadRequest());
        when(profiles.get(9L)).thenThrow(StaffingException.forbidden("x"));
        mockMvc.perform(as(get("/api/bom/staff/9/profile"), "bom@x.vn")).andExpect(status().isForbidden());
    }

    private static ShiftView shift() {
        return new ShiftView(7L, 1L, "Q7", 5L, "FS Quận 7", "fs@x.vn", DAY, LocalTime.of(7, 0), LocalTime.of(21, 0),
                null, Instant.now());
    }

    private static LeaveView leave() {
        return new LeaveView(3L, 5L, null, "fs@x.vn", DAY, DAY, "Ốm", "Pending", null, null, null, null,
                Instant.now(), true);
    }

    private static ProfileSummary summary() {
        return new ProfileSummary(5L, "fs@x.vn", "FS", null, "FS", "ACTIVE", "Nhân viên kho", null, null, null, null,
                null);
    }

    private static ProfileView full() {
        return new ProfileView(5L, "fs@x.vn", "FS", null, "FS", "ACTIVE", "Nhân viên kho", null, "079123456789",
                null, null, null, null);
    }

    private void login(String email, String roleName) {
        Role role = mock(Role.class);
        when(role.getName()).thenReturn(roleName);
        Account account = mock(Account.class);
        when(account.getEmail()).thenReturn(email);
        when(account.getRole()).thenReturn(role);
        when(account.getStatus()).thenReturn(AccountStatus.ACTIVE);
        when(account.isLoginAllowed()).thenReturn(true);
        when(accountRepository.findWithRoleByEmail(email)).thenReturn(Optional.of(account));
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
