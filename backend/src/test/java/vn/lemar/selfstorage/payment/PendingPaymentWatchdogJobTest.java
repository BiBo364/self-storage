package vn.lemar.selfstorage.payment;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import vn.lemar.selfstorage.payment.application.PaymentReconciliationCaseService;
import vn.lemar.selfstorage.payment.application.PendingPaymentWatchdogJob;

class PendingPaymentWatchdogJobTest {

    private final PaymentReconciliationCaseService cases = mock(PaymentReconciliationCaseService.class);
    private final PendingPaymentWatchdogJob job = new PendingPaymentWatchdogJob(cases, 50);

    @Test
    void scanDelegatesWithBatchSize() {
        when(cases.createMissingCases(any(), anyInt())).thenReturn(2);

        job.scan();

        verify(cases).createMissingCases(any(), org.mockito.ArgumentMatchers.eq(50));
    }
}
