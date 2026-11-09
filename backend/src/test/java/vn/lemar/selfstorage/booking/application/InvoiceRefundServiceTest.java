package vn.lemar.selfstorage.booking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import vn.lemar.selfstorage.audit.application.AuditRecorder;
import vn.lemar.selfstorage.booking.domain.Invoice;
import vn.lemar.selfstorage.booking.domain.InvoiceRefundStatus;
import vn.lemar.selfstorage.booking.domain.InvoiceStatus;
import vn.lemar.selfstorage.booking.domain.RentalOrder;
import vn.lemar.selfstorage.booking.repository.InvoiceRepository;
import vn.lemar.selfstorage.payment.application.PaymentService;

/**
 * Một cơ chế hoàn tiền: mọi ca "tiền đã vào phải trả lại" đánh dấu hoá đơn {@code refund_status = Required}; FM ghi
 * nhận đủ số phải hoàn thì {@code Refunded}, chưa đủ thì {@code Processing}.
 */
class InvoiceRefundServiceTest {

    private static final long ORDER = 40L;

    private final InvoiceRepository invoices = mock(InvoiceRepository.class);
    private final PaymentService payments = mock(PaymentService.class);
    private final AuditRecorder audit = mock(AuditRecorder.class);
    private InvoiceRefundService service;
    private RentalOrder order;

    @BeforeEach
    void setUp() {
        service = new InvoiceRefundService(invoices, payments, audit);
        order = RentalOrder.open(1L, 6L, Instant.now().plusSeconds(86_400));
        ReflectionTestUtils.setField(order, "id", ORDER);
    }

    private Invoice paidDeposit(long id, String amount, boolean supplement) {
        Invoice invoice = supplement
                ? Invoice.depositSupplement(order, "INV-SUP-" + id, "Cọc bổ sung", null, new BigDecimal(amount), null)
                : Invoice.deposit(order, "INV-DEP-" + id, "Cọc", null, new BigDecimal(amount), null);
        ReflectionTestUtils.setField(invoice, "id", id);
        invoice.markPaid();
        when(invoices.findByIdForUpdate(id)).thenReturn(Optional.of(invoice));
        return invoice;
    }

    @Test
    void depositRefundIsSplitNewestInvoiceFirstWithinWhatTheGatewayCollected() {
        Invoice deposit = paidDeposit(1L, "500000", false);
        Invoice supplement = paidDeposit(2L, "200000", true);
        when(invoices.findByOrderIdAndStatus(ORDER, InvoiceStatus.PAID)).thenReturn(List.of(deposit, supplement));
        when(payments.refundable(2L)).thenReturn(new BigDecimal("200000"));
        when(payments.refundable(1L)).thenReturn(new BigDecimal("500000"));

        service.requireDeposit(ORDER, new BigDecimal("300000"), InvoiceRefundReasons.CHECKOUT_DEPOSIT, 3L);

        assertThat(supplement.getRefundStatus()).isEqualTo(InvoiceRefundStatus.REQUIRED);
        assertThat(supplement.refundOwed()).isEqualByComparingTo("200000");
        assertThat(deposit.getRefundStatus()).isEqualTo(InvoiceRefundStatus.REQUIRED);
        assertThat(deposit.refundOwed()).isEqualByComparingTo("100000");
        assertThat(deposit.getRefundReason()).isEqualTo("CHECKOUT_DEPOSIT");
        verify(audit, times(2)).record(any());
    }

    @Test
    void depositWithNothingCollectedThroughTheGatewayIsLeftUnmarked() {
        Invoice deposit = paidDeposit(1L, "500000", false);
        when(invoices.findByOrderIdAndStatus(ORDER, InvoiceStatus.PAID)).thenReturn(List.of(deposit));
        when(payments.refundable(1L)).thenReturn(BigDecimal.ZERO);

        service.requireDeposit(ORDER, new BigDecimal("100000"), InvoiceRefundReasons.UNIT_CHANGE_EXCESS, null);
        service.requireDeposit(ORDER, BigDecimal.ZERO, InvoiceRefundReasons.UNIT_CHANGE_EXCESS, null);
        service.requireDeposit(ORDER, null, InvoiceRefundReasons.UNIT_CHANGE_EXCESS, null);

        assertThat(deposit.getRefundStatus()).isEqualTo(InvoiceRefundStatus.NONE);
        verify(audit, never()).record(any());
    }

    @Test
    void fullRefundMarksTheWholeInvoice() {
        Invoice deposit = paidDeposit(1L, "500000", false);

        service.requireFull(deposit, InvoiceRefundReasons.INVOICE_CLOSED, null);

        assertThat(deposit.getRefundStatus()).isEqualTo(InvoiceRefundStatus.REQUIRED);
        assertThat(deposit.getRefundAmount()).isNull();
        assertThat(deposit.refundOwed()).isEqualByComparingTo("500000");
        verify(audit).record(any());
    }

    @Test
    void recordedRefundsMoveTheInvoiceThroughProcessingToRefunded() {
        Invoice deposit = paidDeposit(1L, "500000", false);
        deposit.markRefundRequired(InvoiceRefundReasons.UNIT_CHANGE_EXCESS, new BigDecimal("100000"));
        deposit.markRefundRequired(InvoiceRefundReasons.UNIT_CHANGE_EXCESS, new BigDecimal("50000"));
        assertThat(deposit.refundOwed()).isEqualByComparingTo("150000");

        deposit.recordRefund(new BigDecimal("100000"), Instant.now());
        assertThat(deposit.getRefundStatus()).isEqualTo(InvoiceRefundStatus.PROCESSING);

        deposit.recordRefund(new BigDecimal("150000"), Instant.now());
        assertThat(deposit.getRefundStatus()).isEqualTo(InvoiceRefundStatus.REFUNDED);
        assertThat(deposit.getRefundedAt()).isNotNull();
    }

    @Test
    void voluntaryRefundOnAnInvoiceNeverMarkedKeepsNone() {
        Invoice deposit = paidDeposit(1L, "500000", false);

        deposit.recordRefund(new BigDecimal("100000"), Instant.now());

        assertThat(deposit.getRefundStatus()).isEqualTo(InvoiceRefundStatus.NONE);
    }
}
