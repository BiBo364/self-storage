package vn.lemar.selfstorage.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import vn.lemar.selfstorage.payment.application.PaymentSessionQueryService;
import vn.lemar.selfstorage.payment.application.PaymentUrlCache;
import vn.lemar.selfstorage.payment.domain.PaymentDirection;
import vn.lemar.selfstorage.payment.domain.PaymentTransaction;
import vn.lemar.selfstorage.payment.domain.PaymentStatus;
import vn.lemar.selfstorage.payment.repository.PaymentTransactionRepository;

/** Khóa giao dịch ngắn hạn suy ra từ giao dịch còn Pending — không có khóa riêng để lệch. */
class PaymentSessionQueryServiceTest {

    private final PaymentTransactionRepository repository = mock(PaymentTransactionRepository.class);
    private final PaymentUrlCache urlCache = mock(PaymentUrlCache.class);
    private final PaymentSessionQueryService service = new PaymentSessionQueryService(repository, urlCache);

    @Test
    void noInvoicesMeansNoOpenSessionAndNoQuery() {
        assertThat(service.hasOpenSession(List.of())).isFalse();
        assertThat(service.hasOpenSession(null)).isFalse();

        verifyNoInteractions(repository);
    }

    @Test
    void anOpenSessionIsAPendingTransactionInsideTheWindow() {
        when(repository.existsByInvoiceIdInAndStatusAndCreatedAtAfter(
                anyCollection(), any(PaymentStatus.class), any(Instant.class))).thenReturn(true);

        assertThat(service.hasOpenSession(List.of(1L, 2L))).isTrue();
    }

    @Test
    void sameInvoiceOpeningUsesThePendingCheck() {
        when(repository.existsByInvoiceIdInAndStatusAndCreatedAtAfter(
                anyCollection(), any(PaymentStatus.class), any(Instant.class))).thenReturn(true);

        assertThat(service.hasOpenSessionForInvoice(1L)).isTrue();
    }

    @Test
    void openPaymentUrlComesFromTheCache() {
        when(urlCache.get(1L)).thenReturn(Optional.of("https://pay/session"));

        assertThat(service.openPaymentUrl(1L)).contains("https://pay/session");
    }

    @Test
    void successfulPaymentDetailsAreReturnedForTheBookingRepairJob() {
        PaymentTransaction transaction = mock(PaymentTransaction.class);
        when(transaction.getInvoiceId()).thenReturn(1L);
        when(transaction.getVnpTxnRef()).thenReturn("TXN1");
        when(transaction.getAmount()).thenReturn(new BigDecimal("10000"));
        when(transaction.getPaidAt()).thenReturn(Instant.parse("2026-10-28T12:00:00Z"));
        when(repository.findFirstByInvoiceIdAndDirectionAndStatusOrderByPaidAtDesc(
                1L, PaymentDirection.PAY, PaymentStatus.SUCCESS))
                .thenReturn(Optional.of(transaction));

        var payment = service.findSuccessfulPayment(1L);

        assertThat(payment).hasValueSatisfying(found -> {
            assertThat(found.invoiceId()).isEqualTo(1L);
            assertThat(found.vnpTxnRef()).isEqualTo("TXN1");
            assertThat(found.amount()).isEqualByComparingTo(new BigDecimal("10000"));
            assertThat(found.paidAt()).isEqualTo(Instant.parse("2026-10-28T12:00:00Z"));
        });
    }

    @Test
    void successfulPaymentIsReportedForTheInvoice() {
        when(repository.existsByInvoiceIdAndDirectionAndStatus(
                1L, PaymentDirection.PAY, PaymentStatus.SUCCESS)).thenReturn(true);

        assertThat(service.hasSuccessfulPayment(1L)).isTrue();
    }

    @Test
    void noPendingTransactionMeansTheUnitIsFree() {
        when(repository.existsByInvoiceIdInAndStatusAndCreatedAtAfter(
                anyCollection(), any(PaymentStatus.class), any(Instant.class))).thenReturn(false);

        assertThat(service.hasOpenSession(List.of(1L))).isFalse();
    }
}
