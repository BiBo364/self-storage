package vn.lemar.selfstorage.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import vn.lemar.selfstorage.payment.application.PaymentGateway;
import vn.lemar.selfstorage.audit.application.AuditRecorder;
import vn.lemar.selfstorage.payment.application.PaymentReconciliationCaseService;
import vn.lemar.selfstorage.payment.application.PaymentService;
import vn.lemar.selfstorage.payment.application.PaymentUrlCache;
import vn.lemar.selfstorage.payment.application.VnPayGateway;
import vn.lemar.selfstorage.payment.application.dto.GatewayQueryResult;
import vn.lemar.selfstorage.payment.application.dto.PaymentRequest;
import vn.lemar.selfstorage.payment.application.event.PaymentDuplicateDetected;
import vn.lemar.selfstorage.payment.application.event.PaymentFailed;
import vn.lemar.selfstorage.payment.application.event.PaymentSucceeded;
import vn.lemar.selfstorage.payment.config.VnPayProperties;
import vn.lemar.selfstorage.payment.domain.PaymentDirection;
import vn.lemar.selfstorage.payment.domain.PaymentStatus;
import vn.lemar.selfstorage.payment.domain.PaymentTransaction;
import vn.lemar.selfstorage.payment.repository.PaymentTransactionRepository;

/**
 * Hỏi cổng ({@code querydr} / tra cứu payOS) khi khách quay về: mỗi nhánh kết quả → trạng thái giao dịch + event;
 * chữ ký response querydr; huỷ phiên; số còn hoàn được.
 */
class PaymentReconcileTest {

    private static final BigDecimal AMOUNT = new BigDecimal("500000");

