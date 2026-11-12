package vn.lemar.selfstorage.booking.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
import java.util.Map;
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
import vn.lemar.selfstorage.booking.application.AppointmentBookingService;
import vn.lemar.selfstorage.booking.application.AppointmentBookingService.BookedAppointment;
import vn.lemar.selfstorage.booking.application.AppointmentBookingService.Day;
import vn.lemar.selfstorage.booking.application.AppointmentBookingService.Slot;
import vn.lemar.selfstorage.booking.application.AppointmentBookingService.SlotWindow;
import vn.lemar.selfstorage.booking.application.FmFacilityReadService;
import vn.lemar.selfstorage.booking.application.InvoicePaymentService;
import vn.lemar.selfstorage.booking.application.InvoiceQueryService;
import vn.lemar.selfstorage.booking.application.InvoicePaymentService.PayResult;
import vn.lemar.selfstorage.booking.application.InvoicePaymentService.PaymentCheck;
import vn.lemar.selfstorage.booking.application.OrderOverviewQueryService;
import vn.lemar.selfstorage.booking.application.OrderOverviewQueryService.OrderOverview;
import vn.lemar.selfstorage.booking.application.RentalOrderCancelService;
import vn.lemar.selfstorage.booking.application.RentalOrderCancelService.CancelResult;
import vn.lemar.selfstorage.booking.application.exception.BookingConflictException;
import vn.lemar.selfstorage.booking.application.exception.BookingForbiddenException;
import vn.lemar.selfstorage.booking.application.exception.BookingNotFoundException;
import vn.lemar.selfstorage.booking.application.exception.BookingValidationException;
import vn.lemar.selfstorage.booking.repository.OrderOverviewReadRepository.OrderOverviewRow;
import vn.lemar.selfstorage.identity.application.AccountJwtAuthenticationConverter;
import vn.lemar.selfstorage.identity.application.AccountQueryService;
import vn.lemar.selfstorage.identity.application.CurrentAccountProvider;
import vn.lemar.selfstorage.identity.config.JwtConfiguration;
import vn.lemar.selfstorage.identity.domain.Account;
import vn.lemar.selfstorage.identity.domain.AccountStatus;
import vn.lemar.selfstorage.identity.domain.Role;
import vn.lemar.selfstorage.identity.repository.AccountRepository;

/** HTTP contract các route hoàn thiện Flow 1: thanh toán hoá đơn, đơn của tôi + đặt lịch, FM xem/huỷ đơn. */
@WebMvcTest(controllers = {InvoicePaymentController.class, RentalOrderController.class,
    FmRentalOrderActionController.class})
@Import({SecurityConfig.class, JwtConfiguration.class, AccountJwtAuthenticationConverter.class,
    BookingExceptionHandler.class})
@TestPropertySource(properties = "security.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=")
class Flow1CompletionControllerTest {

    private static final String CUSTOMER = "customer@example.com";
    private static final String FM = "fm@example.com";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtEncoder jwtEncoder;

    @MockBean
    private InvoicePaymentService invoicePaymentService;
    @MockBean
    private InvoiceQueryService invoiceQueryService;
    @MockBean
    private OrderOverviewQueryService overviewQuery;
    @MockBean
    private AppointmentBookingService bookingService;
    @MockBean
    private RentalOrderCancelService cancelService;
    @MockBean
    private FmFacilityReadService facilityReadService;
    @MockBean
    private CurrentAccountProvider currentAccountProvider;
    @MockBean
    private AccountQueryService accountQueryService;
    @MockBean
    private AccountRepository accountRepository;

    // ------------------------------------------------------------------ thanh toán

