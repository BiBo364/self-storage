package vn.lemar.selfstorage.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import vn.lemar.selfstorage.audit.application.AuditRecorder;
import vn.lemar.selfstorage.booking.application.DepositPaymentListener;
import vn.lemar.selfstorage.booking.domain.Invoice;
import vn.lemar.selfstorage.booking.domain.InvoiceStatus;
import vn.lemar.selfstorage.booking.domain.InvoiceType;
import vn.lemar.selfstorage.booking.domain.RentalOrder;
import vn.lemar.selfstorage.booking.domain.RentalOrderStatus;
import vn.lemar.selfstorage.booking.repository.InvoiceRepository;
import vn.lemar.selfstorage.booking.repository.RentalOrderRepository;
import vn.lemar.selfstorage.facility.application.StorageUnitQuery;
import vn.lemar.selfstorage.facility.application.StorageUnitService;
import vn.lemar.selfstorage.facility.application.StorageUnitView;
import vn.lemar.selfstorage.identity.application.AccountQueryService;
import vn.lemar.selfstorage.notification.application.NotificationRecipient;
import vn.lemar.selfstorage.notification.application.NotificationService;
import vn.lemar.selfstorage.notification.application.NotificationType;
import vn.lemar.selfstorage.payment.application.event.PaymentFailed;
import vn.lemar.selfstorage.payment.application.event.PaymentSucceeded;

/** Nhánh "Thành công" của spec Flow 1.4, và các ca cổng xác nhận về khi hoàn cảnh đã đổi. */
class DepositPaymentListenerTest {

    private static final Long INVOICE_ID = 100L;
    private static final Long ORDER_ID = 200L;
    private static final Long UNIT_ID = 300L;
    private static final Long CUSTOMER_ID = 7L;
    private static final String CUSTOMER_EMAIL = "customer1@lemar.vn";

