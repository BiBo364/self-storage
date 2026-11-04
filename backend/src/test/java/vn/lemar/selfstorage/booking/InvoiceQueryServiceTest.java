package vn.lemar.selfstorage.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vn.lemar.selfstorage.booking.application.InvoiceListView;
import vn.lemar.selfstorage.booking.application.InvoiceQueryService;
import vn.lemar.selfstorage.booking.application.exception.InvoiceNotFoundException;
import vn.lemar.selfstorage.booking.repository.BookingReadRepository;
import vn.lemar.selfstorage.identity.application.CurrentAccountProvider;

/**
 * Khách đọc một hóa đơn của chính mình. Quyền sở hữu nằm trong điều kiện truy vấn, nên phép thử ở
 * đây là service có truyền đúng id người gọi xuống read model hay không, và hàng rỗng có thành 404
 * hay không — hóa đơn của người khác và hóa đơn không tồn tại đều rỗng, không phân biệt được.
 */
class InvoiceQueryServiceTest {

    private static final Long CUSTOMER_ID = 7L;
    private static final Long INVOICE_ID = 100L;

    private final BookingReadRepository readRepository = mock(BookingReadRepository.class);
    private final CurrentAccountProvider currentAccount = mock(CurrentAccountProvider.class);

    private InvoiceQueryService service;

    @BeforeEach
    void setUp() {
        service = new InvoiceQueryService(readRepository, currentAccount);
        when(currentAccount.getCurrentAccountId()).thenReturn(CUSTOMER_ID);
    }

    @Test
    void ownInvoiceComesBackWithTheStatusTheGatewaySettled() {
        when(readRepository.findCustomerInvoice(CUSTOMER_ID, INVOICE_ID)).thenReturn(Optional.of(paidView()));

        InvoiceListView view = service.findOwn(INVOICE_ID);

        assertThat(view.id()).isEqualTo(INVOICE_ID);
        assertThat(view.status()).isEqualTo("Paid");
        assertThat(view.type()).isEqualTo("Deposit");
        assertThat(view.amount()).isEqualByComparingTo("10000.00");
    }

    /** Projection chốt ở contract đọc hóa đơn: FE dựng trang kết quả thanh toán từ đúng các trường này. */
    @Test
    void theViewCarriesUnitAndFacilityForTheResultPage() {
        when(readRepository.findCustomerInvoice(CUSTOMER_ID, INVOICE_ID)).thenReturn(Optional.of(paidView()));

        InvoiceListView view = service.findOwn(INVOICE_ID);

        assertThat(view.createdAt()).isEqualTo(Instant.parse("2026-11-01T00:00:00Z"));
        assertThat(view.unitId()).isEqualTo(5L);
        assertThat(view.unitCode()).isEqualTo("A-01");
        assertThat(view.facilityId()).isEqualTo(1L);
        assertThat(view.facilityName()).isEqualTo("Lemar Quan 7");
    }

    /** Lọc theo người gọi phải nằm trong chính câu truy vấn, không phải lọc sau khi đã đọc. */
    @Test
    void theQueryIsScopedToTheCaller() {
        when(readRepository.findCustomerInvoice(anyLong(), anyLong())).thenReturn(Optional.of(paidView()));

        service.findOwn(INVOICE_ID);

        verify(readRepository).findCustomerInvoice(CUSTOMER_ID, INVOICE_ID);
    }

    @Test
    void anInvoiceTheCallerDoesNotOwnIsNotFound() {
        when(readRepository.findCustomerInvoice(CUSTOMER_ID, INVOICE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findOwn(INVOICE_ID))
                .isInstanceOf(InvoiceNotFoundException.class);
    }

    private static InvoiceListView paidView() {
        return new InvoiceListView(INVOICE_ID, 200L, "INV-100", "Dat coc khoang A-01", null, "Deposit",
                "Paid", new BigDecimal("10000.00"), Instant.parse("2026-11-01T00:00:00Z"),
                Instant.parse("2026-11-05T00:00:00Z"), 5L, "A-01", 1L, "Lemar Quan 7");
    }
}