    @Test
    void payReturnsGatewayUrl() throws Exception {
        customer();
        when(invoicePaymentService.start(eq(5L), eq(6L), anyString()))
                .thenReturn(new PayResult("https://pay?x=1", null, false));

        mockMvc.perform(as(post("/api/invoices/5/pay"), CUSTOMER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paymentUrl").value("https://pay?x=1"))
                .andExpect(jsonPath("$.message").value(InvoicePaymentController.PAY_MESSAGE));
    }

    @Test
    void rejectedPaymentIs409WithInvoiceCanceledFlag() throws Exception {
        customer();
        when(invoicePaymentService.start(eq(5L), eq(6L), anyString()))
                .thenReturn(new PayResult(null, "Khoang chứa đã được đặt cọc", true));

        mockMvc.perform(as(post("/api/invoices/5/pay"), CUSTOMER))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Khoang chứa đã được đặt cọc"))
                .andExpect(jsonPath("$.invoiceCanceled").value(true));
    }

    @Test
    void payUnknownInvoiceIs404() throws Exception {
        customer();
        when(invoicePaymentService.start(eq(9L), eq(6L), anyString()))
                .thenThrow(new BookingNotFoundException("Không tìm thấy hoá đơn"));

        mockMvc.perform(as(post("/api/invoices/9/pay"), CUSTOMER)).andExpect(status().isNotFound());
    }

    @Test
    void paymentCheckReturnsStatuses() throws Exception {
        customer();
        when(invoicePaymentService.check(eq(5L), eq(6L), anyString(), eq(Map.of())))
                .thenReturn(new PaymentCheck(5L, "Paid", "Success", null, null));

        mockMvc.perform(as(post("/api/invoices/5/payment-check"), CUSTOMER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.invoiceStatus").value("Paid"))
                .andExpect(jsonPath("$.data.paymentStatus").value("Success"));
    }

    @Test
    void paymentCheckForwardsReturnParams() throws Exception {
        customer();
        Map<String, String> params = Map.of("vnp_TxnRef", "INV5T1", "vnp_ResponseCode", "00",
                "vnp_SecureHash", "abc");
        when(invoicePaymentService.check(eq(5L), eq(6L), anyString(), eq(params)))
                .thenReturn(new PaymentCheck(5L, "Paid", "Success", null, null));

        mockMvc.perform(as(post("/api/invoices/5/payment-check"), CUSTOMER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"gatewayParams\":{\"vnp_TxnRef\":\"INV5T1\",\"vnp_ResponseCode\":\"00\","
                                + "\"vnp_SecureHash\":\"abc\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.invoiceStatus").value("Paid"));
    }

    @Test
    void paymentCheckRejectsTooManyReturnParams() throws Exception {
        customer();
        StringBuilder json = new StringBuilder("{\"gatewayParams\":{");
        for (int i = 0; i < 51; i++) {
            json.append(i == 0 ? "" : ",").append("\"vnp_K").append(i).append("\":\"v\"");
        }
        json.append("}}");

        mockMvc.perform(as(post("/api/invoices/5/payment-check"), CUSTOMER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.toString()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void payRequiresToken() throws Exception {
        mockMvc.perform(post("/api/invoices/5/pay")).andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ đơn của tôi + đặt lịch

    @Test
    void myOrdersListAndDetail() throws Exception {
        customer();
        OrderOverview overview = new OrderOverview(row(), List.of("PAY_INVOICE", "CANCEL"));
        when(overviewQuery.listForCustomer(6L)).thenReturn(List.of(overview));
        when(overviewQuery.detailForCustomer(11L, 6L)).thenReturn(overview);

        mockMvc.perform(as(get("/api/rental-orders"), CUSTOMER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].order.id").value(11))
                .andExpect(jsonPath("$.data[0].availableActions[0]").value("PAY_INVOICE"));
        mockMvc.perform(as(get("/api/rental-orders/11"), CUSTOMER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.order.unitCode").value("A-01"));
    }

    @Test
    void slotsAndBooking() throws Exception {
        customer();
        Instant start = Instant.parse("2026-11-10T01:00:00Z");
        when(bookingService.slots(11L, 6L)).thenReturn(new SlotWindow(11L, 1L, LocalDate.parse("2026-11-07"),
                LocalDate.parse("2026-11-14"), List.of(new Day(LocalDate.parse("2026-11-10"),
                List.of(new Slot(start, start.plusSeconds(7200), true))))));
        when(bookingService.book(11L, 6L, LocalDate.parse("2026-11-10"), start)).thenReturn(new BookedAppointment(
                30L, 11L, "Scheduled", LocalDate.parse("2026-11-10"), start, start.plusSeconds(7200), "Q7", "addr"));

        mockMvc.perform(as(get("/api/rental-orders/11/appointment-slots"), CUSTOMER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.days[0].slots[0].available").value(true));
        mockMvc.perform(as(post("/api/rental-orders/11/appointments"), CUSTOMER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"date\":\"2026-11-10\",\"startAt\":\"2026-11-10T01:00:00Z\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.appointmentId").value(30))
                .andExpect(jsonPath("$.data.orderStatus").value("Scheduled"));
    }

    @Test
    void bookingErrorsMapToStatus() throws Exception {
        customer();
        when(bookingService.slots(11L, 6L)).thenThrow(new BookingConflictException("Đã quá hạn đặt lịch check-in"));
        when(bookingService.book(any(), any(), any(), any()))
                .thenThrow(new BookingValidationException("Khung giờ không hợp lệ"));

        mockMvc.perform(as(get("/api/rental-orders/11/appointment-slots"), CUSTOMER))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Đã quá hạn đặt lịch check-in"));
        mockMvc.perform(as(post("/api/rental-orders/11/appointments"), CUSTOMER)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void customerCancel() throws Exception {
        customer();
        when(cancelService.cancelByCustomer(11L, 6L)).thenReturn(new CancelResult(11L, "Canceled", "Khách hủy đơn"));

        mockMvc.perform(as(post("/api/rental-orders/11/cancel"), CUSTOMER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("Canceled"));
    }

    // ------------------------------------------------------------------ FM

    @Test
    void fmCancelsOrderWithReason() throws Exception {
        authenticate(FM, "FM");
        when(currentAccountProvider.getCurrentAccountId()).thenReturn(3L);
        when(cancelService.cancelByManager(11L, "Khách đổi ý", 3L))
                .thenReturn(new CancelResult(11L, "Canceled", "Khách đổi ý"));
        when(cancelService.cancelByManager(eq(12L), isNull(), eq(3L)))
                .thenThrow(new BookingForbiddenException("Không có quyền"));

        mockMvc.perform(as(post("/api/fm/rental-orders/11/cancel"), FM)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Khách đổi ý\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cancelReason").value("Khách đổi ý"));
        mockMvc.perform(as(post("/api/fm/rental-orders/12/cancel"), FM)).andExpect(status().isForbidden());
    }

    @Test
    void customerCannotUseFmRoutes() throws Exception {
        customer();
        mockMvc.perform(as(get("/api/fm/rental-orders"), CUSTOMER)).andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ helpers

    private static OrderOverviewRow row() {
        return new OrderOverviewRow(11L, "Pending", null, Instant.parse("2026-12-01T00:00:00Z"), false, 6L, 1L,
                "Q7", "addr", 1L, "Small", LocalDate.parse("2026-11-10"), 3, Instant.parse("2026-11-01T00:00:00Z"),
                "Khách", CUSTOMER, "0900000000", 2L, "A-01", null, "Available", new BigDecimal("500000"), 4L,
                "Agreed", 5L, "INV-DEP", "Deposit", "Unpaid", new BigDecimal("500000"),
                Instant.parse("2026-11-04T00:00:00Z"), null, null, null, null, null, null, null, null, null, null,
                null, null, null, null);
    }

    private void customer() {
        authenticate(CUSTOMER, "CUSTOMER");
        when(accountQueryService.requireIdByEmail(CUSTOMER)).thenReturn(6L);
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