    private final InvoiceRepository invoices = mock(InvoiceRepository.class);
    private final RentalOrderRepository orders = mock(RentalOrderRepository.class);
    private final StorageUnitQuery unitQuery = mock(StorageUnitQuery.class);
    private final StorageUnitService units = mock(StorageUnitService.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final AccountQueryService accounts = mock(AccountQueryService.class);
    private final AuditRecorder audit = mock(AuditRecorder.class);

    private DepositPaymentListener listener;
    private Invoice invoice;
    private RentalOrder order;

    @BeforeEach
    void setUp() {
        listener = new DepositPaymentListener(invoices, orders, unitQuery, units, notifications,
                accounts, audit);

        invoice = mock(Invoice.class);
        when(invoice.getId()).thenReturn(INVOICE_ID);
        when(invoice.getOrderId()).thenReturn(ORDER_ID);
        when(invoice.getCustomerId()).thenReturn(CUSTOMER_ID);
        when(invoice.getStatus()).thenReturn(InvoiceStatus.UNPAID);
        when(invoice.getType()).thenReturn(InvoiceType.DEPOSIT);

        order = mock(RentalOrder.class);
        when(order.getId()).thenReturn(ORDER_ID);
        when(order.getUnitId()).thenReturn(UNIT_ID);
        when(order.getStatus()).thenReturn(RentalOrderStatus.PENDING);

        when(invoices.findOrderIdById(INVOICE_ID)).thenReturn(Optional.of(ORDER_ID));
        when(invoices.findByIdForUpdate(INVOICE_ID)).thenReturn(Optional.of(invoice));
        when(orders.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
        when(units.reserveIfAvailable(UNIT_ID)).thenReturn(true);
        when(unitQuery.find(UNIT_ID)).thenReturn(Optional.of(
                new StorageUnitView(UNIT_ID, "A-03", 1L, 1L, "Reserved")));
        when(accounts.requireEmailById(CUSTOMER_ID)).thenReturn(CUSTOMER_EMAIL);
    }

    private PaymentSucceeded succeeded() {
        return new PaymentSucceeded(INVOICE_ID, "INV100T20261025120000ABCDE",
                new BigDecimal("10000.00"), Instant.now());
    }

    @Test
    void successMarksInvoicePaidDepositsOrderAndReservesUnit() {
        listener.onPaymentSucceeded(succeeded());

        verify(invoice).markPaid();
        verify(order).markDeposited();
        verify(units).reserveIfAvailable(UNIT_ID);
    }

    /** Hạn đơn set một lần khi tạo đơn (`db-table-draft.md` › RentalOrder) — cọc không gia hạn. */
    @Test
    void depositDoesNotMoveTheOrderDeadline() {
        listener.onPaymentSucceeded(succeeded());

        verify(order).markDeposited();
        verify(order, never()).getExpiresAt();
    }

    @Test
    void successWritesOneAuditEntryPerChangedEntity() {
        listener.onPaymentSucceeded(succeeded());

        // RentalOrder + StorageUnit; PaymentTransaction do module payment tự ghi.
        verify(audit, times(2)).record(any());
    }

    @Test
    void successNotifiesTheCustomerWithAnEmailAddress() {
        listener.onPaymentSucceeded(succeeded());

        verify(notifications).notify(eq(CUSTOMER_ID), eq(CUSTOMER_EMAIL), any(),
                eq(NotificationType.DEPOSIT_PAYMENT_SUCCEEDED), anyMap(), eq(ORDER_ID));
    }

    /**
     * VNPay retry tới 10 lần × 5 phút, nên IPN có thể về sau khi khoang đã thuộc về khách khác.
     * Đơn thứ hai không được chuyển {@code Deposited} — tiền phải hoàn tay.
     */
    @Test
    void lateConfirmationOnATakenUnitDoesNotDepositTheOrder() {
        when(units.reserveIfAvailable(UNIT_ID)).thenReturn(false);
        when(unitQuery.lock(UNIT_ID)).thenReturn(
                Optional.of(new StorageUnitView(UNIT_ID, "A-01", 400L, 1L, "Reserved")));

        listener.onPaymentSucceeded(succeeded());

        verify(order, never()).markDeposited();
        verify(invoice, never()).markPaid();
        verifyNoInteractions(audit);
        verifyNoInteractions(notifications);
    }

    @Test
    void repeatedConfirmationOnAPaidInvoiceChangesNothing() {
        when(invoice.getStatus()).thenReturn(InvoiceStatus.PAID);

        listener.onPaymentSucceeded(succeeded());

        verify(invoice, never()).markPaid();
        verifyNoInteractions(units);
        verifyNoInteractions(audit);
    }

    /** Tiền vào một hoá đơn đã huỷ là tình huống phải có người xử lý, không phải chuyện thường. */
    @Test
    void confirmationOnACanceledInvoiceIsNotSilentlyIgnored() {
        when(invoice.getStatus()).thenReturn(InvoiceStatus.CANCELED);

        listener.onPaymentSucceeded(succeeded());

        verify(invoice, never()).markPaid();
        verifyNoInteractions(units);
    }

    /**
     * Hoá đơn thuê/gia hạn thuộc luồng khác. Row đơn vẫn bị khoá trước (thứ tự khoá phải khớp B3)
     * nhưng không được đổi gì.
     */
    @Test
    void nonDepositInvoiceChangesNothing() {
        when(invoice.getType()).thenReturn(InvoiceType.RENTAL);

        listener.onPaymentSucceeded(succeeded());

        verify(order, never()).markDeposited();
        verify(invoice, never()).markPaid();
        verifyNoInteractions(units);
        verifyNoInteractions(audit);
    }

    @Test
    void missingInvoiceDoesNotThrowSoTheIpnStaysConfirmed() {
        when(invoices.findOrderIdById(INVOICE_ID)).thenReturn(Optional.empty());

        listener.onPaymentSucceeded(succeeded());

        verifyNoInteractions(audit);
    }

    @Test
    void failureChangesNothingBecauseTheLockExpiresWithTheTransaction() {
        listener.onPaymentFailed(new PaymentFailed(INVOICE_ID, "INV100T1", new BigDecimal("10000.00"),
                "vnp_ResponseCode=24"));

        verifyNoInteractions(invoices);
        verifyNoInteractions(units);
        verifyNoInteractions(audit);
    }

    /**
     * Đổi khoang sau khi đã cọc (#172): đơn đã `Deposited` từ lần cọc đầu. Thu cọc bổ sung chỉ
     * giữ khoang mới — không chuyển trạng thái đơn lần hai, nhưng gửi xác nhận riêng cho khách.
     */
    @Test
    void supplementReservesTheNewUnitAndNotifiesCustomer() {
        when(invoice.getType()).thenReturn(InvoiceType.DEPOSIT_SUPPLEMENT);

        listener.onPaymentSucceeded(succeeded());

        verify(invoice).markPaid();
        verify(units).reserveIfAvailable(UNIT_ID);
        verify(order, never()).markDeposited();
        verify(notifications).notify(eq(CUSTOMER_ID), eq(CUSTOMER_EMAIL),
                eq(NotificationRecipient.CUSTOMER),
                eq(NotificationType.DEPOSIT_SUPPLEMENT_PAYMENT_SUCCEEDED),
                eq(Map.of("orderCode", String.valueOf(ORDER_ID), "unitCode", "A-03")),
                eq(ORDER_ID));
        verify(audit, times(1)).record(any());
    }

    /**
     * B3 khoá row đơn ở mọi đường ghi. B4 phải khoá cùng thứ tự — đơn trước, hoá đơn sau — nếu
     * không thì hoặc deadlock, hoặc B3 đọc được hoá đơn nửa chừng.
     */
    @Test
    void theOrderRowIsLockedBeforeTheInvoice() {
        listener.onPaymentSucceeded(succeeded());

        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(orders, invoices);
        inOrder.verify(orders).findByIdForUpdate(ORDER_ID);
        inOrder.verify(invoices).findByIdForUpdate(INVOICE_ID);
    }

    /**
     * Hai annotation này là điều kiện sống còn: thiếu AFTER_COMMIT thì listener đọc state chưa
     * commit và exception của nó rollback luôn giao dịch IPN; thiếu REQUIRES_NEW thì AuditRecorder
     * và API facility (đều propagation MANDATORY) ném lỗi vì không có transaction.
     */
    @Test
    void successListenerRunsAfterCommitInItsOwnTransaction() throws Exception {
        Method method = DepositPaymentListener.class
                .getMethod("onPaymentSucceeded", PaymentSucceeded.class);

        assertThat(method.getAnnotation(TransactionalEventListener.class).phase())
                .isEqualTo(TransactionPhase.AFTER_COMMIT);
        assertThat(method.getAnnotation(Transactional.class).propagation())
                .isEqualTo(Propagation.REQUIRES_NEW);
    }

    @Test
    void customerEmailIsResolvedThroughIdentityNotGuessed() {
        listener.onPaymentSucceeded(succeeded());

        verify(accounts).requireEmailById(anyLong());
    }
}
