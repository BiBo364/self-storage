package vn.lemar.selfstorage.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import vn.lemar.selfstorage.booking.application.InvoicePaymentService;
import vn.lemar.selfstorage.booking.application.InvoicePaymentService.PayResult;
import vn.lemar.selfstorage.booking.application.InvoiceQueryService;
import vn.lemar.selfstorage.booking.application.InvoiceListView;
import vn.lemar.selfstorage.booking.controller.InvoicePaymentController;
import vn.lemar.selfstorage.identity.application.AccountQueryService;

/**
 * Hai đường của controller hóa đơn: mở phiên thanh toán (VNPay bắt buộc `vnp_IpAddr`, nên phải lấy
 * IP khách cho đúng kể cả khi đứng sau proxy) và đọc lại hóa đơn sau redirect.
 */
class InvoicePaymentControllerTest {

    private final InvoicePaymentService service = mock(InvoicePaymentService.class);
    private final InvoiceQueryService invoiceQuery = mock(InvoiceQueryService.class);
    private final AccountQueryService accounts = mock(AccountQueryService.class);
    private final InvoicePaymentController controller =
            new InvoicePaymentController(service, invoiceQuery, accounts);
    private final HttpServletRequest request = mock(HttpServletRequest.class);
    private final Authentication auth = mock(Authentication.class);

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        when(auth.getName()).thenReturn("customer1@lemar.vn");
        when(accounts.requireIdByEmail("customer1@lemar.vn")).thenReturn(6L);
        when(service.start(anyLong(), anyLong(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new PayResult("https://pay", null, false));
    }

    @Test
    void usesTheFirstHopOfXForwardedForWhenBehindAProxy() {
        when(request.getHeader("X-Forwarded-For")).thenReturn("203.0.113.9, 10.0.0.1");

        controller.pay(auth, 1L, request);

        verify(service).start(1L, 6L, "203.0.113.9");
    }

    @Test
    void fallsBackToRemoteAddrWithoutTheHeader() {
        when(request.getHeader("X-Forwarded-For")).thenReturn(null);
        when(request.getRemoteAddr()).thenReturn("198.51.100.4");

        controller.pay(auth, 1L, request);

        verify(service).start(1L, 6L, "198.51.100.4");
    }

    @Test
    void blankHeaderIsTreatedAsAbsent() {
        when(request.getHeader("X-Forwarded-For")).thenReturn("   ");
        when(request.getRemoteAddr()).thenReturn("198.51.100.4");

        controller.pay(auth, 1L, request);

        verify(service).start(1L, 6L, "198.51.100.4");
    }

    /** FE gọi lại đường này sau redirect, nên response phải chở trạng thái hóa đơn thật. */
    @Test
    void detailReturnsTheInvoiceOfTheCaller() {
        InvoiceListView view = new InvoiceListView(7L, 3L, "INV-7", "Dat coc khoang A-01", null,
                "Deposit", "Paid", new BigDecimal("10000.00"), Instant.parse("2026-11-01T00:00:00Z"),
                Instant.parse("2026-11-05T00:00:00Z"), 5L, "A-01", 1L, "Lemar Quan 7");
        when(invoiceQuery.findOwn(7L)).thenReturn(view);

        assertThat(controller.detail(7L).getBody().data()).isSameAs(view);
    }

    /** Quyền sở hữu kiểm ở service; controller không được tự lọc rồi bỏ sót ca nào. */
    @Test
    void detailDelegatesOwnershipToTheService() {
        when(invoiceQuery.findOwn(anyLong())).thenReturn(unpaidView());

        controller.detail(9L);

        verify(invoiceQuery).findOwn(9L);
    }

    private static InvoiceListView unpaidView() {
        return new InvoiceListView(9L, 3L, "INV-9", "Dat coc khoang A-02", null, "Deposit", "Unpaid",
                new BigDecimal("10000.00"), Instant.parse("2026-11-01T00:00:00Z"),
                Instant.parse("2026-11-05T00:00:00Z"), 6L, "A-02", 1L, "Lemar Quan 7");
    }
}
