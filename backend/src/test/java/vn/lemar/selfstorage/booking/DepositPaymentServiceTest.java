package vn.lemar.selfstorage.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import vn.lemar.selfstorage.booking.application.DepositPaymentService;
import vn.lemar.selfstorage.booking.application.InvoiceVoidingService;
import vn.lemar.selfstorage.booking.application.exception.DepositNotAllowedException;
import vn.lemar.selfstorage.booking.application.exception.EmailNotVerifiedException;
import vn.lemar.selfstorage.booking.application.exception.InvoiceNotFoundException;
import vn.lemar.selfstorage.booking.domain.Invoice;
import vn.lemar.selfstorage.booking.domain.InvoiceStatus;
import vn.lemar.selfstorage.booking.domain.InvoiceType;
import vn.lemar.selfstorage.booking.domain.RentalOrder;
import vn.lemar.selfstorage.booking.repository.InvoiceRepository;
import vn.lemar.selfstorage.booking.repository.RentalOrderRepository;
import vn.lemar.selfstorage.facility.application.StorageUnitQuery;
import vn.lemar.selfstorage.facility.application.StorageUnitView;
import vn.lemar.selfstorage.identity.application.CurrentAccountProvider;
import vn.lemar.selfstorage.payment.application.PaymentService;
import vn.lemar.selfstorage.payment.application.PaymentSessionQuery;

/**
 * Năm nhánh kiểm của spec Flow 1.4, cộng hai cửa trước là sở hữu hóa đơn và xác minh email.
 *
 * <p>Mỗi test dựng đúng một điều kiện hỏng và xác nhận service dừng ở đó, không đi tiếp.
 */
class DepositPaymentServiceTest {

    private static final Long CUSTOMER_ID = 7L;
    private static final Long INVOICE_ID = 100L;
    private static final Long ORDER_ID = 200L;
    private static final Long UNIT_ID = 300L;
    private static final Long FACILITY_ID = 400L;
    private static final String PAY_URL = "https://sandbox.vnpayment.vn/paymentv2/vpcpay.html?x=1";

    private final InvoiceRepository invoices = mock(InvoiceRepository.class);
    private final RentalOrderRepository orders = mock(RentalOrderRepository.class);
    private final StorageUnitQuery units = mock(StorageUnitQuery.class);
    private final PaymentService payments = mock(PaymentService.class);
    private final PaymentSessionQuery sessions = mock(PaymentSessionQuery.class);
    private final CurrentAccountProvider currentAccount = mock(CurrentAccountProvider.class);
    private final InvoiceVoidingService invoiceVoiding = mock(InvoiceVoidingService.class);

    private DepositPaymentService service;
    private Invoice invoice;
    private RentalOrder order;

    private static StorageUnitView unit(String status) {
        return new StorageUnitView(UNIT_ID, "A-01", FACILITY_ID, 1L, status);
    }