    private final PaymentGateway gateway = mock(PaymentGateway.class);
    private final PaymentTransactionRepository repository = mock(PaymentTransactionRepository.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final VnPayProperties properties = new VnPayProperties("TESTTMN1",
            "0123456789abcdef0123456789abcdef", "https://pay", "https://return");
    private PaymentService service;
    private PaymentTransaction transaction;

    @BeforeEach
    void setUp() {
        service = new PaymentService(gateway, repository, properties, events, new ObjectMapper(),
                mock(AuditRecorder.class), mock(PaymentUrlCache.class), mock(PaymentReconciliationCaseService.class));
        transaction = new PaymentTransaction(7L, "TXN1", AMOUNT, "Thanh toan");
        when(repository.findByVnpTxnRef("TXN1")).thenReturn(Optional.of(transaction));
    }

    @Test
    void paidAtGatewayMarksSuccessAndPublishesEvent() {
        when(gateway.query(anyString(), any(), any(), any()))
                .thenReturn(result("00", "00", AMOUNT, true));

        PaymentService.PaymentSnapshot snapshot = service.reconcile("TXN1", "127.0.0.1");

        assertThat(snapshot.status()).isEqualTo("Success");
        verify(events).publishEvent(any(PaymentSucceeded.class));
    }

    @Test
    void amountMismatchKeepsPending() {
        when(gateway.query(anyString(), any(), any(), any()))
                .thenReturn(result("00", "00", new BigDecimal("1"), true));

        assertThat(service.reconcile("TXN1", "127.0.0.1").status()).isEqualTo("Pending");
        verify(events, never()).publishEvent(any());
    }

    @Test
    void failedAtGatewayMarksFailed() {
        when(gateway.query(anyString(), any(), any(), any())).thenReturn(result("00", "02", AMOUNT, true));

        PaymentService.PaymentSnapshot snapshot = service.reconcile("TXN1", "127.0.0.1");

        assertThat(snapshot.status()).isEqualTo("Failed");
        assertThat(snapshot.failureReason()).contains("trạng thái 02");
        verify(events).publishEvent(any(PaymentFailed.class));
    }

    @Test
    void notFoundAtGatewayKeepsPendingEvenAfterWindow() {
        // Không đóng phiên theo đồng hồ: cổng chưa thấy giao dịch thì giữ Pending, watchdog mở case cho ADMIN.
        when(gateway.query(anyString(), any(), any(), any())).thenReturn(result("91", null, null, false));

        assertThat(service.reconcile("TXN1", "127.0.0.1").status()).isEqualTo("Pending");

        ReflectionTestUtils.setField(transaction, "createdAt", Instant.now().minus(30, ChronoUnit.MINUTES));
        assertThat(service.reconcile("TXN1", "127.0.0.1").status()).isEqualTo("Pending");
        verify(events, never()).publishEvent(any());
    }

    @Test
    void paidAtGatewayForInvoiceAlreadyPaidBecomesDuplicate() {
        when(repository.existsByInvoiceIdAndDirectionAndStatus(7L, PaymentDirection.PAY, PaymentStatus.SUCCESS))
                .thenReturn(true);
        when(gateway.query(anyString(), any(), any(), any())).thenReturn(result("00", "00", AMOUNT, true));

        assertThat(service.reconcile("TXN1", "127.0.0.1").status()).isEqualTo("Duplicate");
        verify(events).publishEvent(any(PaymentDuplicateDetected.class));
        verify(events, never()).publishEvent(any(PaymentSucceeded.class));
    }

    @Test
    void gatewayErrorKeepsPending() {
        when(gateway.query(anyString(), any(), any(), any())).thenThrow(new IllegalStateException("timeout"));

        assertThat(service.reconcile("TXN1", "127.0.0.1").status()).isEqualTo("Pending");
    }

    @Test
    void settledTransactionIsNotQueriedAgain() {
        transaction.markFailed("x", "{}");

        assertThat(service.reconcile("TXN1", "127.0.0.1").status()).isEqualTo("Failed");
        verify(gateway, never()).query(anyString(), any(), any(), any());
    }

    @Test
    void unknownTxnRefIsProgrammingError() {
        when(repository.findByVnpTxnRef("NOPE")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reconcile("NOPE", "127.0.0.1")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reconcileLatestAndLatestUseNewestTransaction() {
        when(repository.findFirstByInvoiceIdOrderByCreatedAtDescIdDesc(7L)).thenReturn(Optional.of(transaction));
        when(gateway.query(anyString(), any(), any(), any())).thenReturn(result("00", "01", AMOUNT, true));

        assertThat(service.reconcileLatest(7L, "127.0.0.1")).get()
                .extracting(PaymentService.PaymentSnapshot::status).isEqualTo("Pending");
        assertThat(service.latest(7L)).get().extracting(PaymentService.PaymentSnapshot::txnRef).isEqualTo("TXN1");
    }

    @Test
    void openSessionUsesThePaymentWindow() {
        // Phiên mở = Pending trong cửa sổ 15' — cùng khoá với PaymentSessionQuery.
        Instant before = Instant.now().minus(PaymentService.PAYMENT_WINDOW);
        service.invoicesWithOpenSession(List.of(7L));

        org.mockito.ArgumentCaptor<Instant> since = org.mockito.ArgumentCaptor.forClass(Instant.class);
        org.mockito.Mockito.verify(repository)
                .findInvoiceIdsWithStatusSince(any(), org.mockito.ArgumentMatchers.eq(PaymentStatus.PENDING),
                        since.capture());
        assertThat(since.getValue()).isBetween(before.minusSeconds(5), before.plusSeconds(5));
    }

    @Test
    void openSessionAndPaidAtDelegateToRepository() {
        when(repository.findInvoiceIdsWithStatusSince(any(), any(), any())).thenReturn(List.of(7L));
        when(repository.findFirstPaidAt(7L, PaymentStatus.SUCCESS)).thenReturn(Optional.of(Instant.EPOCH));
        when(repository.findPendingTxnRefsByInvoiceIds(List.of(7L))).thenReturn(List.of("TXN1"));

        assertThat(service.invoicesWithOpenSession(List.of())).isEmpty();
        assertThat(service.invoicesWithOpenSession(List.of(7L))).containsExactly(7L);
        assertThat(service.firstPaidAt(7L)).contains(Instant.EPOCH);
        assertThat(service.pendingTxnRefs(List.of())).isEmpty();
        assertThat(service.pendingTxnRefs(List.of(7L))).containsExactly("TXN1");
    }

    @Test
    void refundableCountsSuccessAndDuplicateMinusRefunds() {
        when(repository.sumSucceeded(7L, PaymentDirection.PAY)).thenReturn(new BigDecimal("500000"));
        when(repository.sumByStatus(7L, PaymentDirection.PAY, PaymentStatus.DUPLICATE))
                .thenReturn(new BigDecimal("500000"));
        when(repository.sumSucceeded(7L, PaymentDirection.REFUND)).thenReturn(new BigDecimal("200000"));

        assertThat(service.refundable(7L)).isEqualByComparingTo("800000");
    }

    @Test
    void cancelSessionClosesPendingOnlyWhenGatewayConfirms() {
        when(gateway.cancel("TXN1", "huy")).thenReturn(false);
        assertThat(service.cancelSession("TXN1", "huy").status()).isEqualTo("Pending");

        when(gateway.cancel("TXN1", "huy")).thenReturn(true);
        PaymentService.PaymentSnapshot closed = service.cancelSession("TXN1", "huy");
        assertThat(closed.status()).isEqualTo("Failed");
        assertThat(closed.failureReason()).isEqualTo("Đã huỷ phiên: huy");
        verify(events).publishEvent(any(PaymentFailed.class));

        // Đã đóng thì không gọi cổng lần nữa.
        assertThat(service.cancelSession("TXN1", "huy").status()).isEqualTo("Failed");
        verify(gateway, org.mockito.Mockito.times(2)).cancel("TXN1", "huy");
    }

    @Test
    void startPaymentPinsCreateDateToTransaction() {
        when(gateway.buildPaymentUrl(any())).thenAnswer(inv -> {
            PaymentRequest request = inv.getArgument(0);
            assertThat(request.createdAt()).isNotNull();
            return "https://pay";
        });

        assertThat(service.startPayment(7L, new PaymentRequest("TXN2", AMOUNT, "x", "127.0.0.1")))
                .isEqualTo("https://pay");
    }

    @Test
    void queryResponseSignatureIsVerified() {
        VnPayGateway vnPay = new VnPayGateway(properties);
        Map<String, String> response = new LinkedHashMap<>();
        response.put("vnp_ResponseId", "r1");
        response.put("vnp_Command", "querydr");
        response.put("vnp_ResponseCode", "00");
        response.put("vnp_Message", "OK");
        response.put("vnp_TmnCode", "TESTTMN1");
        response.put("vnp_TxnRef", "TXN1");
        response.put("vnp_Amount", "50000000");
        response.put("vnp_TransactionStatus", "00");
        response.put("vnp_TransactionNo", "123");
        response.put("vnp_PayDate", "20261106100000");
        String hash = (String) ReflectionTestUtils.invokeMethod(vnPay, "signData",
                (Object) ReflectionTestUtils.invokeMethod(vnPay, "querySignatureData", response));
        response.put("vnp_SecureHash", hash);

        GatewayQueryResult ok = ReflectionTestUtils.invokeMethod(vnPay, "toQueryResult", response);
        assertThat(ok.paid()).isTrue();
        assertThat(ok.amount()).isEqualByComparingTo(AMOUNT);

        response.put("vnp_SecureHash", "tampered");
        GatewayQueryResult bad = ReflectionTestUtils.invokeMethod(vnPay, "toQueryResult", response);
        assertThat(bad.paid()).isFalse();
        assertThat(bad.signatureValid()).isFalse();

        GatewayQueryResult empty = ReflectionTestUtils.invokeMethod(vnPay, "toQueryResult", (Object) null);
        assertThat(empty.signatureValid()).isFalse();
        assertThat(empty.amount()).isNull();
    }

    @Test
    void queryResultClassifiesGatewayCodes() {
        assertThat(result("91", null, null, false).notFound()).isTrue();
        assertThat(result("00", "01", AMOUNT, true).failed()).isFalse();
        assertThat(result("00", "02", AMOUNT, true).failed()).isTrue();
        assertThat(result("00", "00", AMOUNT, false).paid()).isFalse();
    }

    private static GatewayQueryResult result(String code, String status, BigDecimal amount, boolean signed) {
        return new GatewayQueryResult(code, status, "GW1", amount, "20261106100000", signed, Map.of());
    }
}
