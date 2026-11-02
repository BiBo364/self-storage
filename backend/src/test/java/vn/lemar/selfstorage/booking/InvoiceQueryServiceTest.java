package vn.lemar.selfstorage.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vn.lemar.selfstorage.booking.application.InvoiceQueryService;
import vn.lemar.selfstorage.booking.application.InvoiceView;
import vn.lemar.selfstorage.booking.application.exception.InvoiceNotFoundException;
import vn.lemar.selfstorage.booking.domain.Invoice;
import vn.lemar.selfstorage.booking.domain.InvoiceStatus;
import vn.lemar.selfstorage.booking.domain.InvoiceType;
import vn.lemar.selfstorage.booking.repository.InvoiceRepository;
import vn.lemar.selfstorage.identity.application.CurrentAccountProvider;

/**
 * Khách đọc hóa đơn của chính mình. Hóa đơn của người khác phải trả **cùng một** 404 với hóa đơn
 * không tồn tại — khác nhau là tiết lộ id nào đang có thật.
 */
class InvoiceQueryServiceTest {

    private static final Long CUSTOMER_ID = 7L;
    private static final Long INVOICE_ID = 100L;

    private final InvoiceRepository invoices = mock(InvoiceRepository.class);
    private final CurrentAccountProvider currentAccount = mock(CurrentAccountProvider.class);

    private InvoiceQueryService service;
    private Invoice invoice;

    @BeforeEach
    void setUp() {
        service = new InvoiceQueryService(invoices, currentAccount);

        invoice = mock(Invoice.class);
        when(invoice.getId()).thenReturn(INVOICE_ID);
        when(invoice.getOrderId()).thenReturn(200L);
        when(invoice.getCustomerId()).thenReturn(CUSTOMER_ID);
        when(invoice.getCode()).thenReturn("INV-100");
        when(invoice.getTitle()).thenReturn("Dat coc khoang A-01");
        when(invoice.getType()).thenReturn(InvoiceType.DEPOSIT);
        when(invoice.getStatus()).thenReturn(InvoiceStatus.PAID);
        when(invoice.getAmount()).thenReturn(new BigDecimal("10000.00"));
        when(invoice.getDueDate()).thenReturn(Instant.parse("2026-11-05T00:00:00Z"));

        when(currentAccount.getCurrentAccountId()).thenReturn(CUSTOMER_ID);
        when(invoices.findById(INVOICE_ID)).thenReturn(Optional.of(invoice));
    }

    @Test
    void ownInvoiceComesBackWithTheStatusTheGatewaySettled() {
        InvoiceView view = service.findOwn(INVOICE_ID);

        assertThat(view.id()).isEqualTo(INVOICE_ID);
        assertThat(view.status()).isEqualTo("Paid");
        assertThat(view.type()).isEqualTo("Deposit");
        assertThat(view.amount()).isEqualByComparingTo("10000.00");
    }

    @Test
    void someoneElsesInvoiceLooksLikeItDoesNotExist() {
        when(invoice.getCustomerId()).thenReturn(999L);

        assertThatThrownBy(() -> service.findOwn(INVOICE_ID))
                .isInstanceOf(InvoiceNotFoundException.class);
    }

    @Test
    void missingInvoiceIsNotFound() {
        when(invoices.findById(INVOICE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findOwn(INVOICE_ID))
                .isInstanceOf(InvoiceNotFoundException.class);
    }
}