    @BeforeEach
    void setUp() {
        service = new DepositPaymentService(invoices, orders, units, payments, sessions,
                currentAccount, invoiceVoiding);

        when(currentAccount.isCurrentAccountVerified()).thenReturn(true);
        when(currentAccount.getCurrentAccountId()).thenReturn(CUSTOMER_ID);

        invoice = mock(Invoice.class);
        when(invoice.getId()).thenReturn(INVOICE_ID);
        when(invoice.getOrderId()).thenReturn(ORDER_ID);
        when(invoice.getCustomerId()).thenReturn(CUSTOMER_ID);
        when(invoice.getStatus()).thenReturn(InvoiceStatus.UNPAID);
        when(invoice.getType()).thenReturn(InvoiceType.DEPOSIT);
        when(invoice.getAmount()).thenReturn(new BigDecimal("10000.00"));
        when(invoice.getTitle()).thenReturn("Dat coc khoang A");

        order = mock(RentalOrder.class);
        when(order.getId()).thenReturn(ORDER_ID);
        when(order.getUnitId()).thenReturn(UNIT_ID);
        when(order.getExpiresAt()).thenReturn(Instant.now().plusSeconds(3600));

        when(invoices.findById(INVOICE_ID)).thenReturn(Optional.of(invoice));
        when(invoices.findOrderIdByIdAndCustomerId(INVOICE_ID, CUSTOMER_ID)).thenReturn(Optional.of(ORDER_ID));
        when(invoices.findByIdForUpdate(INVOICE_ID)).thenReturn(Optional.of(invoice));
        when(orders.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
        when(units.lock(UNIT_ID)).thenReturn(Optional.of(unit("Available")));
        when(orders.findByUnitId(UNIT_ID)).thenReturn(List.of(order));
        when(invoices.findByOrderIdIn(List.of(ORDER_ID))).thenReturn(List.of(invoice));
        when(sessions.hasOpenSession(anyCollection())).thenReturn(false);
        when(sessions.hasSuccessfulPayment(INVOICE_ID)).thenReturn(false);
        when(sessions.hasOpenSessionForInvoice(INVOICE_ID)).thenReturn(false);
        when(sessions.openPaymentUrl(INVOICE_ID)).thenReturn(Optional.empty());
        when(payments.resumePayment(INVOICE_ID, "1.2.3.4")).thenReturn(Optional.empty());
        when(payments.startPayment(eq(INVOICE_ID), any())).thenReturn(PAY_URL);
    }

    @Test
    void branch5ClearPathReturnsPaymentUrl() {
        assertThat(service.startDeposit(INVOICE_ID, "1.2.3.4")).isEqualTo(PAY_URL);
    }

    @Test
    void successfulPaymentAlreadyRecordedRefusesANewSession() {
        when(sessions.hasSuccessfulPayment(INVOICE_ID)).thenReturn(true);

        assertThatThrownBy(() -> service.startDeposit(INVOICE_ID, "1.2.3.4"))
                .isInstanceOf(DepositNotAllowedException.class)
                .hasMessageContaining("đã được thanh toán thành công");

        verifyNoInteractions(payments);
        verifyNoInteractions(invoiceVoiding);
    }

    @Test
    void retryingTheSameInvoiceReturnsTheSameSessionUrl() {
        when(sessions.hasOpenSessionForInvoice(INVOICE_ID)).thenReturn(true);
        when(sessions.openPaymentUrl(INVOICE_ID)).thenReturn(Optional.of("https://pay/session"));

        assertThat(service.startDeposit(INVOICE_ID, "1.2.3.4"))
                .isEqualTo("https://pay/session");

        verifyNoInteractions(payments);
    }

    @Test
    void cacheMissResumesTheSameSession() {
        when(sessions.hasOpenSessionForInvoice(INVOICE_ID)).thenReturn(true);
        when(sessions.openPaymentUrl(INVOICE_ID)).thenReturn(Optional.empty());
        when(payments.resumePayment(INVOICE_ID, "1.2.3.4"))
                .thenReturn(Optional.of("https://pay/resumed"));

        assertThat(service.startDeposit(INVOICE_ID, "1.2.3.4"))
                .isEqualTo("https://pay/resumed");
    }

    @Test
    void pendingSessionWithoutCachedUrlIsRefused() {
        when(sessions.hasOpenSessionForInvoice(INVOICE_ID)).thenReturn(true);
        when(sessions.openPaymentUrl(INVOICE_ID)).thenReturn(Optional.empty());
        when(payments.resumePayment(INVOICE_ID, "1.2.3.4")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.startDeposit(INVOICE_ID, "1.2.3.4"))
                .isInstanceOf(DepositNotAllowedException.class)
                .hasMessageContaining("hết hiệu lực");
    }

    @Test
    void amountComesFromTheInvoiceNotTheCaller() {
        service.startDeposit(INVOICE_ID, "1.2.3.4");

        verify(payments).startPayment(eq(INVOICE_ID), org.mockito.ArgumentMatchers.argThat(
                request -> request.amount().compareTo(new BigDecimal("10000.00")) == 0));
    }

    @Test
    void unverifiedEmailIsRejectedBeforeReadingTheInvoice() {
        when(currentAccount.isCurrentAccountVerified()).thenReturn(false);

        assertThatThrownBy(() -> service.startDeposit(INVOICE_ID, "1.2.3.4"))
                .isInstanceOf(EmailNotVerifiedException.class);
        verifyNoInteractions(invoices);
    }

    @Test
    void someoneElsesInvoiceLooksLikeItDoesNotExist() {
        when(invoices.findOrderIdByIdAndCustomerId(INVOICE_ID, CUSTOMER_ID)).thenReturn(Optional.empty());
        when(invoice.getCustomerId()).thenReturn(999L);

        assertThatThrownBy(() -> service.startDeposit(INVOICE_ID, "1.2.3.4"))
                .isInstanceOf(InvoiceNotFoundException.class);
    }

    @Test
    void branch1ExpiredOrderIsRejectedBeforeTouchingUnit() {
        when(order.getExpiresAt()).thenReturn(Instant.now().minusSeconds(60));

        assertThatThrownBy(() -> service.startDeposit(INVOICE_ID, "1.2.3.4"))
                .isInstanceOf(DepositNotAllowedException.class)
                .hasMessageContaining("Đơn thuê kho đã hết hạn");
        verifyNoInteractions(invoiceVoiding);
        verifyNoInteractions(units);
    }

    @Test
    void branch2ReservedUnitVoidsInvoiceAndRefuses() {
        when(units.lock(UNIT_ID)).thenReturn(Optional.of(unit("Reserved")));

        assertThatThrownBy(() -> service.startDeposit(INVOICE_ID, "1.2.3.4"))
                .isInstanceOf(DepositNotAllowedException.class)
                .hasMessageContaining("đã được đặt cọc bởi khách hàng khác");

        verify(invoiceVoiding).voidForUnavailableUnit(INVOICE_ID, "A-01", FACILITY_ID, CUSTOMER_ID);
        verifyNoInteractions(payments);
    }

    @Test
    void branch3MaintenanceUnitVoidsInvoiceAndRefuses() {
        when(units.lock(UNIT_ID)).thenReturn(Optional.of(unit("Maintenance")));

        assertThatThrownBy(() -> service.startDeposit(INVOICE_ID, "1.2.3.4"))
                .isInstanceOf(DepositNotAllowedException.class)
                .hasMessageContaining("hiện không khả dụng");

        verify(invoiceVoiding).voidForUnavailableUnit(INVOICE_ID, "A-01", FACILITY_ID, CUSTOMER_ID);
    }

    @Test
    void unavailableUnitOnAnOrderThatHoldsItMarksItForReproposal() {
        when(order.holdsUnit()).thenReturn(true);
        when(units.lock(UNIT_ID)).thenReturn(Optional.of(unit("Reserved")));

        assertThatThrownBy(() -> service.startDeposit(INVOICE_ID, "1.2.3.4"))
                .isInstanceOf(DepositNotAllowedException.class)
                .hasMessageContaining("đã được đặt cọc bởi khách hàng khác");

        verify(order).requireReproposal();
        verify(invoiceVoiding).voidForUnavailableUnit(INVOICE_ID, "A-01", FACILITY_ID, CUSTOMER_ID);
    }

    @Test
    void locksTheOrderThenTheInvoiceThenTheUnit() {
        service.startDeposit(INVOICE_ID, "1.2.3.4");

        InOrder order = org.mockito.Mockito.inOrder(orders, invoices, units);
        order.verify(orders).findByIdForUpdate(ORDER_ID);
        order.verify(invoices).findByIdForUpdate(INVOICE_ID);
        order.verify(units).lock(UNIT_ID);
    }

    @Test
    void branch4OpenSessionOnUnitBlocksWithoutSideEffects() {
        Invoice otherInvoice = mock(Invoice.class);
        when(otherInvoice.getId()).thenReturn(101L);
        when(invoices.findByOrderIdIn(List.of(ORDER_ID))).thenReturn(List.of(invoice, otherInvoice));
        when(sessions.hasOpenSession(List.of(101L))).thenReturn(true);

        assertThatThrownBy(() -> service.startDeposit(INVOICE_ID, "1.2.3.4"))
                .isInstanceOf(DepositNotAllowedException.class)
                .hasMessageContaining("đang trong quá trình xử lý thanh toán");

        verifyNoInteractions(invoiceVoiding);
        verifyNoInteractions(payments);
    }

    /**
     * Khách bấm back rồi bấm lại không phải "khách hàng khác" — phiên của chính hóa đơn này phải
     * bị loại khỏi phép kiểm, nếu không khách bị chặn 15 phút trên đơn của chính mình.
     */
    @Test
    void customersOwnOpenSessionDoesNotBlockThemselves() {
        assertThat(service.startDeposit(INVOICE_ID, "1.2.3.4")).isEqualTo(PAY_URL);

        verify(sessions).hasOpenSession(List.of());
    }

    @Test
    void lockIsCheckedPerUnitNotPerInvoice() {
        RentalOrder otherOrder = mock(RentalOrder.class);
        when(otherOrder.getId()).thenReturn(201L);
        Invoice otherInvoice = mock(Invoice.class);
        when(otherInvoice.getId()).thenReturn(101L);
        when(orders.findByUnitId(UNIT_ID)).thenReturn(List.of(order, otherOrder));
        when(invoices.findByOrderIdIn(List.of(ORDER_ID, 201L)))
                .thenReturn(List.of(invoice, otherInvoice));

        service.startDeposit(INVOICE_ID, "1.2.3.4");

        verify(sessions).hasOpenSession(List.of(101L));
    }

    @Test
    void paidInvoiceDoesNotOpenANewSession() {
        when(invoice.getStatus()).thenReturn(InvoiceStatus.PAID);

        assertThatThrownBy(() -> service.startDeposit(INVOICE_ID, "1.2.3.4"))
                .isInstanceOf(DepositNotAllowedException.class)
                .hasMessageContaining("không ở trạng thái chờ thanh toán cọc");
        verifyNoInteractions(payments);
    }

    @Test
    void nonDepositInvoiceIsRejected() {
        when(invoice.getType()).thenReturn(InvoiceType.RENTAL);

        assertThatThrownBy(() -> service.startDeposit(INVOICE_ID, "1.2.3.4"))
                .isInstanceOf(DepositNotAllowedException.class);
        verifyNoInteractions(payments);
    }

    @Test
    void orderWithoutAnExistingUnitIsRejected() {
        when(units.lock(UNIT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.startDeposit(INVOICE_ID, "1.2.3.4"))
                .isInstanceOf(DepositNotAllowedException.class)
                .hasMessageContaining("hiện không khả dụng");
    }

    @Test
    void txnRefIsAlphanumericAndDerivedFromTheInvoice() {
        service.startDeposit(INVOICE_ID, "1.2.3.4");

        verify(payments).startPayment(anyLong(), org.mockito.ArgumentMatchers.argThat(request ->
                request.txnRef().startsWith("INV" + INVOICE_ID + "T")
                        && request.txnRef().matches("[A-Za-z0-9]{1,100}")));
    }

    @Test
    void orderWithoutADeadlineIsNotTreatedAsExpired() {
        when(order.getExpiresAt()).thenReturn(null);

        assertThat(service.startDeposit(INVOICE_ID, "1.2.3.4")).isEqualTo(PAY_URL);
    }
}
