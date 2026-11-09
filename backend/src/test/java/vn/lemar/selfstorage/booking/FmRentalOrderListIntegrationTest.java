package vn.lemar.selfstorage.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import vn.lemar.selfstorage.booking.application.ProposalQueryService;
import vn.lemar.selfstorage.booking.application.dto.FmRentalOrderPageResponse;
import vn.lemar.selfstorage.booking.application.dto.FmRentalOrderSummaryResponse;
import vn.lemar.selfstorage.booking.application.exception.RentalOrderValidationException;

/** Danh sách đơn thuê FM chạy thật trên Postgres: sort, filter, flag repropose, paging, facility scope. */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@Transactional
class FmRentalOrderListIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private ProposalQueryService service;
    @Autowired
    private JdbcTemplate jdbc;

    private long customerId;
    private long q7;
    private long td;
    private long unitId;
    private long tdUnitId;
    private Instant base;

    @BeforeEach
    void setUp() {
        customerId = accountId("customer1@lemar.vn");
        q7 = jdbc.queryForObject("select id from facilities where code = 'Q7'", Long.class);
        td = jdbc.queryForObject("select id from facilities where code = 'TD'", Long.class);
        unitId = jdbc.queryForObject("select id from storage_units where code = 'A-01'", Long.class);
        tdUnitId = jdbc.queryForObject("select id from storage_units where code = 'A-01'", Long.class);
        base = Instant.now().plus(1, ChronoUnit.DAYS);
        loginAs("fm1@lemar.vn");
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void listComputesFlagsAndSortsByExpiry() {
        long pendingRejected = seedOrder(q7, "Pending", base.plus(2, ChronoUnit.DAYS), false, "Rejected", "Lý do");
        long pendingWaiting = seedOrder(q7, "Pending", base.plus(1, ChronoUnit.DAYS), false, "Pending", null);
        long pendingNoProposal = seedOrder(q7, "Pending", base.plus(3, ChronoUnit.DAYS), false, null, null);
        long canceled = seedOrder(q7, "Canceled", base.plus(4, ChronoUnit.DAYS), false, "Rejected", "Huỷ");
        long depositedNeeds = seedOrder(q7, "Deposited", base.plus(5, ChronoUnit.DAYS), true, "Rejected", "Cũ");

        FmRentalOrderPageResponse page = service.ordersForFacilityManager(q7, null, 0, 20);

        assertThat(page.total()).isEqualTo(5);
        assertThat(page.items()).extracting(FmRentalOrderSummaryResponse::id)
                .containsExactly(pendingWaiting, pendingRejected, pendingNoProposal, canceled, depositedNeeds);

        Map<Long, FmRentalOrderSummaryResponse> byId = page.items().stream()
                .collect(java.util.stream.Collectors.toMap(FmRentalOrderSummaryResponse::id, item -> item));
        assertThat(byId.get(pendingRejected).needsRepropose()).isTrue();
        assertThat(byId.get(pendingRejected).lastProposal().status()).isEqualTo("Rejected");
        assertThat(byId.get(pendingRejected).rejectionCount()).isEqualTo(1);
        assertThat(byId.get(pendingWaiting).needsRepropose()).isFalse();
        assertThat(byId.get(pendingNoProposal).needsRepropose()).isTrue();
        assertThat(byId.get(canceled).needsRepropose()).isFalse();
        assertThat(byId.get(depositedNeeds).needsReproposal()).isTrue();
        assertThat(byId.get(depositedNeeds).needsRepropose()).isTrue();
        assertThat(page.items().get(0).maxRejectionCount()).isEqualTo(3);
    }

    @Test
    void listFiltersByStatusAndPaging() {
        long first = seedOrder(q7, "Pending", base.plus(1, ChronoUnit.DAYS), false, "Rejected", null);
        long second = seedOrder(q7, "Pending", base.plus(2, ChronoUnit.DAYS), false, "Rejected", null);
        seedOrder(q7, "Done", base.plus(3, ChronoUnit.DAYS), false, null, null);

        FmRentalOrderPageResponse pending = service.ordersForFacilityManager(q7, "Pending", 0, 1);

        assertThat(pending.total()).isEqualTo(2);
        assertThat(pending.items()).extracting(FmRentalOrderSummaryResponse::id).containsExactly(first);
        assertThat(service.ordersForFacilityManager(q7, "Done", 0, 20).items())
                .extracting(FmRentalOrderSummaryResponse::id).hasSize(1);
        assertThatThrownBy(() -> service.ordersForFacilityManager(q7, "Bogus", 0, 20))
                .isInstanceOf(RentalOrderValidationException.class);
    }

    @Test
    void listRejectsAnotherFacility() {
        seedOrder(td, "Pending", base, false, "Rejected", null);

        assertThatThrownBy(() -> service.ordersForFacilityManager(td, null, 0, 20))
                .isInstanceOf(vn.lemar.selfstorage.identity.application.exception.ForbiddenException.class);
    }

    private long seedOrder(long facilityId, String status, Instant expiresAt, boolean needsReproposal,
                           String proposalStatus, String note) {
        Long requestId = jdbc.queryForObject("""
                insert into rental_requests (facility_id, customer_name, normalized_customer_email,
                    customer_phone, unit_type_id, start_date, period, unit_id, status)
                values (?, ?, ?, '0900000000', (select id from unit_types where name = 'Small'),
                    current_date + 7, 3, ?, 'Converted')
                returning id""", Long.class, facilityId, "Order Test " + UUID.randomUUID(),
                "order" + UUID.randomUUID() + "@example.com", unitId);
        long orderId = jdbc.queryForObject("""
                insert into rental_orders (request_id, customer_id, unit_id, status, expires_at, needs_reproposal)
                values (?, ?, ?, ?, ?, ?) returning id""",
                Long.class, requestId, customerId, unitId, status, Timestamp.from(expiresAt), needsReproposal);
        if (proposalStatus != null) {
            jdbc.update("""
                    insert into proposal_feedbacks (order_id, customer_id, unit_id, status, note, created_at)
                    values (?, ?, ?, ?, ?, now())""", orderId, customerId, unitId, proposalStatus, note);
        }
        return orderId;
    }

    private long accountId(String email) {
        return jdbc.queryForObject("select id from accounts where email = ?", Long.class, email);
    }

    private static void loginAs(String email) {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(email, null, List.of()));
    }
}
