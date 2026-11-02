package vn.lemar.selfstorage.booking.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Read model queue: join order/proposal thật trên Postgres, không mock SQL. */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@Transactional
class BookingReadRepositoryQueueTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private BookingReadRepository repository;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void enrichesRequestWithOrderAndLastProposal() {
        long customer = accountId("customer1@lemar.vn");
        long facility = facilityId("Q7");
        long unitType = unitTypeId("Small");
        long unit = unitId("A-01");
        long requestId = jdbc.queryForObject("""
                insert into rental_requests (facility_id, customer_name, normalized_customer_email,
                    customer_phone, unit_type_id, start_date, period, status)
                values (?, 'Read Test', 'customer1@lemar.vn', '0901234567', ?, current_date, 6, 'Converted')
                returning id
                """, Long.class, facility, unitType);
        long orderId = jdbc.queryForObject("""
                insert into rental_orders (request_id, customer_id, unit_id, status)
                values (?, ?, ?, 'Pending') returning id
                """, Long.class, requestId, customer, unit);
        jdbc.update("""
                insert into proposal_feedbacks (order_id, customer_id, unit_id, status, note, created_at)
                values (?, ?, ?, 'Rejected', 'Không thích', now())
                """, orderId, customer, unit);

        Map<Long, BookingReadRepository.QueueEnrichment> result =
                repository.findQueueEnrichments(List.of(requestId));

        assertThat(result).containsKey(requestId);
        BookingReadRepository.QueueEnrichment enrichment = result.get(requestId);
        assertThat(enrichment.orderId()).isEqualTo(orderId);
        assertThat(enrichment.orderStatus()).isEqualTo("Pending");
        assertThat(enrichment.needsReproposal()).isFalse();
        assertThat(enrichment.lastProposalUnitCode()).isEqualTo("A-01");
        assertThat(enrichment.lastProposalStatus()).isEqualTo("Rejected");
        assertThat(enrichment.lastProposalNote()).isEqualTo("Không thích");
        assertThat(enrichment.rejectionCount()).isEqualTo(1);
        assertThat(enrichment.pendingProposalCount()).isZero();
        assertThat(repository.findQueueEnrichments(List.of())).isEmpty();
    }

    private long accountId(String email) {
        return jdbc.queryForObject("select id from accounts where email = ?", Long.class, email);
    }

    private long facilityId(String code) {
        return jdbc.queryForObject("select id from facilities where code = ?", Long.class, code);
    }

    private long unitTypeId(String name) {
        return jdbc.queryForObject("select id from unit_types where name = ?", Long.class, name);
    }

    private long unitId(String code) {
        return jdbc.queryForObject("select id from storage_units where code = ?", Long.class, code);
    }
}
