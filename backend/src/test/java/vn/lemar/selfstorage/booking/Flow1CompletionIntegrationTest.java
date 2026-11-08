package vn.lemar.selfstorage.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.lemar.selfstorage.booking.application.AppointmentBookingService;
import vn.lemar.selfstorage.booking.application.AppointmentBookingService.BookedAppointment;
import vn.lemar.selfstorage.booking.application.AppointmentBookingService.Slot;
import vn.lemar.selfstorage.booking.application.AppointmentBookingService.SlotWindow;
import vn.lemar.selfstorage.booking.application.BookingExpiryService;
import vn.lemar.selfstorage.booking.application.InvoicePaymentService;
import vn.lemar.selfstorage.booking.application.InvoicePaymentService.PayResult;
import vn.lemar.selfstorage.booking.application.OrderOverviewQueryService;
import vn.lemar.selfstorage.booking.application.ProposalService;
import vn.lemar.selfstorage.booking.application.OrderOverviewQueryService.OrderOverview;
import vn.lemar.selfstorage.booking.application.RentalOrderCancelService;
import vn.lemar.selfstorage.booking.application.exception.BookingConflictException;
import vn.lemar.selfstorage.booking.application.exception.BookingNotFoundException;
import vn.lemar.selfstorage.booking.application.exception.BookingValidationException;
import vn.lemar.selfstorage.identity.application.exception.ForbiddenException;
import vn.lemar.selfstorage.payment.application.PaymentGateway;
import vn.lemar.selfstorage.payment.application.PaymentService;
import vn.lemar.selfstorage.payment.application.dto.GatewayNotification;
import vn.lemar.selfstorage.payment.application.dto.GatewayQueryResult;

