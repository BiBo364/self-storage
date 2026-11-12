package vn.lemar.selfstorage.handover;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.lemar.selfstorage.booking.application.AppointmentBookingService;
import vn.lemar.selfstorage.booking.application.AppointmentNoShowService;
import vn.lemar.selfstorage.booking.application.BookingExpiryService;
import vn.lemar.selfstorage.booking.application.HandoverOrderService.ContractInvoice;
import vn.lemar.selfstorage.booking.application.InvoicePaymentService;
import vn.lemar.selfstorage.booking.application.RentalOrderCancelService;
import vn.lemar.selfstorage.booking.application.exception.BookingConflictException;
import vn.lemar.selfstorage.handover.application.ContractDocumentService;
import vn.lemar.selfstorage.handover.application.HandoverExpiryService;
import vn.lemar.selfstorage.handover.application.HandoverOverdueService;
import vn.lemar.selfstorage.handover.application.HandoverQueryService;
import vn.lemar.selfstorage.handover.application.HandoverWorkflowService;
import vn.lemar.selfstorage.handover.application.HandoverWorkflowService.ConfirmOutcome;
import vn.lemar.selfstorage.handover.application.HandoverWorkflowService.RejectOutcome;
import vn.lemar.selfstorage.handover.application.dto.HandoverView;
import vn.lemar.selfstorage.handover.application.exception.HandoverConflictException;
import vn.lemar.selfstorage.handover.application.exception.HandoverForbiddenException;
import vn.lemar.selfstorage.handover.application.exception.HandoverValidationException;
import vn.lemar.selfstorage.handover.domain.AccessChoice;
import vn.lemar.selfstorage.payment.application.PaymentGateway;
import vn.lemar.selfstorage.payment.application.PaymentService;

