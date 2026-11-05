package vn.lemar.selfstorage.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import vn.lemar.selfstorage.payment.application.PaymentReconciliationCaseService;
import vn.lemar.selfstorage.payment.application.event.PaymentPendingReviewRequired;
import vn.lemar.selfstorage.payment.application.exception.PendingPaymentFilterException;
import vn.lemar.selfstorage.payment.repository.PaymentReconciliationCaseRepository;

class PaymentReconciliationCaseServiceTest {

    private final PaymentReconciliationCaseRepository repository =
            mock(PaymentReconciliationCaseRepository.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final PaymentReconciliationCaseService service =
            new PaymentReconciliationCaseService(repository, events);

    @Test
    void createsOnlyEventsForNewCases() {
        var first = new PaymentReconciliationCaseRepository.PendingRow(1L, 7L, "TXN1",
                BigDecimal.TEN, Instant.now().minusSeconds(1200));
        var second = new PaymentReconciliationCaseRepository.PendingRow(2L, 8L, "TXN2",
                BigDecimal.ONE, Instant.now().minusSeconds(1200));
        when(repository.findWithoutCase(any(), anyInt())).thenReturn(List.of(first, second));
        when(repository.createIfAbsent(anyLong(), anyLong(), any())).thenReturn(Optional.of(10L))
                .thenReturn(Optional.empty());

        int created = service.createMissingCases(Instant.now(), 50);

        assertThat(created).isEqualTo(1);
        verify(events).publishEvent(any(PaymentPendingReviewRequired.class));
    }

    @Test
    void listsOpenCasesWithPaginationMetadata() {
        var row = new PaymentReconciliationCaseRepository.CaseRow(10L, 1L, 7L, "TXN1",
                BigDecimal.TEN, Instant.now().minusSeconds(1200), Instant.now().minusSeconds(1200),
                null, "Open", null, null, null, null, null, null, null, null, null);
        when(repository.countOpen(any())).thenReturn(1L);
        when(repository.findOpenPage(any(), anyLong(), anyInt())).thenReturn(List.of(row));

        var page = service.listOpen(15, 0, 20);

        assertThat(page.totalElements()).isEqualTo(1);
        assertThat(page.totalPages()).isEqualTo(1);
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).pendingAgeMinutes()).isGreaterThanOrEqualTo(19);
    }

    @Test
    void rejectsInvalidPageSizeAndThreshold() {
        assertThatThrownBy(() -> service.listOpen(-1, 0, 20))
                .isInstanceOf(PendingPaymentFilterException.class);
        assertThatThrownBy(() -> service.listOpen(15, -1, 20))
                .isInstanceOf(PendingPaymentFilterException.class);
        assertThatThrownBy(() -> service.listOpen(15, 0, 0))
                .isInstanceOf(PendingPaymentFilterException.class);
        verify(repository, never()).findOpenPage(any(), anyLong(), anyInt());
    }
}