/**
 * Hoàn thiện Flow 1 chạy thật trên Postgres: thanh toán cọc (5 nhánh spec 1.4) → IPN / querydr chốt cọc → đặt lịch
 * check-in (khung cố định, mở HandoverRecord) → huỷ đơn (khách / FM, cascade sang handover) → cron Flow 1 → tổng
 * quan đơn. Cổng VNPay mock — chữ ký đã có {@code VnPayGatewayTest}.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class Flow1CompletionIntegrationTest {

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter PAY_DATE = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final BigDecimal AMOUNT = new BigDecimal("500000.00");
    private static final AtomicInteger SEQ = new AtomicInteger();

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @MockBean
    private PaymentGateway gateway;
    @MockBean
    private JavaMailSender mailSender;

    @Autowired
    private InvoicePaymentService invoicePaymentService;
    @Autowired
    private PaymentService paymentService;
    @Autowired
    private AppointmentBookingService bookingService;
    @Autowired
    private RentalOrderCancelService cancelService;
    @Autowired
    private BookingExpiryService expiryService;
    @Autowired
    private OrderOverviewQueryService overviewQuery;
    @Autowired
    private ProposalService proposalService;
    @Autowired
    private JdbcTemplate jdbc;

    private long customerId;
    private long facilityId;

    @BeforeEach
    void setUp() {
        customerId = id("select id from accounts where email = 'customer1@lemar.vn'");
        facilityId = id("select id from facilities where code = 'Q7'");
        when(gateway.buildPaymentUrl(any())).thenReturn("https://sandbox.vnpayment.vn/pay?x=1");
        // Mock interface trả null cho default method — dùng mã giao dịch mặc định thật.
        when(gateway.newTxnRef(any())).thenCallRealMethod();
        when(gateway.verifySignature(anyMap())).thenReturn(true);
        when(mailSender.createMimeMessage()).thenReturn(
                new jakarta.mail.internet.MimeMessage(jakarta.mail.Session.getInstance(new java.util.Properties())));
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------------ 1.4 thanh toán cọc

    @Test
    void payCreatesPendingTransactionAndReturnsGatewayUrl() {
        Seed seed = seedPendingOrder();

        PayResult result = invoicePaymentService.start(seed.invoiceId, customerId, "127.0.0.1");

        assertThat(result.rejected()).isFalse();
        assertThat(result.paymentUrl()).startsWith("https://sandbox.vnpayment.vn/pay");
        assertThat(count("select count(*) from payment_transactions where invoice_id = ? and status = 'Pending'",
                seed.invoiceId)).isEqualTo(1);
    }

    @Test
    void secondClickIsRejectedWhenGatewayCannotCloseOldSession() {
        Seed seed = seedPendingOrder();
        invoicePaymentService.start(seed.invoiceId, customerId, "127.0.0.1");
        when(gateway.query(anyString(), any(), any(), any())).thenReturn(pendingAtGateway());
        when(gateway.cancel(anyString(), anyString())).thenReturn(false);

        PayResult again = invoicePaymentService.start(seed.invoiceId, customerId, "127.0.0.1");

        assertThat(again.rejected()).isTrue();
        assertThat(again.rejectionMessage()).contains("phiên thanh toán chưa kết thúc");
        assertThat(count("select count(*) from payment_transactions where invoice_id = ?", seed.invoiceId))
                .isEqualTo(1);
    }

    @Test
    void secondClickCancelsOldSessionAtGatewayAndOpensNewOne() {
        Seed seed = seedPendingOrder();
        invoicePaymentService.start(seed.invoiceId, customerId, "127.0.0.1");
        String oldRef = txnRef(seed.invoiceId);
        when(gateway.query(anyString(), any(), any(), any())).thenReturn(pendingAtGateway());
        when(gateway.cancel(eq(oldRef), anyString())).thenReturn(true);

        PayResult again = invoicePaymentService.start(seed.invoiceId, customerId, "127.0.0.1");

        assertThat(again.rejected()).isFalse();
        assertThat(jdbc.queryForObject("select status from payment_transactions where vnp_txn_ref = ?", String.class,
                oldRef)).isEqualTo("Failed");
        assertThat(count("select count(*) from payment_transactions where invoice_id = ? and status = 'Pending'",
                seed.invoiceId)).isEqualTo(1);
        assertThat(count("select count(*) from audit_logs where action = 'PAYMENT_FAILED' "
                + "and new_value ->> 'txnRef' = ?", oldRef)).isEqualTo(1);
    }

    @Test
    void depositOnUnitBlocksCompetingInvoicesImmediately() {
        Seed winner = seedPendingOrder();
        Seed loser = seedPendingOrderOnUnit(winner.unitId);

        deposit(winner);

        assertThat(unitStatus(winner.unitId)).isEqualTo("Reserved");
        assertThat(invoiceStatus(loser.invoiceId)).isEqualTo("Canceled");
        assertThat(orderStatus(loser.orderId)).isEqualTo("Pending");
        assertThat(count("select count(*) from audit_logs where action = 'INVOICE_CANCELED' and entity_id = ? "
                + "and reason = 'Khoang đã có người đặt cọc trước'", String.valueOf(loser.invoiceId))).isEqualTo(1);
        assertThat(notificationsFor("customer1@lemar.vn", "PROPOSAL_UNIT_TAKEN")).isPositive();
        PayResult late = null;
        try {
            late = invoicePaymentService.start(loser.invoiceId, customerId, "127.0.0.1");
        } catch (BookingConflictException expected) {
            // hoá đơn đã Canceled → không còn thanh toán được
        }
        assertThat(late).isNull();
    }

    @Test
    void cancelingOrderClosesOpenPaymentSessionAfterCommit() {
        Seed seed = seedPendingOrder();
        invoicePaymentService.start(seed.invoiceId, customerId, "127.0.0.1");
        String ref = txnRef(seed.invoiceId);
        when(gateway.cancel(eq(ref), anyString())).thenReturn(true);

        cancelService.cancelByCustomer(seed.orderId, customerId);

        verify(gateway).cancel(eq(ref), anyString());
        assertThat(jdbc.queryForObject("select status from payment_transactions where vnp_txn_ref = ?", String.class,
                ref)).isEqualTo("Failed");
        assertThat(invoiceStatus(seed.invoiceId)).isEqualTo("Canceled");
    }

    @Test
    void lateWebhookOnClosedSessionRecordsMoneyAndAsksFmToRefund() {
        Seed seed = seedPendingOrder();
        invoicePaymentService.start(seed.invoiceId, customerId, "127.0.0.1");
        String ref = txnRef(seed.invoiceId);
        when(gateway.cancel(eq(ref), anyString())).thenReturn(true);
        cancelService.cancelByCustomer(seed.orderId, customerId);
        int refundNotices = notificationsFor("fm1@lemar.vn", "DEPOSIT_REFUND_REQUIRED");

        // Khách vẫn quét QR cũ sau khi link đã huỷ — tiền đã vào.
        when(gateway.parseNotification(anyString())).thenReturn(java.util.Optional.of(new GatewayNotification(ref,
                AMOUNT, "FT" + SEQ.incrementAndGet(), Instant.now(), true, "{}")));
        assertThat(paymentService.handleNotification("{}"))
                .isEqualTo(PaymentService.NotificationResult.ALREADY_SETTLED);

        assertThat(jdbc.queryForObject("select status from payment_transactions where vnp_txn_ref = ?", String.class,
                ref)).isEqualTo("Success");
        assertThat(invoiceStatus(seed.invoiceId)).isEqualTo("Canceled");
        assertThat(paymentService.refundable(seed.invoiceId)).isEqualByComparingTo(AMOUNT);
        assertThat(notificationsFor("fm1@lemar.vn", "DEPOSIT_REFUND_REQUIRED")).isEqualTo(refundNotices + 1);
        // Gọi lặp không ghi nhận lần hai.
        assertThat(paymentService.handleNotification("{}"))
                .isEqualTo(PaymentService.NotificationResult.ALREADY_SETTLED);
        assertThat(notificationsFor("fm1@lemar.vn", "DEPOSIT_REFUND_REQUIRED")).isEqualTo(refundNotices + 1);
    }

    @Test
    void webhookNotificationSettlesDepositOnce() {
        Seed seed = seedPendingOrder();
        invoicePaymentService.start(seed.invoiceId, customerId, "127.0.0.1");
        String ref = txnRef(seed.invoiceId);
        when(gateway.parseNotification(anyString())).thenReturn(java.util.Optional.of(new GatewayNotification(ref,
                AMOUNT, "FT" + SEQ.incrementAndGet(), Instant.now(), true, "{}")));

        assertThat(paymentService.handleNotification("{}")).isEqualTo(PaymentService.NotificationResult.ACCEPTED);
        assertThat(paymentService.handleNotification("{}"))
                .isEqualTo(PaymentService.NotificationResult.ALREADY_SETTLED);
        assertThat(invoiceStatus(seed.invoiceId)).isEqualTo("Paid");
        assertThat(orderStatus(seed.orderId)).isEqualTo("Deposited");

        when(gateway.parseNotification(anyString())).thenReturn(java.util.Optional.empty());
        assertThat(paymentService.handleNotification("x"))
                .isEqualTo(PaymentService.NotificationResult.INVALID_SIGNATURE);
        when(gateway.parseNotification(anyString())).thenReturn(java.util.Optional.of(new GatewayNotification("999",
                AMOUNT, "FT", Instant.now(), true, "{}")));
        assertThat(paymentService.handleNotification("{}")).isEqualTo(PaymentService.NotificationResult.NOT_FOUND);
    }

    @Test
    void anotherCustomersOpenSessionOnSameUnitBlocksPayment() {
        Seed first = seedPendingOrder();
        Seed second = seedPendingOrderOnUnit(first.unitId);
        invoicePaymentService.start(first.invoiceId, customerId, "127.0.0.1");

        PayResult blocked = invoicePaymentService.start(second.invoiceId, customerId, "127.0.0.1");

        assertThat(blocked.rejected()).isTrue();
        assertThat(blocked.rejectionMessage()).contains("đang trong quá trình xử lý thanh toán");
        assertThat(invoiceStatus(second.invoiceId)).isEqualTo("Unpaid");
    }

    @Test
    void reservedUnitCancelsInvoiceAndAsksFmToRepropose() {
        Seed seed = seedPendingOrder();
        jdbc.update("update storage_units set status = 'Reserved' where id = ?", seed.unitId);

        PayResult result = invoicePaymentService.start(seed.invoiceId, customerId, "127.0.0.1");

        assertThat(result.rejected()).isTrue();
        assertThat(result.invoiceCanceled()).isTrue();
        assertThat(result.rejectionMessage()).contains("đã được đặt cọc bởi khách hàng khác");
        assertThat(invoiceStatus(seed.invoiceId)).isEqualTo("Canceled");
        assertThat(orderStatus(seed.orderId)).isEqualTo("Pending");
        assertThat(notificationsFor("fm1@lemar.vn", "PROPOSAL_REPROPOSAL_REQUIRED")).isPositive();
    }

    @Test
    void maintenanceUnitCancelsInvoiceWithMaintenanceMessage() {
        Seed seed = seedPendingOrder();
        jdbc.update("update storage_units set status = 'Maintenance' where id = ?", seed.unitId);

        PayResult result = invoicePaymentService.start(seed.invoiceId, customerId, "127.0.0.1");

        assertThat(result.rejectionMessage()).contains("hiện không khả dụng");
        assertThat(invoiceStatus(seed.invoiceId)).isEqualTo("Canceled");
    }

    @Test
    void expiredOrderRejectsWithoutChangingInvoice() {
        Seed seed = seedPendingOrder();
        jdbc.update("update rental_orders set expires_at = now() - interval '1 hour' where id = ?", seed.orderId);

        PayResult result = invoicePaymentService.start(seed.invoiceId, customerId, "127.0.0.1");

        assertThat(result.rejectionMessage()).contains("Đơn thuê kho đã hết hạn");
        assertThat(invoiceStatus(seed.invoiceId)).isEqualTo("Unpaid");
    }

    @Test
    void otherCustomersInvoiceIsNotFound() {
        Seed seed = seedPendingOrder();
        long other = id("select id from accounts where email = 'fs1@lemar.vn'");

        assertThatThrownBy(() -> invoicePaymentService.start(seed.invoiceId, other, "127.0.0.1"))
                .isInstanceOf(BookingNotFoundException.class);
    }

    @Test
    void paidInvoiceCannotBePaidAgain() {
        Seed seed = seedPendingOrder();
        deposit(seed);

        assertThatThrownBy(() -> invoicePaymentService.start(seed.invoiceId, customerId, "127.0.0.1"))
                .isInstanceOf(BookingConflictException.class);
    }

    @Test
    void ipnSuccessDepositsOrderReservesUnitAndNotifiesCustomer() {
        Seed seed = seedPendingOrder();

        deposit(seed);

        assertThat(invoiceStatus(seed.invoiceId)).isEqualTo("Paid");
        assertThat(orderStatus(seed.orderId)).isEqualTo("Deposited");
        assertThat(unitStatus(seed.unitId)).isEqualTo("Reserved");
        // Khớp từng action với đúng entity của nó — id các bảng có thể trùng nhau giữa các test.
        assertThat(count("select count(*) from audit_logs where (action = 'INVOICE_PAID' and entity_id = ?) "
                + "or (action = 'RENTAL_ORDER_DEPOSITED' and entity_id = ?) "
                + "or (action = 'STORAGE_UNIT_RESERVED' and entity_id = ? and new_value ->> 'orderId' = ?) "
                + "or (action = 'PAYMENT_SUCCEEDED' and new_value ->> 'invoiceId' = ?)",
                String.valueOf(seed.invoiceId), String.valueOf(seed.orderId), String.valueOf(seed.unitId),
                String.valueOf(seed.orderId), String.valueOf(seed.invoiceId))).isEqualTo(4);
        assertThat(notificationsFor("customer1@lemar.vn", "DEPOSIT_PAYMENT_SUCCEEDED")).isPositive();
    }

    @Test
    void querydrConfirmationSettlesInvoiceWhenIpnNeverArrives() {
        Seed seed = seedPendingOrder();
        invoicePaymentService.start(seed.invoiceId, customerId, "127.0.0.1");
        String txnRef = txnRef(seed.invoiceId);
        when(gateway.query(anyString(), any(), any(), any())).thenReturn(new GatewayQueryResult("00", "00",
                "QDR" + SEQ.incrementAndGet(), AMOUNT, nowPayDate(), true, Map.of("vnp_TxnRef", txnRef)));

        InvoicePaymentService.PaymentCheck check = invoicePaymentService.check(seed.invoiceId, customerId, "127.0.0.1");

        assertThat(check.paymentStatus()).isEqualTo("Success");
        assertThat(check.invoiceStatus()).isEqualTo("Paid");
        assertThat(orderStatus(seed.orderId)).isEqualTo("Deposited");
    }

    @Test
    void querydrFailureMarksTransactionFailedAndKeepsInvoiceUnpaid() {
        Seed seed = seedPendingOrder();
        invoicePaymentService.start(seed.invoiceId, customerId, "127.0.0.1");
        when(gateway.query(anyString(), any(), any(), any())).thenReturn(
                new GatewayQueryResult("00", "02", null, AMOUNT, null, true, Map.of()));

        InvoicePaymentService.PaymentCheck check = invoicePaymentService.check(seed.invoiceId, customerId, "127.0.0.1");

        assertThat(check.paymentStatus()).isEqualTo("Failed");
        assertThat(check.invoiceStatus()).isEqualTo("Unpaid");
    }

    // ------------------------------------------------------------------ 1.5 đặt lịch check-in

    @Test
    void bookingCreatesAppointmentHandoverRecordAndSchedulesOrder() {
        Seed seed = seedPendingOrder();
        deposit(seed);

        SlotWindow window = bookingService.slots(seed.orderId, customerId);
        assertThat(window.days()).isNotEmpty();
        assertThat(window.days().get(0).slots()).hasSize(3);
        Slot slot = firstAvailable(window);
        BookedAppointment booked = bookingService.book(seed.orderId, customerId,
                slot.startAt().atZone(VN).toLocalDate(), slot.startAt());

        assertThat(booked.orderStatus()).isEqualTo("Scheduled");
        assertThat(orderStatus(seed.orderId)).isEqualTo("Scheduled");
        assertThat(count("select count(*) from handover_records where order_id = ? and result = 'IN_PROGRESS' "
                + "and appointment_id = ?", seed.orderId, booked.appointmentId())).isEqualTo(1);
        assertThat(count("select count(*) from rental_appointments where order_id = ?", seed.orderId)).isEqualTo(1);
        assertThat(notificationsFor("fm1@lemar.vn", "APPOINTMENT_CREATED")).isPositive();

        assertThatThrownBy(() -> bookingService.slots(seed.orderId, customerId))
                .isInstanceOf(BookingConflictException.class);
    }

    @Test
    void slotAlreadyTakenAtFacilityIsRejected() {
        Seed first = seedPendingOrder();
        Seed second = seedPendingOrder();
        deposit(first);
        deposit(second);
        Slot slot = firstAvailable(bookingService.slots(first.orderId, customerId));
        bookingService.book(first.orderId, customerId, slot.startAt().atZone(VN).toLocalDate(), slot.startAt());

        assertThatThrownBy(() -> bookingService.book(second.orderId, customerId,
                slot.startAt().atZone(VN).toLocalDate(), slot.startAt()))
                .isInstanceOf(BookingConflictException.class)
                .hasMessageContaining("Khung giờ đã có người đặt");
        boolean stillListed = bookingService.slots(second.orderId, customerId).days().stream()
                .flatMap(d -> d.slots().stream())
                .filter(s -> s.startAt().equals(slot.startAt()))
                .anyMatch(Slot::available);
        assertThat(stillListed).isFalse();
    }

    @Test
    void bookingValidatesSlotAndWindow() {
        Seed seed = seedPendingOrder();
        deposit(seed);
        SlotWindow window = bookingService.slots(seed.orderId, customerId);
        Slot slot = firstAvailable(window);

        assertThatThrownBy(() -> bookingService.book(seed.orderId, customerId,
                slot.startAt().atZone(VN).toLocalDate(), slot.startAt().plusSeconds(60)))
                .isInstanceOf(BookingValidationException.class);
        assertThatThrownBy(() -> bookingService.book(seed.orderId, customerId,
                window.maxDate().plusDays(1), slot.startAt()))
                .isInstanceOf(BookingValidationException.class);
        assertThatThrownBy(() -> bookingService.book(seed.orderId, customerId, null, null))
                .isInstanceOf(BookingValidationException.class);
    }

    @Test
    void bookingRequiresDepositedOrder() {
        Seed seed = seedPendingOrder();

        assertThatThrownBy(() -> bookingService.slots(seed.orderId, customerId))
                .isInstanceOf(BookingConflictException.class)
                .hasMessageContaining("chưa ở trạng thái chờ đặt lịch");
    }

    @Test
    void bookingWindowClosesAfterBookingWindowDays() {
        Seed seed = seedPendingOrder();
        deposit(seed);
        jdbc.update("update payment_transactions set paid_at = now() - interval '8 days' where invoice_id = ?",
                seed.invoiceId);

        assertThatThrownBy(() -> bookingService.slots(seed.orderId, customerId))
                .isInstanceOf(BookingConflictException.class)
                .hasMessageContaining("Đã quá hạn đặt lịch check-in");
    }

    @Test
    void bookingWindowRestartsWhenDepositedOrderAgreesToReplacementUnit() {
        Seed seed = seedPendingOrder();
        deposit(seed);
        jdbc.update("update payment_transactions set paid_at = now() - interval '8 days' where invoice_id = ?",
                seed.invoiceId);
        assertThat(bookingService.bookable(seed.orderId)).isFalse();

        // Khoang bị giữ trước → cơ sở đề xuất khoang khác, khách đồng ý hôm qua: cửa sổ tính lại từ lúc đồng ý.
        jdbc.update("insert into proposal_feedbacks (order_id, customer_id, unit_id, status, agreed_at) values "
                + "(?, ?, ?, 'Agreed', now() - interval '1 day')", seed.orderId, customerId, seed.unitId);

        assertThat(bookingService.bookable(seed.orderId)).isTrue();
        SlotWindow window = bookingService.slots(seed.orderId, customerId);
        assertThat(window.maxDate()).isAfterOrEqualTo(LocalDate.now(VN).plusDays(12));
    }

    @Test
    void rebookingAfterCanceledCheckinIgnoresBookingWindowAndActionFollowsIt() {
        Seed seed = seedPendingOrder();
        deposit(seed);
        jdbc.update("update payment_transactions set paid_at = now() - interval '8 days' where invoice_id = ?",
                seed.invoiceId);
        assertThat(bookingService.bookable(seed.orderId)).isFalse();
        assertThat(overviewQuery.detailForCustomer(seed.orderId, customerId).availableActions())
                .doesNotContain(OrderOverviewQueryService.ACTION_BOOK_CHECKIN);

        long canceled = id("insert into appointments (customer_id, facility_id, type, date, started_at, end_at, "
                + "status, cancel_reason) values (" + customerId + ", " + facilityId + ", 'CHECKIN', current_date - 1, "
                + "now() - interval '26 hours', now() - interval '24 hours', 'Canceled', 'Quá giờ và khách không đến') "
                + "returning id");
        jdbc.update("insert into rental_appointments (appointment_id, order_id) values (?, ?)", canceled,
                seed.orderId);

        assertThat(bookingService.bookable(seed.orderId)).isTrue();
        assertThat(bookingService.slots(seed.orderId, customerId).days()).isNotEmpty();
        assertThat(overviewQuery.detailForCustomer(seed.orderId, customerId).availableActions())
                .contains(OrderOverviewQueryService.ACTION_BOOK_CHECKIN);
    }

    @Test
    void reproposeCancelsDepositInvoiceAndClosesItsPaymentSession() {
        Seed seed = seedPendingOrder();
        invoicePaymentService.start(seed.invoiceId, customerId, "127.0.0.1");
        String ref = txnRef(seed.invoiceId);
        when(gateway.cancel(eq(ref), anyString())).thenReturn(true);
        long other = id("insert into storage_units (code, facility_id, unit_type_id, status) values ('RP-"
                + System.nanoTime() % 100000 + "', " + facilityId
                + ", (select id from unit_types where name = 'Small'), 'Available') returning id");
        loginAs("fm1@lemar.vn");

        long fmId = id("select id from accounts where email = 'fm1@lemar.vn'");
        proposalService.repropose(seed.orderId, other, null, fmId);

        verify(gateway).cancel(eq(ref), anyString());
        assertThat(invoiceStatus(seed.invoiceId)).isEqualTo("Canceled");
        assertThat(jdbc.queryForObject("select status from payment_transactions where vnp_txn_ref = ?", String.class,
                ref)).isEqualTo("Failed");
    }

    @Test
    void paidDepositOnTakenUnitBecomesDepositedOnAgreedReplacementWithoutSecondDeposit() {
        Seed seed = seedPendingOrder();
        // Khách mở phiên thanh toán, rồi khoang bị đơn khác giữ trước khi tiền cọc của đơn này về.
        invoicePaymentService.start(seed.invoiceId, customerId, "127.0.0.1");
        jdbc.update("update storage_units set status = 'Reserved' where id = ?", seed.unitId);
        Map<String, String> ipn = new HashMap<>();
        ipn.put("vnp_TxnRef", txnRef(seed.invoiceId));
        ipn.put("vnp_TmnCode", "TESTTMN1");
        ipn.put("vnp_Amount", "50000000");
        ipn.put("vnp_ResponseCode", "00");
        ipn.put("vnp_TransactionStatus", "00");
        ipn.put("vnp_TransactionNo", "IPN" + SEQ.incrementAndGet() + System.nanoTime() % 100000);
        ipn.put("vnp_PayDate", nowPayDate());
        ipn.put("vnp_SecureHash", "mocked");
        assertThat(paymentService.handleIpn(ipn).rspCode()).isEqualTo("00");
        assertThat(invoiceStatus(seed.invoiceId)).isEqualTo("Paid");
        assertThat(orderStatus(seed.orderId)).isEqualTo("Pending");

        long replacement = id("insert into storage_units (code, facility_id, unit_type_id, status) values ('RX-"
                + System.nanoTime() % 100000 + "', " + facilityId
                + ", (select id from unit_types where name = 'Small'), 'Available') returning id");
        long proposalId = id("insert into proposal_feedbacks (order_id, customer_id, unit_id, status) values ("
                + seed.orderId + ", " + customerId + ", " + replacement + ", 'Pending') returning id");
        loginAs("customer1@lemar.vn");

        proposalService.agree(proposalId, customerId);

        assertThat(orderStatus(seed.orderId)).isEqualTo("Deposited");
        assertThat(unitStatus(replacement)).isEqualTo("Reserved");
        assertThat(unitStatus(seed.unitId)).isEqualTo("Reserved");
        assertThat(count("select count(*) from invoices where order_id = ? and type = 'Deposit'", seed.orderId))
                .isEqualTo(1);
    }

    // ------------------------------------------------------------------ huỷ đơn

    @Test
    void customerCancelRunsCascadeIntoHandover() {
        Seed seed = seedPendingOrder();
        deposit(seed);
        Slot slot = firstAvailable(bookingService.slots(seed.orderId, customerId));
        BookedAppointment booked = bookingService.book(seed.orderId, customerId,
                slot.startAt().atZone(VN).toLocalDate(), slot.startAt());

        RentalOrderCancelService.CancelResult result = cancelService.cancelByCustomer(seed.orderId, customerId);

        assertThat(result.status()).isEqualTo("Canceled");
        assertThat(result.cancelReason()).isEqualTo("Khách hủy đơn");
        assertThat(unitStatus(seed.unitId)).isEqualTo("Available");
        assertThat(jdbc.queryForObject("select status from appointments where id = ?", String.class,
                booked.appointmentId())).isEqualTo("Canceled");
        assertThat(jdbc.queryForObject("select result from handover_records where appointment_id = ?", String.class,
                booked.appointmentId())).isEqualTo("CANCELED");
        assertThatThrownBy(() -> cancelService.cancelByCustomer(seed.orderId, customerId))
                .isInstanceOf(BookingConflictException.class);
    }

    @Test
    void facilityManagerCancelsOnlyOwnFacilityOrders() {
        Seed seed = seedPendingOrder();

        loginAs("fm2@lemar.vn");
        assertThatThrownBy(() -> cancelService.cancelByManager(seed.orderId, null,
                id("select id from accounts where email = 'fm2@lemar.vn'")))
                .isInstanceOf(ForbiddenException.class);

        loginAs("fm1@lemar.vn");
        RentalOrderCancelService.CancelResult result = cancelService.cancelByManager(seed.orderId, "  ",
                id("select id from accounts where email = 'fm1@lemar.vn'"));
        assertThat(result.cancelReason()).isEqualTo("Khách không còn nhu cầu");
        assertThat(invoiceStatus(seed.invoiceId)).isEqualTo("Canceled");
    }

    // ------------------------------------------------------------------ cron Flow 1

    @Test
    void overdueDepositInvoiceExpiresAndCancelsOrder() {
        Seed seed = seedPendingOrder();
        jdbc.update("update invoices set due_date = now() - interval '1 minute' where id = ?", seed.invoiceId);

        assertThat(expiryService.overdueInvoiceIds()).contains(seed.invoiceId);
        assertThat(expiryService.expireInvoice(seed.invoiceId)).isTrue();

        assertThat(invoiceStatus(seed.invoiceId)).isEqualTo("Expired");
        assertThat(orderStatus(seed.orderId)).isEqualTo("Canceled");
        assertThat(jdbc.queryForObject("select cancel_reason from rental_orders where id = ?", String.class,
                seed.orderId)).isEqualTo("Quá hạn thanh toán cọc");
        // Huỷ vì quá hạn → notification ghi "đã hết hạn".
        assertThat(jdbc.queryForObject("select n.title from notifications n join order_notifications o on "
                + "o.notification_id = n.id join accounts a on a.id = n.account_id where o.order_id = ? "
                + "and n.type = 'RENTAL_ORDER_CANCELED' and a.id = ?", String.class, seed.orderId, customerId))
                .isEqualTo("Đơn thuê kho đã hết hạn");
        assertThat(expiryService.expireInvoice(seed.invoiceId)).isFalse();
    }

    @Test
    void overdueInvoiceWaitsWhilePaymentSessionIsOpen() {
        Seed seed = seedPendingOrder();
        invoicePaymentService.start(seed.invoiceId, customerId, "127.0.0.1");
        jdbc.update("update invoices set due_date = now() - interval '1 minute' where id = ?", seed.invoiceId);

        assertThat(expiryService.expireInvoice(seed.invoiceId)).isFalse();
        assertThat(invoiceStatus(seed.invoiceId)).isEqualTo("Unpaid");
    }

    @Test
    void expiredRequestTurnsExpired() {
        long requestId = id("insert into rental_requests (facility_id, customer_name, normalized_customer_email, "
                + "customer_phone, unit_type_id, start_date, period, status, expires_at) values (" + facilityId
                + ", 'Khách', 'late@test.local', '0900000000', (select id from unit_types where name = 'Small'), "
                + "current_date, 1, 'Pending', now() - interval '1 day') returning id");

        assertThat(expiryService.expiredRequestIds()).contains(requestId);
        assertThat(expiryService.expireRequest(requestId)).isTrue();
        assertThat(jdbc.queryForObject("select status from rental_requests where id = ?", String.class, requestId))
                .isEqualTo("Expired");
        assertThat(expiryService.expireRequest(requestId)).isFalse();
    }

    @Test
    void expiredPendingOrderWithPaidDepositAsksFmToRefundNetDeposit() {
        Seed seed = seedPendingOrder();
        // Cọc đã thu nhưng khoang bị giữ trước → đơn ở lại Pending; FM đã hoàn 200.000 trước đó.
        jdbc.update("update invoices set status = 'Paid' where id = ?", seed.invoiceId);
        jdbc.update("insert into payment_transactions (invoice_id, vnp_txn_ref, amount, direction, status, paid_at) "
                + "values (?, ?, 200000, 'REFUND', 'Success', now())", seed.invoiceId, "RF-" + seed.invoiceId);
        jdbc.update("update rental_orders set expires_at = now() - interval '1 minute' where id = ?", seed.orderId);
        int refundNotices = notificationsFor("fm1@lemar.vn", "DEPOSIT_REFUND_REQUIRED");

        assertThat(expiryService.cancelExpiredOrder(seed.orderId)).isTrue();

        assertThat(jdbc.queryForObject("select cancel_reason from rental_orders where id = ?", String.class,
                seed.orderId)).isEqualTo("Hết hạn chờ xếp khoang sau khi đã cọc");
        assertThat(notificationsFor("fm1@lemar.vn", "DEPOSIT_REFUND_REQUIRED")).isEqualTo(refundNotices + 1);
        assertThat(jdbc.queryForObject("select (new_value ->> 'refundAmount')::numeric from audit_logs where action = "
                + "'DEPOSIT_REFUND_REQUIRED' and entity_id = ? order by id desc limit 1", BigDecimal.class,
                String.valueOf(seed.orderId))).isEqualByComparingTo("300000");
    }

    @Test
    void catchAllCancelsExpiredOrdersWithReasonByState() {
        Seed pendingProposal = seedPendingOrder();
        jdbc.update("update proposal_feedbacks set status = 'Pending' where order_id = ?", pendingProposal.orderId);
        Seed deposited = seedPendingOrder();
        deposit(deposited);
        jdbc.update("update rental_orders set expires_at = now() - interval '1 minute' where id in (?, ?)",
                pendingProposal.orderId, deposited.orderId);

        assertThat(expiryService.expiredOrderIds()).contains(pendingProposal.orderId, deposited.orderId);
        assertThat(expiryService.cancelExpiredOrder(pendingProposal.orderId)).isTrue();
        assertThat(expiryService.cancelExpiredOrder(deposited.orderId)).isTrue();

        assertThat(cancelReason(pendingProposal.orderId)).isEqualTo("Quá hạn xác nhận đề xuất");
        assertThat(cancelReason(deposited.orderId)).isEqualTo("Quá hạn đặt lịch check-in");
        assertThat(unitStatus(deposited.unitId)).isEqualTo("Available");
    }

    @Test
    void expiringOrderIsRemindedOnce() {
        Seed seed = seedPendingOrder();
        jdbc.update("update rental_orders set expires_at = now() + interval '2 days' where id = ?", seed.orderId);

        assertThat(expiryService.expiringOrderIds()).contains(seed.orderId);
        assertThat(expiryService.remindExpiring(seed.orderId)).isTrue();
        assertThat(expiryService.remindExpiring(seed.orderId)).isFalse();
    }

    // ------------------------------------------------------------------ tổng quan đơn

    @Test
    void overviewListsActionsForCustomerAndManager() {
        Seed seed = seedPendingOrder();

        OrderOverview mine = overviewQuery.listForCustomer(customerId).stream()
                .filter(o -> o.order().id().equals(seed.orderId)).findFirst().orElseThrow();
        assertThat(mine.availableActions()).contains(OrderOverviewQueryService.ACTION_PAY_INVOICE,
                OrderOverviewQueryService.ACTION_CANCEL);
        assertThat(mine.order().unitCode()).startsWith("T-");
        assertThat(overviewQuery.detailForCustomer(seed.orderId, customerId).order().invoiceStatus())
                .isEqualTo("Unpaid");

        loginAs("fm1@lemar.vn");
        List<OrderOverview> facilityOrders = overviewQuery.listForFacility(facilityId, "Pending");
        assertThat(facilityOrders).extracting(o -> o.order().id()).contains(seed.orderId);
        assertThatThrownBy(() -> overviewQuery.listForFacility(facilityId, "Nope"))
                .isInstanceOf(BookingValidationException.class);
    }

    // ------------------------------------------------------------------ helpers

    private record Seed(long unitId, long orderId, long invoiceId) {
    }

    private Seed seedPendingOrder() {
        int n = SEQ.incrementAndGet();
        long unitId = id("insert into storage_units (code, facility_id, unit_type_id, status) values ('T-" + n
                + "-" + System.nanoTime() % 100000 + "', " + facilityId
                + ", (select id from unit_types where name = 'Small'), 'Available') returning id");
        return seedPendingOrderOnUnit(unitId);
    }

    private Seed seedPendingOrderOnUnit(long unitId) {
        int n = SEQ.incrementAndGet();
        long requestId = id("insert into rental_requests (facility_id, customer_name, normalized_customer_email, "
                + "customer_phone, unit_type_id, start_date, period, status, unit_id) values (" + facilityId
                + ", 'Khách Test', 'customer1@lemar.vn', '0900000000', (select id from unit_types where name = "
                + "'Small'), current_date + 1, 1, 'Converted', " + unitId + ") returning id");
        long orderId = id("insert into rental_orders (request_id, customer_id, unit_id, status, expires_at) values ("
                + requestId + ", " + customerId + ", " + unitId + ", 'Pending', now() + interval '30 days') "
                + "returning id");
        jdbc.update("insert into proposal_feedbacks (order_id, customer_id, unit_id, status) values (?, ?, ?, "
                + "'Agreed')", orderId, customerId, unitId);
        long invoiceId = id("insert into invoices (order_id, customer_id, code, title, type, status, amount, due_date)"
                + " values (" + orderId + ", " + customerId + ", 'INV-DEP-Q7-T" + n + "-" + System.nanoTime() % 100000
                + "', 'Đặt cọc', 'Deposit', 'Unpaid', 500000, now() + interval '3 days') returning id");
        return new Seed(unitId, orderId, invoiceId);
    }

    /** Khách bấm thanh toán rồi IPN báo thành công (giao dịch commit → listener chốt cọc). */
    private void deposit(Seed seed) {
        invoicePaymentService.start(seed.invoiceId, customerId, "127.0.0.1");
        Map<String, String> ipn = new HashMap<>();
        ipn.put("vnp_TxnRef", txnRef(seed.invoiceId));
        ipn.put("vnp_TmnCode", "TESTTMN1");
        ipn.put("vnp_Amount", "50000000");
        ipn.put("vnp_ResponseCode", "00");
        ipn.put("vnp_TransactionStatus", "00");
        ipn.put("vnp_TransactionNo", "IPN" + SEQ.incrementAndGet() + System.nanoTime() % 100000);
        ipn.put("vnp_PayDate", nowPayDate());
        ipn.put("vnp_SecureHash", "mocked");
        assertThat(paymentService.handleIpn(ipn).rspCode()).isEqualTo("00");
    }

    private static GatewayQueryResult pendingAtGateway() {
        return new GatewayQueryResult("00", "01", null, AMOUNT, null, true, Map.of());
    }

    private static String nowPayDate() {
        return LocalDateTime.now(VN).format(PAY_DATE);
    }

    private static Slot firstAvailable(SlotWindow window) {
        return window.days().stream().flatMap(d -> d.slots().stream()).filter(Slot::available).findFirst()
                .orElseThrow(() -> new AssertionError("không còn khung trống"));
    }

    private String txnRef(long invoiceId) {
        return jdbc.queryForObject("select vnp_txn_ref from payment_transactions where invoice_id = ? "
                + "order by id desc limit 1", String.class, invoiceId);
    }

    private long id(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private String invoiceStatus(long invoiceId) {
        return jdbc.queryForObject("select status from invoices where id = ?", String.class, invoiceId);
    }

    private String orderStatus(long orderId) {
        return jdbc.queryForObject("select status from rental_orders where id = ?", String.class, orderId);
    }

    private String cancelReason(long orderId) {
        return jdbc.queryForObject("select cancel_reason from rental_orders where id = ?", String.class, orderId);
    }

    private String unitStatus(long unitId) {
        return jdbc.queryForObject("select status from storage_units where id = ?", String.class, unitId);
    }

    private int notificationsFor(String email, String type) {
        return count("select count(*) from notifications n join accounts a on a.id = n.account_id "
                + "where a.email = ? and n.type = ?", email, type);
    }

    private static void loginAs(String email) {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(email, null, List.of()));
    }
}