/**
 * Flow 2.2–2.5 chạy thật trên Postgres: xác minh → hiện trạng → hợp đồng → hoá đơn tháng đầu → xác nhận + giao
 * khoá → chốt bàn giao; nhánh từ chối / huỷ / quá hạn / no-show. Cổng thanh toán mock (IPN dạng VNPay).
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class HandoverFlowIntegrationTest {

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter PAY_DATE = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final String CUSTOMER = "customer1@lemar.vn";
    private static final String FS = "fs1@lemar.vn";
    private static final String FM = "fm1@lemar.vn";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @MockBean
    private PaymentGateway gateway;
    @MockBean
    private JavaMailSender mailSender;

    @Autowired
    private HandoverWorkflowService workflow;
    @Autowired
    private HandoverQueryService queryService;
    @Autowired
    private HandoverExpiryService handoverExpiry;
    @Autowired
    private HandoverOverdueService handoverOverdue;
    @Autowired
    private ContractDocumentService documentService;
    @Autowired
    private InvoicePaymentService invoicePaymentService;
    @Autowired
    private PaymentService paymentService;
    @Autowired
    private BookingExpiryService bookingExpiry;
    @Autowired
    private AppointmentNoShowService noShowService;
    @Autowired
    private AppointmentBookingService bookingService;
    @Autowired
    private RentalOrderCancelService cancelService;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JdbcTemplate jdbc;

    private long customerId;
    private long facilityId;
    private long fsId;

    @BeforeEach
    void setUp() {
        customerId = id("select id from accounts where email = '" + CUSTOMER + "'");
        fsId = id("select id from accounts where email = '" + FS + "'");
        facilityId = id("select id from facilities where code = 'Q7'");
        when(gateway.buildPaymentUrl(any())).thenReturn("https://pay.test/x");
        when(gateway.newTxnRef(any())).thenCallRealMethod();
        when(gateway.verifySignature(anyMap())).thenReturn(true);
        when(mailSender.createMimeMessage()).thenReturn(
                new jakarta.mail.internet.MimeMessage(jakarta.mail.Session.getInstance(new java.util.Properties())));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        jdbc.update("update policies set value = '2' where key = 'handover.max_rejection_count'");
        jdbc.update("update facilities set enabled_key_access = true, enabled_code_access = true");
    }

    // ------------------------------------------------------------------ happy path

    @Test
    void accessCodeChoiceCompletesHandoverInOneTransaction() {
        Seed seed = seedInProgress(fsId);

        long contractId = untilSigned(seed);
        ContractInvoice invoice = firstMonthInvoice(contractId);
        assertThat(invoice.code()).startsWith("INV-RNT-Q7-");
        assertThat(invoice.status()).isEqualTo("Unpaid");
        pay(invoice);

        loginAs(CUSTOMER);
        ConfirmOutcome outcome = workflow.confirm(seed.handoverId, customerId, AccessChoice.ACCESS_CODE);

        assertThat(outcome.completed()).isTrue();
        assertThat(outcome.accessCode()).matches("\\d{6}");
        assertThat(handoverResult(seed.handoverId)).isEqualTo("COMPLETED");
        assertThat(orderStatus(seed.orderId)).isEqualTo("Done");
        assertThat(unitStatus(seed.unitId)).isEqualTo("Rented");
        assertThat(contractStatus(contractId)).isEqualTo("Active");
        String hash = jdbc.queryForObject("select code_hash from unit_access_keys where contract_id = ? "
                + "and status = 'Active' and access_type = 'AccessCode'", String.class, contractId);
        assertThat(hash).isNotEqualTo(outcome.accessCode());
        assertThat(passwordEncoder.matches(outcome.accessCode(), hash)).isTrue();
        assertThat(auditCount("HANDOVER_COMPLETED", "HandoverRecord", seed.handoverId)).isEqualTo(1);
        assertThat(auditCount("STORAGE_UNIT_RENTED", "StorageUnit", seed.unitId)).isEqualTo(1);
        assertThat(auditCount("CONTRACT_ACTIVATED", "RentalContract", contractId)).isEqualTo(1);
        assertThat(auditCount("RENTAL_ORDER_DONE", "RentalOrder", seed.orderId)).isEqualTo(1);
        assertThat(notifications(CUSTOMER, "HANDOVER_COMPLETED", seed.orderId)).isEqualTo(1);

        // Lấy lại mã: mã cũ bị thu hồi, chỉ còn một mã Active.
        String rotated = workflow.rotateAccessCode(contractId, customerId);
        assertThat(rotated).matches("\\d{6}");
        assertThat(count("select count(*) from unit_access_keys where contract_id = ? and status = 'Active'",
                contractId)).isEqualTo(1);
        assertThat(count("select count(*) from unit_access_keys where contract_id = ? and status = 'Revoked'",
                contractId)).isEqualTo(1);

        // Chốt chỉ một lần.
        assertThatThrownBy(() -> workflow.confirm(seed.handoverId, customerId, AccessChoice.ACCESS_CODE))
                .isInstanceOf(HandoverConflictException.class);
    }

    @Test
    void physicalKeyChoiceWaitsForStaffToHandOverKeys() {
        Seed seed = seedInProgress(fsId);
        long contractId = untilSigned(seed);
        pay(firstMonthInvoice(contractId));

        loginAs(CUSTOMER);
        ConfirmOutcome outcome = workflow.confirm(seed.handoverId, customerId, AccessChoice.BOTH);
        assertThat(outcome.completed()).isFalse();
        assertThat(outcome.accessCode()).isNull();
        assertThat(handoverResult(seed.handoverId)).isEqualTo("IN_PROGRESS");
        assertThat(notifications(FS, "HANDOVER_KEY_REQUESTED", seed.orderId)).isEqualTo(1);
        assertThat(view(seed.handoverId).step()).isEqualTo("HAND_OVER_KEYS");

        loginAs(FS);
        workflow.handOverKeys(seed.handoverId, 2);

        assertThat(handoverResult(seed.handoverId)).isEqualTo("COMPLETED");
        assertThat(orderStatus(seed.orderId)).isEqualTo("Done");
        assertThat(jdbc.queryForObject("select quantity from unit_access_keys where contract_id = ? "
                + "and access_type = 'PhysicalKey'", Integer.class, contractId)).isEqualTo(2);
        assertThat(count("select count(*) from unit_access_keys where contract_id = ? and access_type = 'AccessCode'",
                contractId)).isEqualTo(1);
    }

    @Test
    void agreeingInspectionCreatesDraftContractFromPolicyAndDocumentRenders() {
        Seed seed = seedInProgress(fsId);
        loginAs(FS);
        workflow.verifyIdentity(seed.handoverId, true, null, idPhotos());
        // URL ảnh phải là upload của FS (media_uploads) — trước đây test dùng URL tuỳ ý "https://img.test/1.jpg".
        String photo = uploaded(fsId, "1.jpg");
        workflow.recordInspection(seed.handoverId, "Khoang sạch", List.of(photo));
        loginAs(CUSTOMER);
        workflow.agreeInspection(seed.handoverId, customerId, "Cửa hơi kẹt");

        HandoverView view = view(seed.handoverId);
        assertThat(view.step()).isEqualTo("SIGN_CONTRACT");
        assertThat(view.inspectionNotes()).contains("Khoang sạch").contains("Khách lưu ý: Cửa hơi kẹt");
        assertThat(view.inspectionPhotos()).containsExactly(photo);
        HandoverView.Contract contract = view.contract();
        assertThat(contract.status()).isEqualTo("Draft");
        assertThat(contract.code()).startsWith("HD-Q7-");
        assertThat(contract.termsVersion()).isEqualTo("builtin-v1");
        assertThat(contract.startDate()).isEqualTo(LocalDate.now(VN));
        assertThat(contract.endDate()).isEqualTo(LocalDate.now(VN).plusMonths(3).minusDays(1));
        assertThat(contract.depositAmount()).isEqualByComparingTo("500000");
        assertThat(contract.documentUrl()).isEqualTo("/api/contracts/" + contract.id() + "/document");
        assertThat(auditCount("UNIT_INSPECTED", "HandoverRecord", seed.handoverId)).isEqualTo(1);

        String html = documentService.render(contract.id());
        assertThat(html).contains(contract.code()).contains("Khách Demo").contains("3 tháng");
    }

    // ------------------------------------------------------------------ guards

    @Test
    void stepsMustRunInOrder() {
        Seed seed = seedInProgress(fsId);
        loginAs(FS);
        assertThatThrownBy(() -> workflow.recordInspection(seed.handoverId, "x", List.of()))
                .isInstanceOf(HandoverConflictException.class);
        workflow.verifyIdentity(seed.handoverId, false, "CCCD không khớp", List.of());
        assertThat(view(seed.handoverId).identityStatus()).isEqualTo("Failed");
        assertThat(handoverResult(seed.handoverId)).isEqualTo("IN_PROGRESS");
        workflow.verifyIdentity(seed.handoverId, true, null, idPhotos());
        assertThatThrownBy(() -> workflow.verifyIdentity(seed.handoverId, true, null, idPhotos()))
                .isInstanceOf(HandoverConflictException.class);

        // Khách chưa đồng ý được khi FS chưa ghi hiện trạng (mốc so sánh khi trả kho).
        loginAs(CUSTOMER);
        assertThatThrownBy(() -> workflow.agreeInspection(seed.handoverId, customerId, null))
                .isInstanceOf(HandoverConflictException.class)
                .hasMessageContaining("chưa ghi nhận hiện trạng");
        loginAs(FS);
        workflow.recordInspection(seed.handoverId, "Khoang sạch", List.of());
        loginAs(CUSTOMER);
        workflow.agreeInspection(seed.handoverId, customerId, null);
        long contractId = contractId(seed.orderId);
        assertThatThrownBy(() -> workflow.confirm(seed.handoverId, customerId, AccessChoice.ACCESS_CODE))
                .isInstanceOf(HandoverConflictException.class);

        loginAs(FS);
        workflow.signContract(contractId, uploaded(fsId, "signed.pdf"));
        loginAs(CUSTOMER);
        assertThatThrownBy(() -> workflow.confirm(seed.handoverId, customerId, AccessChoice.ACCESS_CODE))
                .isInstanceOf(HandoverConflictException.class)
                .hasMessageContaining("thanh toán hoá đơn tháng đầu");
        assertThatThrownBy(() -> workflow.reject(seed.handoverId, customerId, "Đổi ý", false))
                .isInstanceOf(HandoverConflictException.class)
                .hasMessageContaining("chỉ còn lựa chọn huỷ đơn");
    }

    @Test
    void onlyAssignedStaffCanAct() {
        long otherStaff = id("select id from accounts where email = '" + FM + "'");
        Seed seed = seedInProgress(otherStaff);
        loginAs(FS);
        assertThatThrownBy(() -> workflow.verifyIdentity(seed.handoverId, true, null, idPhotos()))
                .isInstanceOf(HandoverForbiddenException.class);
    }

    @Test
    void disabledAccessTypeIsRejected() {
        Seed seed = seedInProgress(fsId);
        long contractId = untilSigned(seed);
        pay(firstMonthInvoice(contractId));
        jdbc.update("update facilities set enabled_code_access = false where id = ?", facilityId);

        loginAs(CUSTOMER);
        assertThatThrownBy(() -> workflow.confirm(seed.handoverId, customerId, AccessChoice.ACCESS_CODE))
                .isInstanceOf(HandoverValidationException.class);
    }

    @Test
    void pendingStartDateOverrideBlocksSigningUntilManagerReviews() {
        Seed seed = seedInProgress(fsId);
        untilAgreed(seed);
        long contractId = contractId(seed.orderId);
        LocalDate requested = LocalDate.now(VN).plusDays(5);

        loginAs(FS);
        workflow.requestStartDateOverride(contractId, requested, "Khách chuyển đồ sau 5 ngày");
        assertThat(view(seed.handoverId).step()).isEqualTo("OVERRIDE_PENDING");
        String scan = uploaded(fsId, "s.pdf");
        assertThatThrownBy(() -> workflow.signContract(contractId, scan))
                .isInstanceOf(HandoverConflictException.class);
        assertThatThrownBy(() -> documentService.render(contractId)).isInstanceOf(HandoverConflictException.class);

        loginAs(FM);
        workflow.reviewStartDateOverride(contractId, true, "OK");
        assertThat(jdbc.queryForObject("select start_date from rental_contracts where id = ?", LocalDate.class,
                contractId)).isEqualTo(requested);
        assertThat(auditCount("START_DATE_OVERRIDE_REQUESTED", "RentalContract", contractId)).isEqualTo(1);
        assertThat(auditCount("START_DATE_OVERRIDE_APPROVED", "RentalContract", contractId)).isEqualTo(1);

        loginAs(FS);
        workflow.signContract(contractId, scan);
        assertThat(contractStatus(contractId)).isEqualTo("Signed");
    }

    // ------------------------------------------------------------------ 2.5 từ chối / huỷ

    @Test
    void rejectBelowLimitReturnsOrderToDepositedForReproposal() {
        Seed seed = seedInProgress(fsId);
        untilAgreed(seed);
        long contractId = contractId(seed.orderId);

        loginAs(CUSTOMER);
        RejectOutcome outcome = workflow.reject(seed.handoverId, customerId, "Khoang ẩm", false);

        assertThat(outcome.requiresConfirmation()).isFalse();
        assertThat(outcome.orderCanceled()).isFalse();
        assertThat(handoverResult(seed.handoverId)).isEqualTo("REJECTED");
        assertThat(jdbc.queryForObject("select inspection_status from handover_records where id = ?", String.class,
                seed.handoverId)).isEqualTo("Rejected");
        assertThat(orderStatus(seed.orderId)).isEqualTo("Deposited");
        assertThat(jdbc.queryForObject("select needs_reproposal from rental_orders where id = ?", Boolean.class,
                seed.orderId)).isTrue();
        assertThat(unitStatus(seed.unitId)).isEqualTo("Reserved");
        assertThat(contractStatus(contractId)).isEqualTo("Canceled");
        assertThat(notifications(FM, "HANDOVER_REJECTED", seed.orderId)).isEqualTo(1);
        assertThat(auditCount("HANDOVER_REJECTED", "HandoverRecord", seed.handoverId)).isEqualTo(1);

        // Chưa có khoang mới → chưa đặt lịch check-in được.
        assertThatThrownBy(() -> bookingService.slots(seed.orderId, customerId))
                .isInstanceOf(BookingConflictException.class)
                .hasMessageContaining("sắp xếp khoang khác");
    }

    @Test
    void rejectAtLimitNeedsConfirmationThenCancelsOrder() {
        jdbc.update("update policies set value = '1' where key = 'handover.max_rejection_count'");
        Seed seed = seedInProgress(fsId);
        untilAgreed(seed);

        loginAs(CUSTOMER);
        RejectOutcome ask = workflow.reject(seed.handoverId, customerId, "Không ưng", false);
        assertThat(ask.requiresConfirmation()).isTrue();
        assertThat(handoverResult(seed.handoverId)).isEqualTo("IN_PROGRESS");

        RejectOutcome done = workflow.reject(seed.handoverId, customerId, "Không ưng", true);
        assertThat(done.orderCanceled()).isTrue();
        assertThat(handoverResult(seed.handoverId)).isEqualTo("REJECTED");
        assertThat(orderStatus(seed.orderId)).isEqualTo("Canceled");
        assertThat(cancelReason(seed.orderId)).isEqualTo("Từ chối quá số khoang cho phép");
        assertThat(unitStatus(seed.unitId)).isEqualTo("Available");
    }

    @Test
    void customerNotSigningCancelsOrder() {
        Seed seed = seedInProgress(fsId);
        untilAgreed(seed);
        long contractId = contractId(seed.orderId);

        loginAs(FS);
        workflow.customerDidNotSign(seed.handoverId);

        assertThat(orderStatus(seed.orderId)).isEqualTo("Canceled");
        assertThat(cancelReason(seed.orderId)).isEqualTo("Khách không ký hợp đồng");
        assertThat(handoverResult(seed.handoverId)).isEqualTo("CANCELED");
        assertThat(contractStatus(contractId)).isEqualTo("Canceled");
    }

    @Test
    void cancelAfterFirstMonthPaidAsksManagerToRefund() {
        Seed seed = seedInProgress(fsId);
        long contractId = untilSigned(seed);
        ContractInvoice invoice = firstMonthInvoice(contractId);
        pay(invoice);

        cancelService.cancelByCustomer(seed.orderId, customerId);

        assertThat(contractStatus(contractId)).isEqualTo("Canceled");
        assertThat(handoverResult(seed.handoverId)).isEqualTo("CANCELED");
        assertThat(notifications(FM, "FIRST_MONTH_REFUND_REQUIRED", seed.orderId)).isEqualTo(1);
    }

    @Test
    void cancelBeforePayingCancelsFirstMonthInvoice() {
        Seed seed = seedInProgress(fsId);
        long contractId = untilSigned(seed);
        ContractInvoice invoice = firstMonthInvoice(contractId);

        cancelService.cancelByCustomer(seed.orderId, customerId);

        assertThat(invoiceStatus(invoice.invoiceId())).isEqualTo("Canceled");
        assertThat(contractStatus(contractId)).isEqualTo("Canceled");
    }

    // ------------------------------------------------------------------ cron

    @Test
    void overdueHandoverReturnsOrderToDepositedWhileOrderStillValid() {
        Seed seed = seedInProgress(fsId);
        long contractId = untilSigned(seed);
        ContractInvoice invoice = firstMonthInvoice(contractId);
        jdbc.update("update handover_records set due_at = now() - interval '1 minute' where id = ?",
                seed.handoverId);

        assertThat(handoverOverdue.findOverdueIds()).contains(seed.handoverId);
        assertThat(handoverOverdue.cancelIfOverdue(seed.handoverId)).isTrue();

        assertThat(handoverResult(seed.handoverId)).isEqualTo("CANCELED");
        assertThat(jdbc.queryForObject("select reject_reason from handover_records where id = ?", String.class,
                seed.handoverId)).isEqualTo("Chưa hoàn tất bàn giao trong hạn");
        assertThat(contractStatus(contractId)).isEqualTo("Canceled");
        assertThat(invoiceStatus(invoice.invoiceId())).isEqualTo("Canceled");
        assertThat(orderStatus(seed.orderId)).isEqualTo("Deposited");
        assertThat(unitStatus(seed.unitId)).isEqualTo("Reserved");
        assertThat(notifications(CUSTOMER, "HANDOVER_OVERDUE", seed.orderId)).isEqualTo(1);
        assertThat(handoverOverdue.cancelIfOverdue(seed.handoverId)).isFalse();
    }

    @Test
    void overdueHandoverOnExpiredOrderRunsCascade() {
        Seed seed = seedInProgress(fsId);
        jdbc.update("update handover_records set due_at = now() - interval '1 minute' where id = ?",
                seed.handoverId);
        jdbc.update("update rental_orders set expires_at = now() - interval '1 minute' where id = ?", seed.orderId);

        assertThat(handoverOverdue.cancelIfOverdue(seed.handoverId)).isTrue();

        assertThat(orderStatus(seed.orderId)).isEqualTo("Canceled");
        assertThat(cancelReason(seed.orderId)).isEqualTo("Chưa hoàn tất bàn giao trong hạn");
        assertThat(unitStatus(seed.unitId)).isEqualTo("Available");
    }

    @Test
    void expiredFirstMonthInvoiceCancelsSignedContract() {
        Seed seed = seedInProgress(fsId);
        long contractId = untilSigned(seed);
        ContractInvoice invoice = firstMonthInvoice(contractId);
        jdbc.update("update invoices set due_date = now() - interval '1 minute' where id = ?", invoice.invoiceId());

        assertThat(bookingExpiry.expireInvoice(invoice.invoiceId())).isTrue();

        assertThat(invoiceStatus(invoice.invoiceId())).isEqualTo("Expired");
        assertThat(contractStatus(contractId)).isEqualTo("Canceled");
        assertThat(handoverResult(seed.handoverId)).isEqualTo("CANCELED");
        assertThat(jdbc.queryForObject("select reject_reason from handover_records where id = ?", String.class,
                seed.handoverId)).isEqualTo("Quá hạn thanh toán tháng đầu");
        assertThat(orderStatus(seed.orderId)).isEqualTo("Deposited");
    }

    @Test
    void noShowClosesHandoverRecordAndReturnsOrder() {
        Seed seed = seedScheduledNoShow();

        assertThat(noShowService.cancelIfDue(seed.appointmentId)).isTrue();

        assertThat(handoverResult(seed.handoverId)).isEqualTo("CANCELED");
        assertThat(jdbc.queryForObject("select reject_reason from handover_records where id = ?", String.class,
                seed.handoverId)).isEqualTo("Khách không đến nhận kho theo lịch hẹn.");
        assertThat(orderStatus(seed.orderId)).isEqualTo("Deposited");
        assertThat(unitStatus(seed.unitId)).isEqualTo("Reserved");
    }

    @Test
    void noShowOnExpiredOrderRunsCascade() {
        Seed seed = seedScheduledNoShow();
        jdbc.update("update rental_orders set expires_at = now() - interval '1 minute' where id = ?", seed.orderId);

        assertThat(noShowService.cancelIfDue(seed.appointmentId)).isTrue();

        assertThat(orderStatus(seed.orderId)).isEqualTo("Canceled");
        assertThat(cancelReason(seed.orderId)).isEqualTo("Không đến nhận kho quá hạn giữ");
        assertThat(handoverResult(seed.handoverId)).isEqualTo("CANCELED");
    }

    // ------------------------------------------------------------------ validation / guard

    @Test
    void staffInputValidation() {
        Seed seed = seedInProgress(fsId);
        loginAs(FS);
        workflow.verifyIdentity(seed.handoverId, true, "CCCD khớp", idPhotos());
        List<String> tooMany = java.util.Collections.nCopies(21, "https://img.test/x.jpg");
        assertThatThrownBy(() -> workflow.recordInspection(seed.handoverId, "x", tooMany))
                .isInstanceOf(HandoverValidationException.class);
        assertThatThrownBy(() -> workflow.handOverKeys(seed.handoverId, 0))
                .isInstanceOf(HandoverValidationException.class);
        assertThatThrownBy(() -> workflow.handOverKeys(seed.handoverId, 11))
                .isInstanceOf(HandoverValidationException.class);
        assertThatThrownBy(() -> workflow.handOverKeys(seed.handoverId, 1))
                .isInstanceOf(HandoverConflictException.class);
        assertThatThrownBy(() -> workflow.customerDidNotSign(seed.handoverId))
                .isInstanceOf(HandoverConflictException.class);
        assertThatThrownBy(() -> workflow.signContract(1L, " ")).isInstanceOf(HandoverValidationException.class);
        assertThatThrownBy(() -> workflow.requestStartDateOverride(1L, null, "x"))
                .isInstanceOf(HandoverValidationException.class);
        assertThatThrownBy(() -> workflow.requestStartDateOverride(1L, LocalDate.now(), " "))
                .isInstanceOf(HandoverValidationException.class);

        loginAs(CUSTOMER);
        assertThatThrownBy(() -> workflow.agreeInspection(seed.handoverId, customerId, "x".repeat(1001)))
                .isInstanceOf(HandoverValidationException.class);
        loginAs(FS);
        workflow.recordInspection(seed.handoverId, "Khoang sạch", List.of());
        loginAs(CUSTOMER);
        workflow.agreeInspection(seed.handoverId, customerId, null);
        assertThatThrownBy(() -> workflow.agreeInspection(seed.handoverId, customerId, null))
                .isInstanceOf(HandoverConflictException.class);
        long contractId = contractId(seed.orderId);

        loginAs(FS);
        assertThatThrownBy(() -> workflow.recordInspection(seed.handoverId, "x", List.of()))
                .isInstanceOf(HandoverConflictException.class);
        assertThatThrownBy(() -> workflow.requestStartDateOverride(contractId, LocalDate.now(VN).minusDays(1), "x"))
                .isInstanceOf(HandoverValidationException.class);
        workflow.requestStartDateOverride(contractId, LocalDate.now(VN).plusDays(2), "Dời");
        assertThatThrownBy(() -> workflow.requestStartDateOverride(contractId, LocalDate.now(VN).plusDays(3), "x"))
                .isInstanceOf(HandoverConflictException.class);
        loginAs(FM);
        workflow.reviewStartDateOverride(contractId, false, null);
        assertThat(jdbc.queryForObject("select start_date_override_status from rental_contracts where id = ?",
                String.class, contractId)).isEqualTo("Rejected");
        assertThatThrownBy(() -> workflow.reviewStartDateOverride(contractId, true, null))
                .isInstanceOf(HandoverConflictException.class);

        loginAs(FS);
        String scan = uploaded(fsId, "s.pdf");
        workflow.signContract(contractId, scan);
        assertThatThrownBy(() -> workflow.signContract(contractId, scan))
                .isInstanceOf(HandoverConflictException.class);
        assertThatThrownBy(() -> workflow.requestStartDateOverride(contractId, LocalDate.now(VN), "x"))
                .isInstanceOf(HandoverConflictException.class);
        assertThatThrownBy(() -> workflow.customerDidNotSign(seed.handoverId))
                .isInstanceOf(HandoverConflictException.class);
    }

    @Test
    void customerInputValidation() {
        Seed seed = seedInProgress(fsId);
        long contractId = untilSigned(seed);
        loginAs(CUSTOMER);
        assertThatThrownBy(() -> workflow.reject(seed.handoverId, customerId, " ", false))
                .isInstanceOf(HandoverValidationException.class);
        assertThatThrownBy(() -> workflow.reject(seed.handoverId, customerId, "x".repeat(501), false))
                .isInstanceOf(HandoverValidationException.class);
        assertThatThrownBy(() -> workflow.confirm(seed.handoverId, customerId, null))
                .isInstanceOf(HandoverValidationException.class);
        assertThatThrownBy(() -> workflow.rotateAccessCode(contractId, customerId))
                .isInstanceOf(HandoverConflictException.class);
        assertThatThrownBy(() -> workflow.rotateAccessCode(contractId, customerId + 999))
                .isInstanceOf(vn.lemar.selfstorage.handover.application.exception.HandoverNotFoundException.class);
        assertThatThrownBy(() -> queryService.forCustomer(seed.handoverId, customerId + 999))
                .isInstanceOf(vn.lemar.selfstorage.handover.application.exception.HandoverNotFoundException.class);

        pay(firstMonthInvoice(contractId));
        loginAs(CUSTOMER);
        workflow.confirm(seed.handoverId, customerId, AccessChoice.PHYSICAL_KEY);
        assertThatThrownBy(() -> workflow.confirm(seed.handoverId, customerId, AccessChoice.PHYSICAL_KEY))
                .isInstanceOf(HandoverConflictException.class);
        loginAs(FS);
        workflow.handOverKeys(seed.handoverId, 1);
        jdbc.update("update facilities set enabled_code_access = false where id = ?", facilityId);
        assertThatThrownBy(() -> workflow.rotateAccessCode(contractId, customerId))
                .isInstanceOf(HandoverConflictException.class);
    }

    @Test
    void passingIdentityNeedsOneToFourUploadedDocumentPhotos() {
        Seed seed = seedInProgress(fsId);
        loginAs(FS);
        assertThatThrownBy(() -> workflow.verifyIdentity(seed.handoverId, true, null, List.of()))
                .isInstanceOf(HandoverValidationException.class)
                .hasMessageContaining("ít nhất một ảnh CCCD");
        assertThatThrownBy(() -> workflow.verifyIdentity(seed.handoverId, true, null, null))
                .isInstanceOf(HandoverValidationException.class);
        List<String> five = List.of(uploaded(fsId, "1.jpg"), uploaded(fsId, "2.jpg"), uploaded(fsId, "3.jpg"),
                uploaded(fsId, "4.jpg"), uploaded(fsId, "5.jpg"));
        assertThatThrownBy(() -> workflow.verifyIdentity(seed.handoverId, true, null, five))
                .isInstanceOf(HandoverValidationException.class)
                .hasMessageContaining("Tối đa 4");
        String foreign = uploaded(customerId, "cccd.jpg");
        assertThatThrownBy(() -> workflow.verifyIdentity(seed.handoverId, true, null, List.of(foreign)))
                .isInstanceOf(HandoverValidationException.class)
                .hasMessageContaining("Ảnh giấy tờ");
        assertThat(view(seed.handoverId).identityStatus()).isEqualTo("Pending");

        // Không đạt: ảnh tuỳ chọn. Đạt: ảnh lưu vào biên bản và upload Pending chuyển Attached ngay.
        workflow.verifyIdentity(seed.handoverId, false, "CCCD mờ", List.of());
        assertThat(view(seed.handoverId).identityStatus()).isEqualTo("Failed");
        String front = uploaded(fsId, "front.jpg", "Pending");
        String back = uploaded(fsId, "back.jpg", "Pending");
        workflow.verifyIdentity(seed.handoverId, true, null, List.of(front, back));

        HandoverView view = view(seed.handoverId);
        assertThat(view.identityStatus()).isEqualTo("Verified");
        assertThat(view.identityPhotos()).containsExactly(front, back);
        assertThat(jdbc.queryForObject("select count(*) from media_uploads where url in (?, ?) "
                + "and status = 'Attached'", Long.class, front, back)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("select new_value ->> 'photoCount' from audit_logs where action = "
                + "'IDENTITY_VERIFIED' and entity_id = ? order by id desc limit 1", String.class,
                String.valueOf(seed.handoverId))).isEqualTo("2");
    }

    @Test
    void queriesForStaffAndCustomer() {
        Seed seed = seedInProgress(fsId);
        loginAs(FS);
        assertThat(queryService.staffQueue()).extracting(HandoverView::id).contains(seed.handoverId);
        HandoverView view = queryService.forStaff(seed.handoverId);
        assertThat(view.step()).isEqualTo("VERIFY_IDENTITY");
        assertThat(view.keyAccessEnabled()).isTrue();
        assertThat(view.maxRejectionCount()).isEqualTo(2);
        assertThat(queryService.forCustomer(customerId)).extracting(HandoverView::id).contains(seed.handoverId);

        Seed waiting = seedScheduledNoShow();
        assertThat(queryService.view(waiting.handoverId).step()).isEqualTo("WAITING_ARRIVAL");
        assertThatThrownBy(() -> workflow.verifyIdentity(waiting.handoverId, true, null, idPhotos()))
                .isInstanceOf(HandoverConflictException.class);
        assertThatThrownBy(() -> queryService.view(-1L))
                .isInstanceOf(vn.lemar.selfstorage.handover.application.exception.HandoverNotFoundException.class);
    }

    @Test
    void expiryIgnoresRecordsThatAreNotDue() {
        Seed seed = seedInProgress(fsId);
        assertThat(handoverOverdue.cancelIfOverdue(seed.handoverId)).isFalse();
        assertThat(handoverOverdue.cancelIfOverdue(-1L)).isFalse();
        untilAgreed(seed);
        handoverExpiry.onFirstMonthInvoiceExpired(contractId(seed.orderId));
        assertThat(handoverResult(seed.handoverId)).isEqualTo("IN_PROGRESS");
        handoverExpiry.onFirstMonthInvoiceExpired(-1L);
        loginAs(CUSTOMER);
        workflow.reject(seed.handoverId, customerId, "Không ưng", false);
        assertThat(handoverOverdue.cancelIfOverdue(seed.handoverId)).isFalse();
    }

    // ------------------------------------------------------------------ bản hợp đồng chốt lúc ký

    @Test
    void contractDocumentRendersLiveBeforeSigningAndFrozenAfter() {
        Seed seed = seedInProgress(fsId);
        untilAgreed(seed);
        long contractId = contractId(seed.orderId);

        // Bản nháp render động: đổi dữ liệu là bản xem/in đổi theo.
        loginAs(FS);
        String draft = documentService.render(contractId);
        assertThat(draft).contains("Trạng thái Draft").contains("Khoang sạch, khoá tốt");
        assertThat(documentHtml(contractId)).isNull();
        jdbc.update("update handover_records set inspection_notes = 'Ghi chú trước khi ký' where id = ?",
                seed.handoverId);
        assertThat(documentService.render(contractId)).contains("Ghi chú trước khi ký");

        workflow.signContract(contractId, uploaded(fsId, "signed.pdf"));
        String frozen = documentHtml(contractId);
        assertThat(frozen).contains("Trạng thái Signed").contains("Ghi chú trước khi ký");

        // Sau khi ký: dữ liệu hiện tại đổi (hiện trạng, giá) không làm đổi bản đã ký.
        jdbc.update("update handover_records set inspection_notes = 'Đổi sau khi ký' where id = ?", seed.handoverId);
        jdbc.update("update rental_contracts set monthly_price = 999999 where id = ?", contractId);
        String reprinted = documentService.render(contractId);
        assertThat(reprinted).isEqualTo(frozen).doesNotContain("Đổi sau khi ký").doesNotContain("999.999");
        loginAs(CUSTOMER);
        assertThat(documentService.render(contractId)).isEqualTo(frozen);
    }

    // ------------------------------------------------------------------ sửa hiện trạng sau khi khách đồng ý

    @Test
    void staffCorrectsInspectionAfterAgreementWithReasonAndAudit() {
        Seed seed = seedInProgress(fsId);
        loginAs(FS);
        workflow.verifyIdentity(seed.handoverId, true, null, idPhotos());
        String first = uploaded(fsId, "c1.jpg");
        workflow.recordInspection(seed.handoverId, "Khoang sạch", List.of(first));
        loginAs(CUSTOMER);
        workflow.agreeInspection(seed.handoverId, customerId, null);
        java.time.Instant agreedAt = view(seed.handoverId).unitInspectedAt();

        loginAs(FS);
        String second = uploaded(fsId, "c2.jpg");
        workflow.correctInspection(seed.handoverId, "  Khoang sạch, vết xước cửa trái  ", List.of(first, second),
                " Bổ sung vết xước khách chỉ ra ");

        HandoverView view = view(seed.handoverId);
        assertThat(view.inspectionStatus()).isEqualTo("Agreed");
        assertThat(view.unitInspectedAt()).isEqualTo(agreedAt);
        assertThat(view.inspectionNotes()).isEqualTo("Khoang sạch, vết xước cửa trái");
        assertThat(view.inspectionPhotos()).containsExactly(first, second);
        assertThat(view.result()).isEqualTo("IN_PROGRESS");
        assertThat(auditCount("INSPECTION_CORRECTED", "HandoverRecord", seed.handoverId)).isEqualTo(1);
        Map<String, Object> audit = jdbc.queryForMap("select actor_account_id, reason, "
                + "old_value->>'inspectionNotes' as old_notes, old_value->>'photoCount' as old_count, "
                + "new_value->>'inspectionNotes' as new_notes, new_value->>'photoCount' as new_count "
                + "from audit_logs where action = 'INSPECTION_CORRECTED' and entity_id = ?",
                String.valueOf(seed.handoverId));
        assertThat(((Number) audit.get("actor_account_id")).longValue()).isEqualTo(fsId);
        assertThat(audit.get("reason")).isEqualTo("Bổ sung vết xước khách chỉ ra");
        assertThat(audit.get("old_notes")).isEqualTo("Khoang sạch");
        assertThat(audit.get("old_count")).isEqualTo("1");
        assertThat(audit.get("new_notes")).isEqualTo("Khoang sạch, vết xước cửa trái");
        assertThat(audit.get("new_count")).isEqualTo("2");

        // Sửa được cả khi hợp đồng đã ký (biên bản còn mở) — bản hợp đồng đã chốt không đổi theo.
        long contractId = contractId(seed.orderId);
        workflow.signContract(contractId, uploaded(fsId, "signed.pdf"));
        String frozen = documentHtml(contractId);
        workflow.correctInspection(seed.handoverId, "Ghi chú sau khi ký", List.of(), "Sửa lại câu chữ");
        assertThat(view(seed.handoverId).inspectionNotes()).isEqualTo("Ghi chú sau khi ký");
        assertThat(view(seed.handoverId).inspectionPhotos()).isEmpty();
        assertThat(documentService.render(contractId)).isEqualTo(frozen);
        assertThat(auditCount("INSPECTION_CORRECTED", "HandoverRecord", seed.handoverId)).isEqualTo(2);
    }

    @Test
    void inspectionCorrectionGuards() {
        Seed seed = seedInProgress(fsId);
        loginAs(FS);
        workflow.verifyIdentity(seed.handoverId, true, null, idPhotos());
        workflow.recordInspection(seed.handoverId, "Khoang sạch", List.of());

        // Khách chưa đồng ý → dùng bước ghi hiện trạng thường.
        assertThatThrownBy(() -> workflow.correctInspection(seed.handoverId, "x", List.of(), "Lý do"))
                .isInstanceOf(HandoverConflictException.class)
                .hasMessageContaining("Khách chưa đồng ý hiện trạng");

        loginAs(CUSTOMER);
        workflow.agreeInspection(seed.handoverId, customerId, null);
        loginAs(FS);
        assertThatThrownBy(() -> workflow.correctInspection(seed.handoverId, "x", List.of(), " "))
                .isInstanceOf(HandoverValidationException.class)
                .hasMessageContaining("lý do");
        assertThatThrownBy(() -> workflow.correctInspection(seed.handoverId, "x", List.of(), null))
                .isInstanceOf(HandoverValidationException.class);
        assertThatThrownBy(() -> workflow.correctInspection(seed.handoverId, "x", List.of(), "x".repeat(501)))
                .isInstanceOf(HandoverValidationException.class);
        List<String> tooMany = java.util.Collections.nCopies(21, uploaded(fsId, "many.jpg"));
        assertThatThrownBy(() -> workflow.correctInspection(seed.handoverId, "x", tooMany, "Lý do"))
                .isInstanceOf(HandoverValidationException.class)
                .hasMessageContaining("Tối đa 20 ảnh");
        assertThatThrownBy(() -> workflow.correctInspection(seed.handoverId, " ", null, "Lý do"))
                .isInstanceOf(HandoverValidationException.class)
                .hasMessageContaining("ghi chú hoặc ít nhất một ảnh");
        assertThatThrownBy(() -> workflow.correctInspection(seed.handoverId, "x",
                List.of("https://img.test/foreign.jpg"), "Lý do"))
                .isInstanceOf(HandoverValidationException.class)
                .hasMessageContaining("đã tải lên hệ thống");
        assertThat(auditCount("INSPECTION_CORRECTED", "HandoverRecord", seed.handoverId)).isZero();
        assertThat(view(seed.handoverId).inspectionNotes()).isEqualTo("Khoang sạch");

        // FS khác không sửa được.
        long otherStaff = id("select id from accounts where email = '" + FM + "'");
        Seed foreign = seedInProgress(otherStaff);
        assertThatThrownBy(() -> workflow.correctInspection(foreign.handoverId, "x", List.of(), "Lý do"))
                .isInstanceOf(HandoverForbiddenException.class);
    }

    @Test
    void inspectionCorrectionRejectedOnceHandoverCompleted() {
        Seed seed = seedInProgress(fsId);
        long contractId = untilSigned(seed);
        pay(firstMonthInvoice(contractId));
        loginAs(CUSTOMER);
        assertThat(workflow.confirm(seed.handoverId, customerId, AccessChoice.ACCESS_CODE).completed()).isTrue();

        loginAs(FS);
        assertThatThrownBy(() -> workflow.correctInspection(seed.handoverId, "Sửa muộn", List.of(), "Lý do"))
                .isInstanceOf(HandoverConflictException.class)
                .hasMessageContaining("Phiên bàn giao đã đóng");
        assertThat(auditCount("INSPECTION_CORRECTED", "HandoverRecord", seed.handoverId)).isZero();
    }

    // ------------------------------------------------------------------ nguồn URL ảnh / bản scan

    @Test
    void inspectionPhotosMustBeUploadsOfCurrentStaff() {
        Seed seed = seedInProgress(fsId);
        loginAs(FS);
        workflow.verifyIdentity(seed.handoverId, true, null, idPhotos());

        assertThatThrownBy(() -> workflow.recordInspection(seed.handoverId, "x",
                List.of("https://img.test/1.jpg")))
                .isInstanceOf(HandoverValidationException.class)
                .hasMessageContaining("Ảnh hiện trạng phải là ảnh bạn đã tải lên hệ thống");
        assertThatThrownBy(() -> workflow.recordInspection(seed.handoverId, "x", List.of(" ")))
                .isInstanceOf(HandoverValidationException.class);
        assertThatThrownBy(() -> workflow.recordInspection(seed.handoverId, "x",
                java.util.Arrays.asList((String) null)))
                .isInstanceOf(HandoverValidationException.class);
        String customersUpload = uploaded(customerId, "cus.jpg");
        assertThatThrownBy(() -> workflow.recordInspection(seed.handoverId, "x", List.of(customersUpload)))
                .isInstanceOf(HandoverValidationException.class);
        String canceled = uploaded(fsId, "canceled.jpg", "Canceled");
        assertThatThrownBy(() -> workflow.recordInspection(seed.handoverId, "x", List.of(canceled)))
                .isInstanceOf(HandoverValidationException.class);
        assertThat(view(seed.handoverId).inspectionNotes()).isNull();

        String pending = uploaded(fsId, "pending.jpg", "Pending");
        workflow.recordInspection(seed.handoverId, "Khoang sạch", List.of(pending));
        assertThat(view(seed.handoverId).inspectionPhotos()).containsExactly(pending);
        // Lưu ảnh vào biên bản → upload Pending được attach phía server (cron dọn không xoá).
        assertThat(jdbc.queryForObject("select status from media_uploads where url = ?", String.class, pending))
                .isEqualTo("Attached");

        // Ảnh đã có trên biên bản giữ nguyên được dù upload gốc sau đó bị dọn.
        jdbc.update("update media_uploads set status = 'Deleted' where url = ?", pending);
        String extra = uploaded(fsId, "extra.jpg");
        workflow.recordInspection(seed.handoverId, "Khoang sạch", List.of(pending, extra));
        assertThat(view(seed.handoverId).inspectionPhotos()).containsExactly(pending, extra);
    }

    @Test
    void signedScanMustBeUploadOfCurrentStaff() {
        Seed seed = seedInProgress(fsId);
        untilAgreed(seed);
        long contractId = contractId(seed.orderId);
        loginAs(FS);

        assertThatThrownBy(() -> workflow.signContract(contractId, "https://img.test/signed.pdf"))
                .isInstanceOf(HandoverValidationException.class)
                .hasMessageContaining("Bản scan hợp đồng phải là tệp bạn đã tải lên hệ thống");
        String customersUpload = uploaded(customerId, "cus.pdf");
        assertThatThrownBy(() -> workflow.signContract(contractId, customersUpload))
                .isInstanceOf(HandoverValidationException.class);
        assertThat(contractStatus(contractId)).isEqualTo("Draft");
        assertThat(documentHtml(contractId)).isNull();

        String scan = uploaded(fsId, "ok.pdf", "Pending");
        workflow.signContract(contractId, "  " + scan + " ");
        assertThat(contractStatus(contractId)).isEqualTo("Signed");
        assertThat(jdbc.queryForObject("select pdf_url from rental_contracts where id = ?", String.class,
                contractId)).isEqualTo(scan);
    }

    // ------------------------------------------------------------------ helpers

    private record Seed(long unitId, long orderId, long appointmentId, long handoverId) {
    }

    /** Đơn {@code InProgress}: cọc Paid, khoang Reserved, lịch check-in Done (khách đã đến), biên bản mở. */
    private Seed seedInProgress(long staffId) {
        return seed(staffId, true);
    }

    /** Đơn {@code InProgress} với lịch Pending đã quá giờ, khách chưa đến. */
    private Seed seedScheduledNoShow() {
        return seed(fsId, false);
    }

    private Seed seed(long staffId, boolean arrived) {
        int n = SEQ.incrementAndGet();
        String suffix = n + "-" + System.nanoTime() % 100000;
        long unitId = id("insert into storage_units (code, facility_id, unit_type_id, status) values ('H-" + suffix
                + "', " + facilityId + ", (select id from unit_types where name = 'Small'), 'Reserved') returning id");
        long requestId = id("insert into rental_requests (facility_id, customer_name, normalized_customer_email, "
                + "customer_phone, unit_type_id, start_date, period, status, unit_id) values (" + facilityId
                + ", 'Khách Demo', '" + CUSTOMER + "', '0900000000', (select id from unit_types where name = "
                + "'Small'), current_date, 3, 'Converted', " + unitId + ") returning id");
        long orderId = id("insert into rental_orders (request_id, customer_id, unit_id, status, expires_at) values ("
                + requestId + ", " + customerId + ", " + unitId + ", 'InProgress', now() + interval '20 days') "
                + "returning id");
        jdbc.update("insert into proposal_feedbacks (order_id, customer_id, unit_id, status) values (?, ?, ?, "
                + "'Agreed')", orderId, customerId, unitId);
        jdbc.update("insert into invoices (order_id, customer_id, code, title, type, status, amount, due_date) values "
                + "(?, ?, ?, 'Đặt cọc', 'Deposit', 'Paid', 500000, now() + interval '3 days')", orderId, customerId,
                "INV-DEP-Q7-H" + suffix);
        String times = arrived
                ? "now() - interval '30 minutes', now() + interval '90 minutes', now() - interval '10 minutes', 'Done'"
                : "now() - interval '3 hours', now() - interval '1 hour', null, 'Pending'";
        long appointmentId = id("insert into appointments (customer_id, facility_id, staff_id, type, date, started_at,"
                + " end_at, arrived_at, status) values (" + customerId + ", " + facilityId + ", " + staffId
                + ", 'CHECKIN', current_date, " + times + ") returning id");
        jdbc.update("insert into rental_appointments (appointment_id, order_id) values (?, ?)", appointmentId,
                orderId);
        long handoverId = id("insert into handover_records (order_id, appointment_id, unit_id, due_at) values ("
                + orderId + ", " + appointmentId + ", " + unitId + ", "
                + (arrived ? "now() + interval '1 day'" : "null") + ") returning id");
        return new Seed(unitId, orderId, appointmentId, handoverId);
    }

    private void untilAgreed(Seed seed) {
        loginAs(FS);
        workflow.verifyIdentity(seed.handoverId, true, null, idPhotos());
        workflow.recordInspection(seed.handoverId, "Khoang sạch, khoá tốt", List.of());
        loginAs(CUSTOMER);
        workflow.agreeInspection(seed.handoverId, customerId, null);
    }

    private long untilSigned(Seed seed) {
        untilAgreed(seed);
        long contractId = contractId(seed.orderId);
        loginAs(FS);
        ContractInvoice invoice = workflow.signContract(contractId, uploaded(fsId, "signed.pdf"));
        assertThat(invoice.dueDate()).isBefore(java.time.Instant.now().plusSeconds(24 * 3600 + 60));
        return contractId;
    }

    /** Giả lập một upload {@code Attached} qua {@code /api/media} của account (row {@code media_uploads}). */
    /** Ảnh CCCD FS đã upload — xác minh đạt bắt buộc ít nhất một ảnh. */
    private List<String> idPhotos() {
        return List.of(uploaded(fsId, "cccd.jpg"));
    }

    private String uploaded(long accountId, String name) {
        return uploaded(accountId, name, "Attached");
    }

    private String uploaded(long accountId, String name, String status) {
        String publicId = "uploads/acc-" + accountId + "/" + SEQ.incrementAndGet() + "-" + System.nanoTime() + "-"
                + name;
        String url = "https://res.cloudinary.com/test/image/upload/" + publicId;
        jdbc.update("insert into media_uploads (account_id, batch_id, public_id, url, bytes, format, status) "
                + "values (?, gen_random_uuid(), ?, ?, 1024, 'jpg', ?)", accountId, publicId, url, status);
        return url;
    }

    private String documentHtml(long contractId) {
        return jdbc.queryForObject("select document_html from rental_contracts where id = ?", String.class,
                contractId);
    }

    private ContractInvoice firstMonthInvoice(long contractId) {
        return jdbc.queryForObject("select id, code, status, amount, due_date from invoices where contract_id = ? "
                        + "and type = 'Rental' order by id desc limit 1",
                (rs, i) -> new ContractInvoice(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getBigDecimal(4),
                        rs.getTimestamp(5).toInstant()), contractId);
    }

    /** Khách bấm thanh toán hoá đơn tháng đầu rồi IPN báo thành công. */
    private void pay(ContractInvoice invoice) {
        assertThat(invoicePaymentService.start(invoice.invoiceId(), customerId, "127.0.0.1").rejected()).isFalse();
        String ref = jdbc.queryForObject("select vnp_txn_ref from payment_transactions where invoice_id = ? "
                + "order by id desc limit 1", String.class, invoice.invoiceId());
        Map<String, String> ipn = new HashMap<>();
        ipn.put("vnp_TxnRef", ref);
        ipn.put("vnp_TmnCode", "TESTTMN1");
        ipn.put("vnp_Amount", invoice.amount().multiply(BigDecimal.valueOf(100)).toBigInteger().toString());
        ipn.put("vnp_ResponseCode", "00");
        ipn.put("vnp_TransactionStatus", "00");
        ipn.put("vnp_TransactionNo", "RNT" + SEQ.incrementAndGet() + System.nanoTime() % 100000);
        ipn.put("vnp_PayDate", LocalDateTime.now(VN).format(PAY_DATE));
        ipn.put("vnp_SecureHash", "mocked");
        assertThat(paymentService.handleIpn(ipn).rspCode()).isEqualTo("00");
        assertThat(invoiceStatus(invoice.invoiceId())).isEqualTo("Paid");
    }

    private HandoverView view(long handoverId) {
        return queryService.view(handoverId);
    }

    private long contractId(long orderId) {
        return id("select id from rental_contracts where order_id = " + orderId + " order by id desc limit 1");
    }

    private long id(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private int auditCount(String action, String entityType, long entityId) {
        return count("select count(*) from audit_logs where action = ? and entity_type = ? and entity_id = ?",
                action, entityType, String.valueOf(entityId));
    }

    private int notifications(String email, String type, long orderId) {
        return count("select count(*) from notifications n join accounts a on a.id = n.account_id "
                + "join order_notifications o on o.notification_id = n.id "
                + "where a.email = ? and n.type = ? and o.order_id = ?", email, type, orderId);
    }

    private String handoverResult(long id) {
        return jdbc.queryForObject("select result from handover_records where id = ?", String.class, id);
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

    private String contractStatus(long contractId) {
        return jdbc.queryForObject("select status from rental_contracts where id = ?", String.class, contractId);
    }

    private String invoiceStatus(long invoiceId) {
        return jdbc.queryForObject("select status from invoices where id = ?", String.class, invoiceId);
    }

    private static void loginAs(String email) {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(email, null, List.of()));
    }
}
