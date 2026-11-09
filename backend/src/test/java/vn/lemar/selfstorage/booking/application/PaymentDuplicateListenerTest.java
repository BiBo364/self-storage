package vn.lemar.selfstorage.booking.application;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import vn.lemar.selfstorage.booking.repository.InvoiceRepository;
import vn.lemar.selfstorage.notification.application.NotificationType;
import vn.lemar.selfstorage.payment.application.event.PaymentDuplicateDetected;

class PaymentDuplicateListenerTest {

    private final InvoiceRepository invoices = mock(InvoiceRepository.class);
    private final OrderNotifier orderNotifier = mock(OrderNotifier.class);
    private final InvoiceRefundService refunds = mock(InvoiceRefundService.class);
    private final PaymentDuplicateListener listener =
            new PaymentDuplicateListener(invoices, orderNotifier, refunds);

    @Test
    void notifiesFacilityManagerForDuplicatePayment() {
        when(invoices.findOrderIdById(1L)).thenReturn(Optional.of(2L));

        listener.onDuplicatePayment(new PaymentDuplicateDetected(
                1L, "TXN-DUP", new BigDecimal("10000"), Instant.now()));

        verify(orderNotifier).notifyFacilityManager(eq(2L),
                eq(NotificationType.PAYMENT_DUPLICATE_REFUND_REQUIRED),
                eq(Map.of("orderCode", "2", "vnpTxnRef", "TXN-DUP", "amount", "10000")));
    }

    @Test
    void duplicatePaymentPutsTheInvoiceInTheRefundQueueOnce() {
        vn.lemar.selfstorage.booking.domain.Invoice fresh = mock(vn.lemar.selfstorage.booking.domain.Invoice.class);
        when(fresh.getRefundStatus()).thenReturn(vn.lemar.selfstorage.booking.domain.InvoiceRefundStatus.NONE);
        when(invoices.findOrderIdById(1L)).thenReturn(Optional.of(2L));
        when(invoices.findByIdForUpdate(1L)).thenReturn(Optional.of(fresh));

        listener.onDuplicatePayment(new PaymentDuplicateDetected(
                1L, "TXN-DUP", new BigDecimal("10000"), Instant.now()));

        verify(refunds).requireFull(fresh, InvoiceRefundReasons.DUPLICATE_PAYMENT, null);
    }

    @Test
    void invoiceAlreadyAwaitingRefundKeepsItsReason() {
        vn.lemar.selfstorage.booking.domain.Invoice pending =
                mock(vn.lemar.selfstorage.booking.domain.Invoice.class);
        when(pending.getRefundStatus()).thenReturn(vn.lemar.selfstorage.booking.domain.InvoiceRefundStatus.REQUIRED);
        when(invoices.findOrderIdById(1L)).thenReturn(Optional.of(2L));
        when(invoices.findByIdForUpdate(1L)).thenReturn(Optional.of(pending));

        listener.onDuplicatePayment(new PaymentDuplicateDetected(
                1L, "TXN-DUP", new BigDecimal("10000"), Instant.now()));

        verify(refunds, never()).requireFull(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void missingOrderDoesNotNotify() {
        when(invoices.findOrderIdById(1L)).thenReturn(Optional.empty());

        listener.onDuplicatePayment(new PaymentDuplicateDetected(
                1L, "TXN-DUP", new BigDecimal("10000"), Instant.now()));

        verify(orderNotifier, never()).notifyFacilityManager(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }
}
