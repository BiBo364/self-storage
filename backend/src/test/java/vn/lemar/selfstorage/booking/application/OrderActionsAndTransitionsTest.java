package vn.lemar.selfstorage.booking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import vn.lemar.selfstorage.booking.domain.Invoice;
import vn.lemar.selfstorage.booking.domain.InvoiceStatus;
import vn.lemar.selfstorage.booking.domain.InvoiceType;
import vn.lemar.selfstorage.booking.domain.RentalOrder;
import vn.lemar.selfstorage.booking.domain.RentalOrderStatus;
import vn.lemar.selfstorage.booking.repository.OrderOverviewReadRepository.OrderOverviewRow;

/** {@code availableActions} theo trạng thái đơn + guard chuyển trạng thái domain + job cron Flow 1. */
class OrderActionsAndTransitionsTest {

    // ------------------------------------------------------------------ availableActions khách

    @Test
    void pendingOrderWithPendingProposalCanRespondAndCancel() {
        assertThat(OrderOverviewQueryService.customerActions(row("Pending", "Pending", null, null, null, null, null)))
                .containsExactly("RESPOND_PROPOSAL", "CANCEL");
    }

    @Test
    void agreedProposalWithUnpaidInvoiceCanPay() {
        assertThat(OrderOverviewQueryService.customerActions(row("Pending", "Agreed", "Unpaid", null, null, null,
                null))).containsExactly("PAY_INVOICE", "CANCEL");
    }

    @Test
    void depositedOrderCanBookCheckinUnlessSupplementUnpaid() {
        assertThat(OrderOverviewQueryService.customerActions(row("Deposited", "Agreed", "Paid", null, null, null,
                null))).containsExactly("BOOK_CHECKIN", "CANCEL");
        assertThat(OrderOverviewQueryService.customerActions(row("Deposited", "Agreed", "Unpaid", null, null, null,
                null))).containsExactly("PAY_INVOICE", "CANCEL");
    }

    @Test
    void inProgressAfterArrivalCanHandover() {
        assertThat(OrderOverviewQueryService.customerActions(row("InProgress", "Agreed", "Paid", "Done", "IN_PROGRESS",
                null, null))).containsExactly("HANDOVER", "CANCEL");
        assertThat(OrderOverviewQueryService.customerActions(row("InProgress", "Agreed", "Paid", "Pending",
                "IN_PROGRESS", null, null))).containsExactly("CANCEL");
        assertThat(OrderOverviewQueryService.customerActions(row("InProgress", "Agreed", "Paid", "Done", "REJECTED",
                null, null))).containsExactly("CANCEL");
    }

    @Test
    void doneOrderOnlyViewsContract() {
        assertThat(OrderOverviewQueryService.customerActions(row("Done", "Agreed", "Paid", "Done", "COMPLETED",
                9L, "Active"))).containsExactly("VIEW_CONTRACT");
        assertThat(OrderOverviewQueryService.customerActions(row("Done", "Agreed", "Paid", "Done", "COMPLETED",
                9L, "Ended"))).containsExactly("VIEW_CONTRACT");
        assertThat(OrderOverviewQueryService.customerActions(row("Done", "Agreed", "Unpaid", "Done", "COMPLETED",
                9L, "Draft"))).isEmpty();
        assertThat(OrderOverviewQueryService.customerActions(row("Canceled", "Pending", "Unpaid", null, null,
                null, null))).isEmpty();
    }

    // ------------------------------------------------------------------ domain

    @Test
    void rentalOrderTransitionsGuardTheirSourceState() {
        RentalOrder order = RentalOrder.open(1L, 2L, Instant.now().plusSeconds(3600));
        assertThatThrownBy(order::schedule).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(order::returnToDeposited).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(order::complete).isInstanceOf(IllegalStateException.class);

        order.markDeposited();
        assertThat(order.getStatus()).isEqualTo(RentalOrderStatus.DEPOSITED);
        assertThatThrownBy(order::markDeposited).isInstanceOf(IllegalStateException.class);
        order.schedule();
        order.returnToDeposited();
        order.schedule();
        order.startProgress();
        order.returnToDeposited();
        order.schedule();
        order.startProgress();
        order.requireReproposal();
        order.complete();
        assertThat(order.getStatus()).isEqualTo(RentalOrderStatus.DONE);
        assertThat(order.isNeedsReproposal()).isFalse();

        assertThat(order.isExpiredAt(Instant.now())).isFalse();
        assertThat(order.isExpiredAt(Instant.now().plusSeconds(7200))).isTrue();
        assertThat(RentalOrder.open(1L, 2L, null).isExpiredAt(Instant.now())).isFalse();
    }

    @Test
    void invoiceTransitionsOnlyFromUnpaid() {
        RentalOrder order = RentalOrder.open(1L, 2L, null);
        Invoice deposit = Invoice.deposit(order, "C1", "t", "d", BigDecimal.TEN, null);
        assertThat(deposit.isDepositKind()).isTrue();
        deposit.markPaid();
        assertThat(deposit.getStatus()).isEqualTo(InvoiceStatus.PAID);
        assertThatThrownBy(deposit::markPaid).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(deposit::expire).isInstanceOf(IllegalStateException.class);

        Invoice rental = Invoice.forContract(5L, 2L, InvoiceType.RENTAL, "C2", "t", "d", BigDecimal.ONE, null);
        assertThat(rental.getContractId()).isEqualTo(5L);
        assertThat(rental.isDepositKind()).isFalse();
        rental.expire();
        assertThat(rental.getStatus()).isEqualTo(InvoiceStatus.EXPIRED);
        assertThat(Invoice.depositSupplement(order, "C3", "t", "d", BigDecimal.ONE, null).isDepositKind()).isTrue();
    }

    // ------------------------------------------------------------------ job

    @Test
    void expiryJobRunsEveryStepAndSurvivesFailures() {
        BookingExpiryService service = mock(BookingExpiryService.class);
        when(service.expiredRequestIds()).thenReturn(List.of(1L, 2L));
        when(service.expireRequest(1L)).thenReturn(true);
        when(service.expireRequest(2L)).thenThrow(new IllegalStateException("boom"));
        when(service.overdueInvoiceIds()).thenReturn(List.of(3L));
        when(service.expireInvoice(3L)).thenReturn(false);
        when(service.expiredOrderIds()).thenReturn(List.of());
        when(service.expiringOrderIds()).thenReturn(List.of(4L));
        when(service.remindExpiring(4L)).thenReturn(true);

        new BookingExpiryJob(service).run();

        verify(service).expireInvoice(3L);
        verify(service).remindExpiring(4L);
    }

    // ------------------------------------------------------------------ helpers

    private static OrderOverviewRow row(String status, String proposal, String invoice, String appointment,
                                        String handover, Long contractId, String contractStatus) {
        return new OrderOverviewRow(1L, status, null, null, false, 2L, 3L, "Q7", "addr", 1L, "Small", null, 1, null,
                "Khách", "c@x.vn", "09", 4L, "A-01", null, "Available", BigDecimal.TEN, 5L, proposal, 6L, "INV",
                "Deposit", invoice, BigDecimal.TEN, null, appointment == null ? null : 7L, null, null, null,
                appointment, null, handover == null ? null : 8L, handover, null, contractId, null, contractStatus,
                null, null);
    }

}
