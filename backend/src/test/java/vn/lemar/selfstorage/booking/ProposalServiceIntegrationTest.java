package vn.lemar.selfstorage.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.lemar.selfstorage.booking.application.AgreeResult;
import vn.lemar.selfstorage.booking.application.InvoiceView;
import vn.lemar.selfstorage.booking.application.ProposalQueryService;
import vn.lemar.selfstorage.booking.application.ProposalService;
import vn.lemar.selfstorage.booking.application.ProposalView;
import vn.lemar.selfstorage.booking.application.RejectResult;
import vn.lemar.selfstorage.booking.application.dto.CustomerProposalResponse;
import vn.lemar.selfstorage.booking.application.dto.FmRentalOrderResponse;
import vn.lemar.selfstorage.booking.application.exception.ProposalConflictException;
import vn.lemar.selfstorage.booking.application.exception.ProposalForbiddenException;
import vn.lemar.selfstorage.booking.application.exception.ProposalNotFoundException;
import vn.lemar.selfstorage.booking.application.exception.ProposalValidationException;
import vn.lemar.selfstorage.booking.application.exception.RentalOrderNotFoundException;
import vn.lemar.selfstorage.booking.application.exception.StorageUnitNotFoundException;
import vn.lemar.selfstorage.identity.application.exception.ForbiddenException;

