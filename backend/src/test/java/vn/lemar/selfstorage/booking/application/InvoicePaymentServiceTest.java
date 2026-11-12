package vn.lemar.selfstorage.booking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import vn.lemar.selfstorage.booking.application.InvoicePaymentService.PayResult;
import vn.lemar.selfstorage.booking.application.exception.BookingConflictException;
import vn.lemar.selfstorage.booking.application.exception.BookingNotFoundException;
import vn.lemar.selfstorage.booking.application.exception.DepositNotAllowedException;
import vn.lemar.selfstorage.booking.domain.Invoice;
import vn.lemar.selfstorage.booking.domain.InvoiceStatus;
import vn.lemar.selfstorage.booking.domain.InvoiceType;
import vn.lemar.selfstorage.booking.domain.RentalOrder;
import vn.lemar.selfstorage.booking.domain.RentalOrderStatus;
import vn.lemar.selfstorage.booking.repository.InvoiceRepository;
import vn.lemar.selfstorage.booking.repository.RentalOrderRepository;
import vn.lemar.selfstorage.payment.application.PaymentService;
import vn.lemar.selfstorage.payment.application.PaymentSessionQuery;

/**
 * Bấm thanh toán: hoá đơn cọc đi qua {@link DepositPaymentService} (từ chối → 409 kèm message), hoá đơn gắn hợp đồng
 * kiểm hoá đơn / đơn rồi dùng chung luật phiên của payment — một hoá đơn tối đa một phiên mở.
 */
class InvoicePaymentServiceTest {

    private static final long CUSTOMER = 6L;
    private static final long INVOICE = 50L;
    private static final long ORDER = 40L;

    private final InvoiceRepository invoices = mock(InvoiceRepository.class);
    private final RentalOrderRepository orders = mock(RentalOrderRepository.class);
    private final PaymentService payments = mock(PaymentService.class);
    private final PaymentSessionQuery sessions = mock(PaymentSessionQuery.class);
    private final DepositPaymentService deposits = mock(DepositPaymentService.class);
    private final AppointmentBookingService booking = mock(AppointmentBookingService.class);
    private InvoicePaymentService service;
    private RentalOrder order;

    @BeforeEach
    void setUp() {
        service = new InvoicePaymentService(invoices, orders, payments, sessions, deposits,
                mock(PlatformTransactionManager.class), booking);
        order = RentalOrder.open(1L, CUSTOMER, Instant.now().plusSeconds(86_400));
        ReflectionTestUtils.setField(order, "id", ORDER);
        when(invoices.findOrderIdById(INVOICE)).thenReturn(Optional.of(ORDER));
        when(orders.findByIdForUpdate(ORDER)).thenReturn(Optional.of(order));
    }

    private Invoice contractInvoice(Instant dueDate) {
        Invoice invoice = Invoice.forContract(9L, CUSTOMER, InvoiceType.RENTAL, "INV-RNT-1", "Tiền thuê",
                null, new BigDecimal("500000"), dueDate);
        ReflectionTestUtils.setField(invoice, "id", INVOICE);
        when(invoices.findById(INVOICE)).thenReturn(Optional.of(invoice));
        when(invoices.findByIdForUpdate(INVOICE)).thenReturn(Optional.of(invoice));
        return invoice;
    }

    private Invoice depositInvoice() {
        Invoice invoice = Invoice.deposit(order, "INV-DEP-1", "Cọc", null, new BigDecimal("500000"),
                Instant.now().plusSeconds(3600));
        ReflectionTestUtils.setField(invoice, "id", INVOICE);
        when(invoices.findById(INVOICE)).thenReturn(Optional.of(invoice));
        return invoice;
    }

    @Test
    void depositInvoiceIsDelegatedToTheDepositFlow() {
        depositInvoice();
        when(deposits.startDeposit(INVOICE, "1.2.3.4")).thenReturn("https://pay");

        assertThat(service.start(INVOICE, CUSTOMER, "1.2.3.4").paymentUrl()).isEqualTo("https://pay");
    }

    @Test
    void depositRejectionBecomesA409ResultAndReportsAVoidedInvoice() {
        Invoice peek = depositInvoice();
        // Đọc lại sau khi DepositPaymentService đã commit việc huỷ hoá đơn: một bản khác trong DB.
        Invoice voided = Invoice.deposit(order, "INV-DEP-1", "Cọc", null, new BigDecimal("500000"), null);
        voided.cancel();
        when(invoices.findById(INVOICE)).thenReturn(Optional.of(peek), Optional.of(voided));
        when(deposits.startDeposit(INVOICE, "ip"))
                .thenThrow(new DepositNotAllowedException("Khoang chứa đã được đặt cọc bởi khách hàng khác."));

        PayResult result = service.start(INVOICE, CUSTOMER, "ip");

        assertThat(result.rejected()).isTrue();
        assertThat(result.invoiceCanceled()).isTrue();
        assertThat(result.rejectionMessage()).contains("đã được đặt cọc");
    }

    @Test
    void invoiceOfAnotherCustomerIsNotFound() {
        contractInvoice(null);

        assertThatThrownBy(() -> service.start(INVOICE, 99L, "ip")).isInstanceOf(BookingNotFoundException.class);
    }

    @Test
    void contractInvoiceOpensANewSessionWithTheAmountFromTheDatabase() {
        contractInvoice(Instant.now().plusSeconds(3600));
        when(payments.startInvoicePayment(INVOICE, new BigDecimal("500000"), "TT hoa don 50", "ip"))
                .thenReturn("https://pay/new");

        assertThat(service.start(INVOICE, CUSTOMER, "ip").paymentUrl()).isEqualTo("https://pay/new");
    }