/**
 * Flow 1.3 chạy thật trên Postgres (Testcontainers) với seed V2/V3: khách đồng ý / từ chối, FM đề
 * xuất lại, theo các điểm chốt ở #113. Đơn + proposal do luồng B2 tạo nên test tự chèn bằng SQL. Máy không
 * có Docker thì skip.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@Transactional
class ProposalServiceIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    /** Giá seed V2 của loại khoang Small, {@code invoice.deposit_months} = 1. */
    private static final BigDecimal SMALL_MONTHLY_PRICE = new BigDecimal("500000.00");

    @Autowired
    private ProposalService service;
    @Autowired
    private ProposalQueryService queryService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private EntityManager entityManager;

    private long customerId;
    private long otherCustomerId;
    private long fmId;
    private long facilityId;
    private long orderId;

    @BeforeEach
    void seedOrder() {
        customerId = accountId("customer1@lemar.vn");
        otherCustomerId = accountId("unverified@lemar.vn");
        fmId = accountId("fm1@lemar.vn");
        facilityId = jdbc.queryForObject("select id from facilities where code = 'Q7'", Long.class);
        Long smallType = jdbc.queryForObject("select id from unit_types where name = 'Small'", Long.class);
        Long requestId = jdbc.queryForObject("""
                insert into rental_requests (facility_id, customer_name, normalized_customer_email, customer_phone,
                    unit_type_id, start_date, period, unit_id, status)
                values (?, 'Khách Test', 'customer1@lemar.vn', '0900000000', ?, current_date + 7, 3, ?, 'Converted')
                returning id""", Long.class, facilityId, smallType, unitId("A-01"));
        orderId = jdbc.queryForObject("""
                insert into rental_orders (request_id, customer_id, status, expires_at)
                values (?, ?, 'Pending', now() + interval '30 days') returning id""",
                Long.class, requestId, customerId);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ---------------------------------------------------------------- đồng ý

    @Test
    void agreeMarksAgreedAssignsUnitAndIssuesDepositInvoice() {
        long proposalId = proposal("A-01", "Pending");

        AgreeResult result = service.agree(proposalId, customerId);

        assertThat(result.outcome()).isEqualTo(AgreeResult.Outcome.AGREED);
        assertThat(result.proposal().status()).isEqualTo("Agreed");
        assertThat(proposalStatus(proposalId)).isEqualTo("Agreed");
        assertThat(orderUnitId()).isEqualTo(unitId("A-01"));

        InvoiceView invoice = result.invoice();
        assertThat(invoice.code()).matches("INV-DEP-Q7-\\d{6}-[A-Z2-9]{6}");
        assertThat(invoice.type()).isEqualTo("Deposit");
        assertThat(invoice.status()).isEqualTo("Unpaid");
        assertThat(invoice.amount()).isEqualByComparingTo(SMALL_MONTHLY_PRICE);
        assertThat(invoice.title()).isEqualTo("Đặt cọc khoang chứa A-01");
        assertThat(invoice.description()).isEqualTo("Thanh toán đặt cọc khoang chứa A-01 để đảm bảo giữ chỗ.");
        assertThat(invoice.dueDate()).isCloseTo(Instant.now().plus(3, ChronoUnit.DAYS), within(1, ChronoUnit.MINUTES));

        entityManager.flush();
        Map<String, Object> row = jdbc.queryForMap(
                "select order_id, customer_id, type, status, amount from invoices where id = ?", invoice.id());
        assertThat(row).containsEntry("order_id", orderId).containsEntry("customer_id", customerId)
                .containsEntry("type", "Deposit").containsEntry("status", "Unpaid");
        assertThat(auditActions("ProposalFeedback", proposalId)).containsExactly("PROPOSAL_AGREED");
        assertThat(auditActions("Invoice", invoice.id())).containsExactly("INVOICE_CREATED");
        assertThat(jdbc.queryForObject("select actor_account_id from audit_logs where entity_type = 'Invoice' "
                + "and entity_id = ?", Long.class, String.valueOf(invoice.id()))).isEqualTo(customerId);
    }

    @Test
    void depositUsesUnitPriceOverrideTimesDepositMonths() {
        long proposalId = proposal("A-01", "Pending");
        jdbc.update("update storage_units set monthly_price = 650000 where code = 'A-01'");
        jdbc.update("update policies set value = '2' where key = 'invoice.deposit_months'");

        AgreeResult result = service.agree(proposalId, customerId);

        assertThat(result.invoice().amount()).isEqualByComparingTo("1300000");
    }

    @Test
    void depositDueDateNeverExceedsOrderExpiry() {
        Instant orderExpiry = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        jdbc.update("update rental_orders set expires_at = ? where id = ?", Timestamp.from(orderExpiry), orderId);
        long proposalId = proposal("A-01", "Pending");

        AgreeResult result = service.agree(proposalId, customerId);

        assertThat(result.invoice().dueDate()).isEqualTo(orderExpiry);
    }

    @Test
    void agreeOnUnavailableUnitCancelsProposalAndAsksFmToRepropose() {
        long proposalId = proposal("C-01", "Pending"); // C-01 đang Maintenance trong seed

        AgreeResult result = service.agree(proposalId, customerId);

        assertThat(result.outcome()).isEqualTo(AgreeResult.Outcome.UNIT_UNAVAILABLE);
        assertThat(result.invoice()).isNull();
        assertThat(proposalStatus(proposalId)).isEqualTo("Canceled");
        assertThat(orderUnitId()).isNull();
        assertThat(jdbc.queryForObject("select count(*) from invoices", Long.class)).isZero();
        assertThat(notificationsOf(fmId)).singleElement().satisfies(row -> {
            assertThat(row.get("type")).isEqualTo("PROPOSAL_REPROPOSAL_REQUIRED");
            assertThat((String) row.get("body")).contains("Đơn " + orderId).contains("C-01");
        });
        assertThat(orderNotificationCount()).isEqualTo(1);
        assertThat(auditActions("ProposalFeedback", proposalId)).containsExactly("PROPOSAL_CANCELED");
        assertThat(auditReason(proposalId)).contains("C-01");
    }

    @Test
    void secondAgreeOnAnotherPendingProposalIsRejected() {
        long first = proposal("A-01", "Pending");
        long second = proposal("A-02", "Pending");

        AgreeResult ok = service.agree(first, customerId);
        assertThat(ok.invoice()).isNotNull();
        assertThatThrownBy(() -> service.agree(second, customerId))
                .isInstanceOf(ProposalConflictException.class)
                .hasMessageContaining("hoá đơn cọc chờ thanh toán");
        assertThat(proposalStatus(second)).isEqualTo("Pending");
        assertThat(jdbc.queryForObject("select count(*) from invoices where order_id = ? and type = 'Deposit' "
                + "and status in ('Unpaid','Paid')", Long.class, orderId)).isEqualTo(1);
    }

    @Test
    void agreeOnDepositedOrderIssuesOnlyTheDepositDifference() {
        depositedOrder();
        paidDeposit(new BigDecimal("500000.00"));
        long proposalId = proposal("A-02", "Pending");
        jdbc.update("update storage_units set monthly_price = 900000 where code = 'A-02'");

        AgreeResult result = service.agree(proposalId, customerId);

        assertThat(result.invoice()).isNotNull();
        assertThat(result.invoice().amount()).isEqualByComparingTo("400000.00");
        assertThat(result.invoice().title()).isEqualTo("Bổ sung cọc khoang chứa A-02");
        assertThat(result.invoice().type()).isEqualTo("DepositSupplement");
        assertThat(result.invoice().status()).isEqualTo("Unpaid");
        assertThat(notificationsOf(customerId)).extracting(row -> row.get("type"))
                .contains("UNIT_CHANGE_PAYMENT_REQUIRED");
    }

    @Test
    void unitChangeReleasesOldUnitAndReservesNewUnitWhenDepositCovers() {
        depositedOrder();
        jdbc.update("update rental_orders set unit_id = ? where id = ?", unitId("A-01"), orderId);
        jdbc.update("update storage_units set status = 'Reserved' where id = ?", unitId("A-01"));
        paidDeposit(SMALL_MONTHLY_PRICE);
        long proposalId = proposal("A-02", "Pending");

        AgreeResult result = service.agree(proposalId, customerId);

        assertThat(result.invoice()).isNull();
        assertThat(orderUnitId()).isEqualTo(unitId("A-02"));
        assertThat(jdbc.queryForObject("select status from storage_units where id = ?", String.class,
                unitId("A-01"))).isEqualTo("Available");
        // Cọc đã đủ, không có hoá đơn bổ sung để chốt → khoang mới được giữ ngay (đơn đã cọc luôn giữ khoang).
        assertThat(jdbc.queryForObject("select status from storage_units where id = ?", String.class,
                unitId("A-02"))).isEqualTo("Reserved");
        assertThat(auditActions("StorageUnit", unitId("A-01"))).containsExactly("UNIT_CHANGE_RELEASED");
        assertThat(auditActions("StorageUnit", unitId("A-02"))).containsExactly("STORAGE_UNIT_RESERVED");
    }

    @Test
    void unitChangeFeeIsAddedToTheSupplementalDeposit() {
        jdbc.update("update policies set value = '50000' where key = 'fee.unit_change'");
        depositedOrder();
        paidDeposit(new BigDecimal("500000.00"));
        long proposalId = proposal("A-02", "Pending");
        jdbc.update("update storage_units set monthly_price = 900000 where code = 'A-02'");

        AgreeResult result = service.agree(proposalId, customerId);

        assertThat(result.invoice().amount()).isEqualByComparingTo("450000.00");
    }

    @Test
    void agreeOnDepositedOrderWithEqualDepositIssuesNoInvoice() {
        depositedOrder();
        paidDeposit(SMALL_MONTHLY_PRICE);
        long proposalId = proposal("A-02", "Pending");

        AgreeResult result = service.agree(proposalId, customerId);

        assertThat(result.invoice()).isNull();
    }

    @Test
    void agreeOnDepositedOrderWithSurplusAsksFmForManualRefund() {
        depositedOrder();
        paidDeposit(new BigDecimal("1000000.00"));
        long proposalId = proposal("A-02", "Pending");

        AgreeResult result = service.agree(proposalId, customerId);

        assertThat(result.invoice()).isNull();
        assertThat(auditActions("RentalOrder", orderId)).contains("DEPOSIT_REFUND_REQUIRED");
        assertThat(notificationsOf(fmId)).extracting(row -> row.get("type"))
                .contains("DEPOSIT_REFUND_REQUIRED");
        assertThat(notificationsOf(customerId)).extracting(row -> row.get("type"))
                .contains("DEPOSIT_REFUND_PENDING");
    }

    @Test
    void paidSupplementCountsTowardsTheNextDepositDifference() {
        depositedOrder();
        paidDeposit(new BigDecimal("500000.00"));
        jdbc.update("""
                insert into invoices (order_id, customer_id, code, title, type, status, amount, due_date)
                values (?, ?, ?, 'Bổ sung cọc', 'DepositSupplement', 'Paid', 400000, now() + interval '1 day')""",
                orderId, customerId, "INV-DEP-Q7-SUP-" + System.nanoTime());
        long proposalId = proposal("A-02", "Pending");

        AgreeResult result = service.agree(proposalId, customerId);

        assertThat(result.invoice()).isNull();
        assertThat(jdbc.queryForObject("select new_value from audit_logs where action = 'DEPOSIT_REFUND_REQUIRED' "
                + "and entity_id = ? order by id desc limit 1", String.class, String.valueOf(orderId)))
                .contains("400000");
    }

    @Test
    void reproposeCancelsAnUnpaidSupplement() {
        proposal("A-01", "Rejected");
        jdbc.update("""
                insert into invoices (order_id, customer_id, code, title, type, status, amount, due_date)
                values (?, ?, ?, 'Bổ sung cọc', 'DepositSupplement', 'Unpaid', 400000, now() + interval '1 day')""",
                orderId, customerId, "INV-DEP-Q7-SUP-" + System.nanoTime());
        loginAs("fm1@lemar.vn");

        service.repropose(orderId, unitId("A-02"), null, fmId);

        entityManager.flush();
        assertThat(jdbc.queryForObject("select status from invoices where order_id = ? and type = 'DepositSupplement'",
                String.class, orderId)).isEqualTo("Canceled");
    }

    @Test
    void agreeOnCanceledOrderConflicts() {
        jdbc.update("update rental_orders set status = 'Canceled', cancel_reason = 'Khách không còn nhu cầu' "
                + "where id = ?", orderId);
        long proposalId = proposal("A-01", "Pending");

        assertThatThrownBy(() -> service.agree(proposalId, customerId))
                .isInstanceOf(ProposalConflictException.class)
                .hasMessageContaining("đã đóng");
    }

    @Test
    void agreeOnExpiredOrderConflicts() {
        jdbc.update("update rental_orders set expires_at = now() - interval '1 day' where id = ?", orderId);
        long proposalId = proposal("A-01", "Pending");

        assertThatThrownBy(() -> service.agree(proposalId, customerId))
                .isInstanceOf(ProposalConflictException.class)
                .hasMessageContaining("hết hạn");
    }

    @Test
    void agreeByAnotherCustomerIsForbidden() {
        long proposalId = proposal("A-01", "Pending");

        assertThatThrownBy(() -> service.agree(proposalId, otherCustomerId))
                .isInstanceOf(ProposalForbiddenException.class);
        assertThat(proposalStatus(proposalId)).isEqualTo("Pending");
    }

    @Test
    void agreeOnAlreadyHandledProposalConflicts() {
        long proposalId = proposal("A-01", "Rejected");

        assertThatThrownBy(() -> service.agree(proposalId, customerId))
                .isInstanceOf(ProposalConflictException.class);
    }

    @Test
    void agreeOnUnknownProposalIsNotFound() {
        assertThatThrownBy(() -> service.agree(-1L, customerId)).isInstanceOf(ProposalNotFoundException.class);
    }

    // ---------------------------------------------------------------- từ chối

    @Test
    void rejectStoresNoteAuditsAndNotifiesFm() {
        long proposalId = proposal("A-01", "Pending");

        RejectResult result = service.reject(proposalId, customerId, "  Khoang xa cửa ra vào  ");

        assertThat(result.orderCanceled()).isFalse();
        assertThat(result.proposal().status()).isEqualTo("Rejected");
        assertThat(result.proposal().note()).isEqualTo("Khoang xa cửa ra vào");
        assertThat(auditActions("ProposalFeedback", proposalId)).containsExactly("PROPOSAL_REJECTED");
        assertThat(notificationsOf(fmId)).singleElement().satisfies(row -> {
            assertThat(row.get("type")).isEqualTo("PROPOSAL_REJECTED");
            assertThat((String) row.get("body")).contains("A-01").contains("Khoang xa cửa ra vào");
        });
    }

    @Test
    void secondRejectionDoesNotCancelTheOrder() {
        proposal("A-03", "Rejected");
        long proposalId = proposal("A-01", "Pending");

        RejectResult result = service.reject(proposalId, customerId, "Vẫn chưa hợp");

        assertThat(result.orderCanceled()).isFalse();
        assertThat(orderStatus()).isEqualTo("Pending");
    }

    @Test
    void thirdRejectionCancelsTheOrderAndNotifiesBothSides() {
        proposal("A-03", "Rejected");
        proposal("A-04", "Rejected");
        long proposalId = proposal("A-01", "Pending");

        RejectResult result = service.reject(proposalId, customerId, "Lần thứ ba vẫn không hợp");

        assertThat(result.orderCanceled()).isTrue();
        assertThat(proposalStatus(proposalId)).isEqualTo("Rejected");
        assertThat(orderStatus()).isEqualTo("Canceled");
        assertThat(jdbc.queryForObject("select cancel_reason from rental_orders where id = ?", String.class,
                orderId)).isEqualTo("Từ chối quá số khoang cho phép");
        assertThat(auditActions("RentalOrder", orderId)).containsExactly("RENTAL_ORDER_CANCELED");
        assertThat(notificationsOf(customerId)).singleElement().satisfies(row -> {
            assertThat(row.get("type")).isEqualTo("RENTAL_ORDER_CANCELED");
            assertThat((String) row.get("body")).contains("Từ chối quá số khoang cho phép");
            assertThat((String) row.get("body")).contains("Tiền cọc không được hoàn");
            assertThat((String) row.get("body")).contains("đã bị hủy");
        });
        assertThat(notificationsOf(fmId)).extracting(row -> row.get("type"))
                .containsExactly("RENTAL_ORDER_CANCELED");
    }

    @Test
    void cancellationCascadeCancelsLiveProposalsUnpaidInvoicesAndReleasesReservedUnit() {
        proposal("A-03", "Rejected");
        proposal("A-04", "Rejected");
        long agreedEarlier = proposal("B-01", "Agreed");
        long pendingId = proposal("A-01", "Pending");
        // Đơn đã cọc B-01 (Reserved) và đang được đề xuất lại — huỷ đơn phải nhả khoang nó giữ.
        jdbc.update("update storage_units set status = 'Reserved' where code = 'B-01'");
        jdbc.update("update rental_orders set unit_id = ?, status = 'Deposited', needs_reproposal = true "
                + "where id = ?", unitId("B-01"), orderId);
        long invoiceId = jdbc.queryForObject("""
                insert into invoices (order_id, customer_id, code, title, type, status, amount, due_date)
                values (?, ?, 'INV-DEP-Q7-TEST-000001', 'Đặt cọc khoang chứa B-01', 'Deposit', 'Unpaid', 900000,
                        now() + interval '3 days') returning id""", Long.class, orderId, customerId);

        service.reject(pendingId, customerId, "Không cần nữa");

        assertThat(proposalStatus(agreedEarlier)).isEqualTo("Canceled");
        assertThat(jdbc.queryForObject("select status from invoices where id = ?", String.class, invoiceId))
                .isEqualTo("Canceled");
        assertThat(jdbc.queryForObject("select status from storage_units where code = 'B-01'", String.class))
                .isEqualTo("Available");
        assertThat(auditActions("ProposalFeedback", agreedEarlier)).containsExactly("RENTAL_ORDER_CANCELED");
        assertThat(auditActions("Invoice", invoiceId)).containsExactly("INVOICE_CANCELED");
        assertThat(auditActions("StorageUnit", unitId("B-01"))).containsExactly("RENTAL_ORDER_CANCELED");
    }

    @Test
    void cancelingPendingOrderKeepsUnitReservedByAnotherDepositedOrder() {
        // Đơn khác (khách khác) đã cọc A-01 → A-01 Reserved cho đơn đó.
        long otherCustomer = otherCustomerId;
        Long otherRequest = jdbc.queryForObject("""
                insert into rental_requests (facility_id, customer_name, normalized_customer_email, customer_phone,
                    unit_type_id, start_date, period, unit_id, status)
                values (?, 'Khách Khác', 'unverified@lemar.vn', '0900000001',
                        (select id from unit_types where name = 'Small'), current_date + 7, 3, ?, 'Converted')
                returning id""", Long.class, facilityId, unitId("A-01"));
        long otherOrder = jdbc.queryForObject("""
                insert into rental_orders (request_id, customer_id, unit_id, status, expires_at)
                values (?, ?, ?, 'Deposited', now() + interval '30 days') returning id""",
                Long.class, otherRequest, otherCustomer, unitId("A-01"));
        jdbc.update("update storage_units set status = 'Reserved' where code = 'A-01'");
        // Đơn của khách này mới đồng ý A-01 (chưa cọc) rồi bị huỷ ở lần từ chối thứ 3.
        jdbc.update("update rental_orders set unit_id = ? where id = ?", unitId("A-01"), orderId);
        proposal("A-02", "Rejected");
        proposal("A-03", "Rejected");
        long pendingId = proposal("A-04", "Pending");

        RejectResult result = service.reject(pendingId, customerId, "Không còn nhu cầu");

        assertThat(result.orderCanceled()).isTrue();
        assertThat(jdbc.queryForObject("select status from rental_orders where id = ?", String.class, otherOrder))
                .isEqualTo("Deposited");
        assertThat(jdbc.queryForObject("select status from storage_units where code = 'A-01'", String.class))
                .as("A-01 vẫn giữ cho đơn đã cọc").isEqualTo("Reserved");
        assertThat(auditActions("StorageUnit", unitId("A-01"))).isEmpty();
    }

    @Test
    void rejectWithoutNoteIsInvalidAndChangesNothing() {
        long proposalId = proposal("A-01", "Pending");

        assertThatThrownBy(() -> service.reject(proposalId, customerId, "   "))
                .isInstanceOf(ProposalValidationException.class);
        assertThatThrownBy(() -> service.reject(proposalId, customerId, null))
                .isInstanceOf(ProposalValidationException.class);
        assertThat(proposalStatus(proposalId)).isEqualTo("Pending");
    }

    @Test
    void rejectOnClosedOrderConflicts() {
        jdbc.update("update rental_orders set status = 'Canceled', cancel_reason = 'Khách không còn nhu cầu' "
                + "where id = ?", orderId);
        long proposalId = proposal("A-01", "Pending");

        assertThatThrownBy(() -> service.reject(proposalId, customerId, "Không hợp"))
                .isInstanceOf(ProposalConflictException.class)
                .hasMessageContaining("đã đóng");
        assertThat(proposalStatus(proposalId)).isEqualTo("Pending");
    }

    @Test
    void rejectWhenFacilityHasNoFmSkipsNotification() {
        long proposalId = proposal("A-01", "Pending");
        jdbc.update("update facilities set fm_account_id = null where id = ?", facilityId);

        service.reject(proposalId, customerId, "Đổi ý");

        assertThat(proposalStatus(proposalId)).isEqualTo("Rejected");
        assertThat(notificationCount()).isZero();
    }

    // ---------------------------------------------------------------- đề xuất lại (FM)

    @Test
    void reproposeCreatesPendingProposalAndNotifiesCustomer() {
        proposal("A-01", "Rejected");
        loginAs("fm1@lemar.vn");

        ProposalView view = service.repropose(orderId, unitId("A-02"), null, fmId);

        assertThat(view.status()).isEqualTo("Pending");
        assertThat(view.unitId()).isEqualTo(unitId("A-02"));
        assertThat(view.customerId()).isEqualTo(customerId);
        assertThat(auditActions("ProposalFeedback", view.id())).containsExactly("PROPOSAL_REPROPOSED");
        assertThat(auditReason(view.id())).isNull();
        assertThat(notificationsOf(customerId)).singleElement().satisfies(row -> {
            assertThat(row.get("type")).isEqualTo("PROPOSAL_REPROPOSED");
            assertThat((String) row.get("body")).contains("http://localhost:5173/proposals");
        });
    }

    @Test
    void reproposeRejectedUnitRequiresOverrideReason() {
        proposal("A-01", "Rejected");
        loginAs("fm1@lemar.vn");

        assertThatThrownBy(() -> service.repropose(orderId, unitId("A-01"), " ", fmId))
                .isInstanceOf(ProposalValidationException.class)
                .hasMessageContaining("A-01");
    }

    @Test
    void reproposeRejectedUnitWithReasonIsAuditedAsOverride() {
        proposal("A-01", "Rejected");
        loginAs("fm1@lemar.vn");

        ProposalView view = service.repropose(orderId, unitId("A-01"), "Khách gọi lại muốn lấy khoang này", fmId);

        assertThat(view.unitId()).isEqualTo(unitId("A-01"));
        assertThat(auditReason(view.id())).isEqualTo("Khách gọi lại muốn lấy khoang này");
    }

    @Test
    void reproposeWhileAProposalIsPendingConflicts() {
        proposal("A-01", "Pending");
        loginAs("fm1@lemar.vn");

        assertThatThrownBy(() -> service.repropose(orderId, unitId("A-02"), null, fmId))
                .isInstanceOf(ProposalConflictException.class);
    }

    @Test
    void reproposeUnavailableUnitConflicts() {
        proposal("A-01", "Rejected");
        loginAs("fm1@lemar.vn");

        assertThatThrownBy(() -> service.repropose(orderId, unitId("C-01"), null, fmId))
                .isInstanceOf(ProposalConflictException.class)
                .hasMessageContaining("C-01");
    }

    @Test
    void reproposeUnitOfAnotherFacilityIsNotFound() {
        Long tdUnit = jdbc.queryForObject("""
                insert into storage_units (code, facility_id, unit_type_id, status)
                values ('TD-01', (select id from facilities where code = 'TD'),
                        (select id from unit_types where name = 'Small'), 'Available') returning id""", Long.class);
        loginAs("fm1@lemar.vn");

        assertThatThrownBy(() -> service.repropose(orderId, tdUnit, null, fmId))
                .isInstanceOf(StorageUnitNotFoundException.class);
    }

    @Test
    void reproposeOnExpiredOrderConflicts() {
        jdbc.update("update rental_orders set expires_at = now() - interval '1 day' where id = ?", orderId);
        loginAs("fm1@lemar.vn");

        assertThatThrownBy(() -> service.repropose(orderId, unitId("A-02"), null, fmId))
                .isInstanceOf(ProposalConflictException.class)
                .hasMessageContaining("hết hạn");
    }

    @Test
    void reproposeOnClosedOrderConflicts() {
        jdbc.update("update rental_orders set status = 'Canceled', cancel_reason = 'Khách không còn nhu cầu' "
                + "where id = ?", orderId);
        loginAs("fm1@lemar.vn");

        assertThatThrownBy(() -> service.repropose(orderId, unitId("A-02"), null, fmId))
                .isInstanceOf(ProposalConflictException.class);
    }

    @Test
    void reproposeByFmOfAnotherFacilityIsForbidden() {
        loginAs("fm2@lemar.vn");

        assertThatThrownBy(() -> service.repropose(orderId, unitId("A-02"), null, accountId("fm2@lemar.vn")))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void reproposeOnUnknownOrderIsNotFound() {
        loginAs("fm1@lemar.vn");

        assertThatThrownBy(() -> service.repropose(-1L, unitId("A-02"), null, fmId))
                .isInstanceOf(RentalOrderNotFoundException.class);
    }

    @Test
    void reproposeIsBlockedWhenOrderLeftPendingWithoutFlag() {
        jdbc.update("update rental_orders set status = 'Deposited' where id = ?", orderId);
        loginAs("fm1@lemar.vn");

        assertThatThrownBy(() -> service.repropose(orderId, unitId("A-02"), null, fmId))
                .isInstanceOf(ProposalConflictException.class)
                .hasMessageContaining("không ở trạng thái cho phép");
    }

    @Test
    void reproposeIsAllowedWhenOrderNeedsReproposal() {
        jdbc.update("update rental_orders set status = 'Deposited', needs_reproposal = true where id = ?", orderId);
        loginAs("fm1@lemar.vn");

        ProposalView view = service.repropose(orderId, unitId("A-02"), null, fmId);

        assertThat(view.status()).isEqualTo("Pending");
        assertThat(needsReproposal()).isFalse();
    }

    @Test
    void rejectingTheReproposedUnitKeepsTheOrderReproposable() {
        depositedOrder();
        jdbc.update("update rental_orders set unit_id = ?, needs_reproposal = true where id = ?",
                unitId("A-01"), orderId);
        paidDeposit(SMALL_MONTHLY_PRICE);
        loginAs("fm1@lemar.vn");
        ProposalView reproposed = service.repropose(orderId, unitId("A-02"), null, fmId);
        assertThat(needsReproposal()).isFalse();

        RejectResult rejected = service.reject(reproposed.id(), customerId, "Không hợp khoang này");

        assertThat(rejected.orderCanceled()).isFalse();
        assertThat(needsReproposal()).isTrue();
        assertThat(service.repropose(orderId, unitId("A-03"), null, fmId).status()).isEqualTo("Pending");
    }

    @Test
    void unavailableReproposedUnitKeepsTheOrderReproposable() {
        depositedOrder();
        jdbc.update("update rental_orders set unit_id = ?, needs_reproposal = true where id = ?",
                unitId("A-01"), orderId);
        paidDeposit(SMALL_MONTHLY_PRICE);
        jdbc.update("update storage_units set status = 'Reserved' where id = ?", unitId("A-01"));
        loginAs("fm1@lemar.vn");
        ProposalView reproposed = service.repropose(orderId, unitId("A-02"), null, fmId);
        jdbc.update("update storage_units set status = 'Maintenance' where id = ?", unitId("A-02"));
        entityManager.clear();

        AgreeResult result = service.agree(reproposed.id(), customerId);

        assertThat(result.outcome()).isEqualTo(AgreeResult.Outcome.UNIT_UNAVAILABLE);
        assertThat(needsReproposal()).isTrue();
        assertThat(service.repropose(orderId, unitId("A-03"), null, fmId).status()).isEqualTo("Pending");
    }

    @Test
    void reproposeCancelsUnpaidDepositInvoiceButKeepsAgreedProposal() {
        long agreed = proposal("A-01", "Agreed");
        long invoiceId = jdbc.queryForObject("""
                insert into invoices (order_id, customer_id, code, title, type, status, amount, due_date)
                values (?, ?, 'INV-DEP-Q7-TEST-000002', 'Đặt cọc khoang chứa A-01', 'Deposit', 'Unpaid', 500000,
                        now() + interval '3 days') returning id""", Long.class, orderId, customerId);
        loginAs("fm1@lemar.vn");

        service.repropose(orderId, unitId("A-02"), null, fmId);

        entityManager.flush();
        assertThat(jdbc.queryForObject("select status from invoices where id = ?", String.class, invoiceId))
                .isEqualTo("Canceled");
        assertThat(auditActions("Invoice", invoiceId)).containsExactly("INVOICE_CANCELED");
        assertThat(proposalStatus(agreed)).isEqualTo("Agreed");
    }

    // ---------------------------------------------------------------- đọc dữ liệu màn hình

    @Test
    void customerListShowsOwnProposalsWithPriceAndRejectionCount() {
        proposal("A-03", "Rejected");
        long pendingId = proposal("A-01", "Pending");
        jdbc.update("update storage_units set monthly_price = 650000 where code = 'A-01'");

        List<CustomerProposalResponse> pending = queryService.listForCustomer(customerId, "Pending");

        assertThat(pending).singleElement().satisfies(row -> {
            assertThat(row.id()).isEqualTo(pendingId);
            assertThat(row.unitCode()).isEqualTo("A-01");
            assertThat(row.unitTypeName()).isEqualTo("Small");
            assertThat(row.facilityName()).isEqualTo("Self Storage Quận 7");
            assertThat(row.monthlyPrice()).isEqualByComparingTo("650000");
            assertThat(row.rejectionCount()).isEqualTo(1);
            assertThat(row.maxRejectionCount()).isEqualTo(3);
        });
        assertThat(queryService.listForCustomer(customerId, " ")).hasSize(2);
        assertThat(queryService.listForCustomer(otherCustomerId, null)).isEmpty();
        assertThatThrownBy(() -> queryService.listForCustomer(customerId, "Expired"))
                .isInstanceOf(ProposalValidationException.class);
    }

    @Test
    void fmOrderDetailListsHistoryAndAvailableUnitsOfRequestedType() {
        proposal("A-01", "Rejected");
        loginAs("fm1@lemar.vn");

        FmRentalOrderResponse order = queryService.orderForFacilityManager(orderId);

        assertThat(order.status()).isEqualTo("Pending");
        assertThat(order.customer().email()).isEqualTo("customer1@lemar.vn");
        assertThat(order.requested().unitTypeName()).isEqualTo("Small");
        assertThat(order.rejectionCount()).isEqualTo(1);
        assertThat(order.proposals()).singleElement().satisfies(row -> assertThat(row.note()).isEqualTo("Không hợp"));
        assertThat(order.availableUnits()).extracting(FmRentalOrderResponse.AvailableUnit::code)
                .containsExactly("A-01", "A-02", "A-03", "A-04");
        assertThat(order.availableUnits()).filteredOn(FmRentalOrderResponse.AvailableUnit::previouslyRejected)
                .extracting(FmRentalOrderResponse.AvailableUnit::code).containsExactly("A-01");
    }

    @Test
    void fmOrderDetailIsScopedToTheFacility() {
        loginAs("fm2@lemar.vn");

        assertThatThrownBy(() -> queryService.orderForFacilityManager(orderId)).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> queryService.orderForFacilityManager(-1L))
                .isInstanceOf(RentalOrderNotFoundException.class);
    }

    // ---------------------------------------------------------------- schema (#87 + chốt #113)

    // Mỗi test chỉ một câu bị CHECK chặn, đặt cuối: Postgres huỷ cả transaction sau câu lỗi đầu tiên.

    @Test
    void schemaRejectsExpiredOrderStatus() {
        assertThatThrownBy(() -> jdbc.update("update rental_orders set status = 'Expired' where id = ?", orderId))
                .hasMessageContaining("rental_orders_status_check");
    }

    @Test
    void schemaRejectsExpiredProposalStatus() {
        long proposalId = proposal("A-01", "Canceled");

        assertThatThrownBy(() -> jdbc.update("update proposal_feedbacks set status = 'Expired' where id = ?",
                proposalId)).hasMessageContaining("proposal_feedbacks_status_check");
    }

    // ---------------------------------------------------------------- helpers

    private long proposal(String unitCode, String status) {
        return jdbc.queryForObject("""
                insert into proposal_feedbacks (order_id, customer_id, unit_id, status, note)
                values (?, ?, ?, ?, ?) returning id""", Long.class,
                orderId, customerId, unitId(unitCode), status, "Rejected".equals(status) ? "Không hợp" : null);
    }

    private void depositedOrder() {
        jdbc.update("update rental_orders set status = 'Deposited' where id = ?", orderId);
    }

    private void paidDeposit(BigDecimal amount) {
        jdbc.update("""
                insert into invoices (order_id, customer_id, code, title, type, status, amount, due_date)
                values (?, ?, ?, 'Đặt cọc khoang chứa A-01', 'Deposit', 'Paid', ?, now() - interval '1 day')""",
                orderId, customerId, "INV-DEP-Q7-PAID-" + System.nanoTime(), amount);
    }

    private boolean needsReproposal() {
        entityManager.flush();
        return jdbc.queryForObject("select needs_reproposal from rental_orders where id = ?", Boolean.class, orderId);
    }

    private long accountId(String email) {
        return jdbc.queryForObject("select id from accounts where email = ?", Long.class, email);
    }

    private long unitId(String code) {
        return jdbc.queryForObject("select id from storage_units where code = ?", Long.class, code);
    }

    private String proposalStatus(long proposalId) {
        entityManager.flush();
        return jdbc.queryForObject("select status from proposal_feedbacks where id = ?", String.class, proposalId);
    }

    private String orderStatus() {
        entityManager.flush();
        return jdbc.queryForObject("select status from rental_orders where id = ?", String.class, orderId);
    }

    private Long orderUnitId() {
        entityManager.flush();
        return jdbc.queryForObject("select unit_id from rental_orders where id = ?", Long.class, orderId);
    }

    private List<String> auditActions(String entityType, long entityId) {
        entityManager.flush();
        return jdbc.queryForList("select action from audit_logs where entity_type = ? and entity_id = ? order by id",
                String.class, entityType, String.valueOf(entityId));
    }

    private String auditReason(long proposalId) {
        entityManager.flush();
        return jdbc.queryForObject("select reason from audit_logs where entity_type = 'ProposalFeedback' "
                + "and entity_id = ?", String.class, String.valueOf(proposalId));
    }

    private List<Map<String, Object>> notificationsOf(long accountId) {
        entityManager.flush();
        return jdbc.queryForList("select type, body from notifications where account_id = ?", accountId);
    }

    private long notificationCount() {
        entityManager.flush();
        return jdbc.queryForObject("select count(*) from notifications", Long.class);
    }

    private long orderNotificationCount() {
        entityManager.flush();
        return jdbc.queryForObject("select count(*) from order_notifications where order_id = ?", Long.class, orderId);
    }

    private static void loginAs(String email) {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(email, null, List.of()));
    }
}