    @Test
    void openSessionReturnsTheCachedUrlInsteadOfOpeningAnother() {
        contractInvoice(null);
        when(sessions.hasOpenSessionForInvoice(INVOICE)).thenReturn(true);
        when(sessions.openPaymentUrl(INVOICE)).thenReturn(Optional.of("https://pay/old"));

        assertThat(service.start(INVOICE, CUSTOMER, "ip").paymentUrl()).isEqualTo("https://pay/old");
        verify(payments, never()).startInvoicePayment(any(), any(), any(), any());
    }

    @Test
    void openSessionThatCannotBeRebuiltIsRejected() {
        contractInvoice(null);
        when(sessions.hasOpenSessionForInvoice(INVOICE)).thenReturn(true);
        when(sessions.openPaymentUrl(INVOICE)).thenReturn(Optional.empty());
        when(payments.resumePayment(INVOICE, "ip")).thenReturn(Optional.empty());

        PayResult result = service.start(INVOICE, CUSTOMER, "ip");

        assertThat(result.rejected()).isTrue();
        assertThat(result.rejectionMessage()).isEqualTo(InvoicePaymentService.MSG_OWN_SESSION_OPEN);
        verify(payments, never()).startInvoicePayment(any(), any(), any(), any());
    }

    @Test
    void successfulPaymentNotYetSettledBlocksANewSession() {
        contractInvoice(null);
        when(sessions.hasSuccessfulPayment(INVOICE)).thenReturn(true);

        assertThatThrownBy(() -> service.start(INVOICE, CUSTOMER, "ip"))
                .isInstanceOf(BookingConflictException.class)
                .hasMessage(InvoicePaymentService.MSG_ALREADY_PAID);
    }

    @Test
    void closedOverdueOrPaidInvoicesAreRefused() {
        Invoice invoice = contractInvoice(Instant.now().minusSeconds(60));
        assertThatThrownBy(() -> service.start(INVOICE, CUSTOMER, "ip"))
                .hasMessage(InvoicePaymentService.MSG_OVERDUE);

        invoice.markPaid();
        assertThatThrownBy(() -> service.start(INVOICE, CUSTOMER, "ip"))
                .hasMessage(InvoicePaymentService.MSG_NOT_UNPAID);
    }

    @Test
    void orderStateGatesContractInvoicesBeforeHandover() {
        contractInvoice(null);
        ReflectionTestUtils.setField(order, "expiresAt", Instant.now().minusSeconds(60));
        assertThat(service.start(INVOICE, CUSTOMER, "ip").rejectionMessage())
                .isEqualTo(InvoicePaymentService.MSG_ORDER_EXPIRED);

        ReflectionTestUtils.setField(order, "status", RentalOrderStatus.CANCELED);
        assertThatThrownBy(() -> service.start(INVOICE, CUSTOMER, "ip"))
                .hasMessage(InvoicePaymentService.MSG_ORDER_CLOSED);

        // Sau bàn giao đơn Done là bình thường: hoá đơn gia hạn / phí vẫn trả được.
        ReflectionTestUtils.setField(order, "status", RentalOrderStatus.DONE);
        when(payments.startInvoicePayment(any(), any(), any(), any())).thenReturn("https://pay/after");
        assertThat(service.start(INVOICE, CUSTOMER, "ip").paymentUrl()).isEqualTo("https://pay/after");
    }

    @Test
    void checkReturnsStatusesAndOffersBookingAfterADepositIsPaid() {
        Invoice invoice = depositInvoice();
        invoice.markPaid();
        when(payments.reconcileLatest(INVOICE, "ip")).thenReturn(Optional.of(
                new PaymentService.PaymentSnapshot(INVOICE, "TXN", "Success", null, Instant.now())));
        when(booking.bookable(ORDER)).thenReturn(true);

        InvoicePaymentService.PaymentCheck check = service.check(INVOICE, CUSTOMER, "ip");

        assertThat(check.invoiceStatus()).isEqualTo(InvoiceStatus.PAID.dbValue());
        assertThat(check.paymentStatus()).isEqualTo("Success");
        assertThat(check.bookCheckinOrderId()).isEqualTo(ORDER);
        assertThatThrownBy(() -> service.check(INVOICE, 99L, "ip")).isInstanceOf(BookingNotFoundException.class);
    }

    @Test
    void checkSettlesSignedReturnParamsBeforeAskingTheGateway() {
        depositInvoice();
        Map<String, String> params = Map.of("vnp_TxnRef", "TXN", "vnp_SecureHash", "sig");
        when(payments.reconcileLatest(INVOICE, "ip")).thenReturn(Optional.empty());

        service.check(INVOICE, CUSTOMER, "ip", params);

        var order = org.mockito.Mockito.inOrder(payments);
        order.verify(payments).confirmFromReturn(INVOICE, params);
        order.verify(payments).reconcileLatest(INVOICE, "ip");
    }

    @Test
    void checkWithoutReturnParamsOnlyAsksTheGateway() {
        depositInvoice();
        when(payments.reconcileLatest(INVOICE, "ip")).thenReturn(Optional.empty());

        service.check(INVOICE, CUSTOMER, "ip", Map.of());
        service.check(INVOICE, CUSTOMER, "ip", null);

        verify(payments, never()).confirmFromReturn(any(), any());
    }

    @Test
    void checkOfAnotherCustomersInvoiceNeverTouchesReturnParams() {
        depositInvoice();

        assertThatThrownBy(() -> service.check(INVOICE, 99L, "ip", Map.of("vnp_TxnRef", "TXN")))
                .isInstanceOf(BookingNotFoundException.class);
        verify(payments, never()).confirmFromReturn(any(), any());
    }
}
