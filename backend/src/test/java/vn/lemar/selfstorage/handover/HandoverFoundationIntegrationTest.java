package vn.lemar.selfstorage.handover;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.within;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.lemar.selfstorage.handover.application.HandoverService;
import vn.lemar.selfstorage.handover.domain.HandoverRecord;
import vn.lemar.selfstorage.handover.domain.HandoverResult;
import vn.lemar.selfstorage.handover.domain.RentalContract;
import vn.lemar.selfstorage.handover.domain.RentalContractStatus;
import vn.lemar.selfstorage.handover.repository.HandoverRecordRepository;
import vn.lemar.selfstorage.handover.repository.RentalContractRepository;

/**
 * Data layer + service Flow 2.1/2.2 chạy thật trên Postgres: mapping hai entity, công thức
 * {@code due_at}, listener idempotent/thiếu record, luật huỷ record + contract, audit actor System.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@Transactional
class HandoverFoundationIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private HandoverService handoverService;
    @Autowired
    private HandoverRecordRepository handoverRecords;
    @Autowired
    private RentalContractRepository contracts;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private EntityManager entityManager;

    @Test
    void handoverRecordRoundTripMapsAllColumns() {
        Fixture fixture = fixture(Instant.now().plus(30, ChronoUnit.DAYS));
        Instant dueAt = Instant.now().plus(2, ChronoUnit.DAYS);
        long recordId = seedHandover(fixture, "IN_PROGRESS", dueAt);

        HandoverRecord record = handoverRecords.findByAppointmentId(fixture.appointmentId()).orElseThrow();

        assertThat(record.getId()).isEqualTo(recordId);
        assertThat(record.getOrderId()).isEqualTo(fixture.orderId());
        assertThat(record.getAppointmentId()).isEqualTo(fixture.appointmentId());
        assertThat(record.getUnitId()).isEqualTo(fixture.unitId());
        assertThat(record.getIdentityStatus()).isEqualTo("Verified");
        assertThat(record.getIdentityVerifiedAt()).isNotNull();
        assertThat(record.getInspectionStatus()).isEqualTo("Agreed");
        assertThat(record.getUnitInspectedAt()).isNotNull();
        assertThat(record.getInspectionNotes()).isEqualTo("Khoang sạch");
        assertThat(record.getInspectionPhotos()).containsExactly("front.jpg", "back.jpg");
        assertThat(record.getResult()).isEqualTo(HandoverResult.IN_PROGRESS);
        assertThat(record.getRejectReason()).isNull();
        assertThat(record.getDueAt()).isCloseTo(dueAt, within(1, ChronoUnit.SECONDS));
        assertThat(record.getCreatedAt()).isNotNull();
        assertThat(record.getUpdatedAt()).isNotNull();
        assertThat(handoverRecords.findByOrderId(fixture.orderId()))
                .extracting(HandoverRecord::getId).containsExactly(recordId);
        assertThat(handoverRecords.findOpenByOrderId(fixture.orderId())).isPresent();
    }

    @Test
    void rentalContractRoundTripMapsStatusAndColumns() {
        Fixture fixture = fixture(Instant.now().plus(30, ChronoUnit.DAYS));
        long contractId = seedContract(fixture, "Draft");
        LocalDate today = jdbc.queryForObject("select current_date", LocalDate.class);

        RentalContract contract = contracts
                .findByOrderIdAndStatusIn(fixture.orderId(), List.of(RentalContractStatus.DRAFT))
                .get(0);

        assertThat(contract.getId()).isEqualTo(contractId);
        assertThat(contract.getOrderId()).isEqualTo(fixture.orderId());
        assertThat(contract.getCustomerId()).isEqualTo(fixture.customerId());
        assertThat(contract.getUnitId()).isEqualTo(fixture.unitId());
        assertThat(contract.getCode()).startsWith("CT-");
        assertThat(contract.getTermsVersion()).isEqualTo("v1");
        assertThat(contract.getMonthlyPrice()).isEqualByComparingTo(new BigDecimal("500000.00"));
        assertThat(contract.getDepositAmount()).isEqualByComparingTo(new BigDecimal("500000.00"));
        assertThat(contract.getPeriod()).isEqualTo(3);
        assertThat(contract.getStartDate()).isEqualTo(today.plusDays(1));
        assertThat(contract.getStartDateOverrideRequested()).isEqualTo(today.plusDays(2));
        assertThat(contract.getStartDateOverrideReason()).isEqualTo("Thỏa thuận");
        assertThat(contract.getStartDateOverrideStatus()).isEqualTo("Pending");
        assertThat(contract.getEndDate()).isEqualTo(today.plusDays(100));
        assertThat(contract.getSignedAt()).isNotNull();
        assertThat(contract.getSignature()).isNull();
        assertThat(contract.getDocumentUrl()).isEqualTo("doc.pdf");
        assertThat(contract.getPdfUrl()).isEqualTo("signed.pdf");
        assertThat(contract.getStatus()).isEqualTo(RentalContractStatus.DRAFT);
    }

    @Test
    void arrivedSetsDueAtFromPolicy() {
        Fixture fixture = fixture(Instant.now().plus(30, ChronoUnit.DAYS));
        seedHandover(fixture, "IN_PROGRESS", null);
        Instant before = Instant.now();

        handoverService.onAppointmentArrived(fixture.appointmentId());
        Instant after = Instant.now();
        entityManager.flush();

        Instant dueAt = dueAt(fixture.appointmentId());
        assertThat(dueAt).isBetween(before.plus(1, ChronoUnit.DAYS).minusSeconds(2),
                after.plus(1, ChronoUnit.DAYS).plusSeconds(2));
    }

    @Test
    void arrivedCapsDueAtAtOrderExpiry() {
        Instant expiresAt = Instant.now().plus(2, ChronoUnit.HOURS);
        Fixture fixture = fixture(expiresAt);
        seedHandover(fixture, "IN_PROGRESS", null);

        handoverService.onAppointmentArrived(fixture.appointmentId());
        entityManager.flush();

        Instant storedExpiry = jdbc.queryForObject(
                "select expires_at from rental_orders where id = ?", Timestamp.class, fixture.orderId()).toInstant();
        assertThat(dueAt(fixture.appointmentId())).isEqualTo(storedExpiry);
    }

    @Test
    void arrivedWithoutOrderExpiryUsesPolicy() {
        Fixture fixture = fixture(null);
        seedHandover(fixture, "IN_PROGRESS", null);
        Instant before = Instant.now();

        handoverService.onAppointmentArrived(fixture.appointmentId());
        Instant after = Instant.now();
        entityManager.flush();

        Instant dueAt = dueAt(fixture.appointmentId());
        assertThat(dueAt).isBetween(before.plus(1, ChronoUnit.DAYS).minusSeconds(2),
                after.plus(1, ChronoUnit.DAYS).plusSeconds(2));
    }

    @Test
    void arrivedIsIdempotentWhenDueAtAlreadySet() {
        Fixture fixture = fixture(Instant.now().plus(30, ChronoUnit.DAYS));
        Instant fixed = Instant.now().plus(3, ChronoUnit.DAYS);
        seedHandover(fixture, "IN_PROGRESS", fixed);

        handoverService.onAppointmentArrived(fixture.appointmentId());
        entityManager.flush();

        assertThat(dueAt(fixture.appointmentId())).isCloseTo(fixed, within(1, ChronoUnit.SECONDS));
        assertThat(auditCount("HANDOVER_CANCELED")).isZero();
        assertThat(auditCount("CONTRACT_CANCELED")).isZero();
    }

    @Test
    void arrivedSkipsClosedRecord() {
        Fixture fixture = fixture(Instant.now().plus(30, ChronoUnit.DAYS));
        seedHandover(fixture, "CANCELED", null);

        handoverService.onAppointmentArrived(fixture.appointmentId());
        entityManager.flush();

        assertThat(dueAt(fixture.appointmentId())).isNull();
    }

    @Test
    void arrivedWithoutRecordIsNoOp() {
        Fixture fixture = fixture(Instant.now().plus(30, ChronoUnit.DAYS));

        assertThatCode(() -> handoverService.onAppointmentArrived(fixture.appointmentId()))
                .doesNotThrowAnyException();
        assertThat(handoverRecords.findByOrderId(fixture.orderId())).isEmpty();
    }

    @Test
    void cancelByAppointmentIdCancelsRecordAndContractOnce() {
        Fixture fixture = fixture(Instant.now().plus(30, ChronoUnit.DAYS));
        long recordId = seedHandover(fixture, "IN_PROGRESS", null);
        long contractId = seedContract(fixture, "Draft");

        handoverService.cancelByAppointmentId(fixture.appointmentId(), "Khách không đến");
        entityManager.flush();

        assertThat(handoverResult(recordId)).isEqualTo("CANCELED");
        assertThat(rejectReason(recordId)).isEqualTo("Khách không đến");
        assertThat(contractStatus(contractId)).isEqualTo("Canceled");
        assertThat(auditCount("HANDOVER_CANCELED")).isEqualTo(1);
        assertThat(auditCount("CONTRACT_CANCELED")).isEqualTo(1);
        Map<String, Object> handoverAudit = jdbc.queryForMap(
                "select actor_account_id, reason, old_value ->> 'result' as old_result, "
                        + "new_value ->> 'result' as new_result, "
                        + "new_value ->> 'rejectReason' as reject_reason "
                        + "from audit_logs where action = 'HANDOVER_CANCELED'");
        assertThat(handoverAudit.get("actor_account_id")).isNull();
        assertThat(handoverAudit.get("reason")).isEqualTo("Khách không đến");
        assertThat(handoverAudit.get("old_result")).isEqualTo("IN_PROGRESS");
        assertThat(handoverAudit.get("new_result")).isEqualTo("CANCELED");
        assertThat(handoverAudit.get("reject_reason")).isEqualTo("Khách không đến");
        Map<String, Object> contractAudit = jdbc.queryForMap(
                "select actor_account_id, reason, old_value ->> 'status' as old_status, "
                        + "new_value ->> 'status' as new_status "
                        + "from audit_logs where action = 'CONTRACT_CANCELED'");
        assertThat(contractAudit.get("actor_account_id")).isNull();
        assertThat(contractAudit.get("reason")).isEqualTo("Khách không đến");
        assertThat(contractAudit.get("old_status")).isEqualTo("Draft");
        assertThat(contractAudit.get("new_status")).isEqualTo("Canceled");

        handoverService.cancelByOrderId(fixture.orderId(), "Khách không đến");
        entityManager.flush();

        assertThat(auditCount("HANDOVER_CANCELED")).isEqualTo(1);
        assertThat(auditCount("CONTRACT_CANCELED")).isEqualTo(1);
    }

    @Test
    void cancelByOrderIdCancelsSignedContractWhenRecordAlreadyClosed() {
        Fixture fixture = fixture(Instant.now().plus(30, ChronoUnit.DAYS));
        long recordId = seedHandover(fixture, "CANCELED", null);
        long contractId = seedContract(fixture, "Signed");

        handoverService.cancelByOrderId(fixture.orderId(), "Chưa hoàn tất bàn giao trong hạn");
        entityManager.flush();

        assertThat(handoverResult(recordId)).isEqualTo("CANCELED");
        assertThat(contractStatus(contractId)).isEqualTo("Canceled");
        assertThat(auditCount("HANDOVER_CANCELED")).isZero();
        assertThat(auditCount("CONTRACT_CANCELED")).isEqualTo(1);
    }

    @Test
    void cancelByAppointmentIdLeavesCompletedRecordAndContract() {
        Fixture fixture = fixture(Instant.now().plus(30, ChronoUnit.DAYS));
        long recordId = seedHandover(fixture, "COMPLETED", null);
        long contractId = seedContract(fixture, "Signed");

        handoverService.cancelByAppointmentId(fixture.appointmentId(), "Khách không đến");
        entityManager.flush();

        assertThat(handoverResult(recordId)).isEqualTo("COMPLETED");
        assertThat(contractStatus(contractId)).isEqualTo("Signed");
        assertThat(auditCount("HANDOVER_CANCELED")).isZero();
        assertThat(auditCount("CONTRACT_CANCELED")).isZero();
    }

    @Test
    void cancelByOrderIdLeavesActiveContract() {
        Fixture fixture = fixture(Instant.now().plus(30, ChronoUnit.DAYS));
        long recordId = seedHandover(fixture, "IN_PROGRESS", null);
        long contractId = seedContract(fixture, "Active");

        handoverService.cancelByOrderId(fixture.orderId(), "Chưa hoàn tất bàn giao trong hạn");
        entityManager.flush();

        assertThat(handoverResult(recordId)).isEqualTo("CANCELED");
        assertThat(contractStatus(contractId)).isEqualTo("Active");
        assertThat(auditCount("HANDOVER_CANCELED")).isEqualTo(1);
        assertThat(auditCount("CONTRACT_CANCELED")).isZero();
    }

    private Instant dueAt(long appointmentId) {
        Timestamp due = jdbc.queryForObject(
                "select due_at from handover_records where appointment_id = ?", Timestamp.class, appointmentId);
        return due == null ? null : due.toInstant();
    }

    private String handoverResult(long recordId) {
        return jdbc.queryForObject("select result from handover_records where id = ?", String.class, recordId);
    }

    private String rejectReason(long recordId) {
        return jdbc.queryForObject("select reject_reason from handover_records where id = ?", String.class, recordId);
    }

    private String contractStatus(long contractId) {
        return jdbc.queryForObject("select status from rental_contracts where id = ?", String.class, contractId);
    }

    private long auditCount(String action) {
        return jdbc.queryForObject("select count(*) from audit_logs where action = ?", Long.class, action);
    }

    private long seedHandover(Fixture fixture, String result, Instant dueAt) {
        return jdbc.queryForObject("""
                insert into handover_records (order_id, appointment_id, unit_id, identity_status,
                    identity_verified_at, inspection_status, unit_inspected_at, inspection_notes,
                    inspection_photos, result, due_at)
                values (?, ?, ?, 'Verified', now(), 'Agreed', now(), 'Khoang sạch',
                    array['front.jpg', 'back.jpg'], ?, ?)
                returning id""", Long.class, fixture.orderId(), fixture.appointmentId(), fixture.unitId(),
                result, timestamp(dueAt));
    }

    private long seedContract(Fixture fixture, String status) {
        return jdbc.queryForObject("""
                insert into rental_contracts (order_id, customer_id, unit_id, code, terms_version,
                    monthly_price, deposit_amount, period, start_date, start_date_override_requested,
                    start_date_override_reason, start_date_override_status, end_date, signed_at,
                    signature, document_url, pdf_url, status)
                values (?, ?, ?, ?, 'v1', 500000, 500000, 3, current_date + 1, current_date + 2,
                    'Thỏa thuận', 'Pending', current_date + 100, now(), null, 'doc.pdf', 'signed.pdf', ?)
                returning id""", Long.class, fixture.orderId(), fixture.customerId(), fixture.unitId(),
                "CT-" + UUID.randomUUID(), status);
    }

    private Fixture fixture(Instant expiresAt) {
        long customerId = jdbc.queryForObject(
                "select id from accounts where email = 'customer1@lemar.vn'", Long.class);
        long facilityId = jdbc.queryForObject("select id from facilities where code = 'Q7'", Long.class);
        long unitId = jdbc.queryForObject("select id from storage_units where code = 'A-01'", Long.class);
        Long requestId = jdbc.queryForObject("""
                insert into rental_requests (facility_id, customer_name, normalized_customer_email,
                    customer_phone, unit_type_id, start_date, period, unit_id, status)
                values (?, 'Khách Test', 'customer1@lemar.vn', '0900000000',
                    (select id from unit_types where name = 'Small'), current_date + 7, 3, ?, 'Converted')
                returning id""", Long.class, facilityId, unitId);
        long orderId = jdbc.queryForObject("""
                insert into rental_orders (request_id, customer_id, unit_id, status, expires_at)
                values (?, ?, ?, 'InProgress', ?) returning id""",
                Long.class, requestId, customerId, unitId, timestamp(expiresAt));
        LocalDate day = LocalDate.now().plusDays(3);
        long appointmentId = jdbc.queryForObject("""
                insert into appointments (customer_id, facility_id, type, date, started_at, end_at, status)
                values (?, ?, 'CHECKIN', ?, ?::timestamptz, ?::timestamptz, 'Pending') returning id""",
                Long.class, customerId, facilityId, day, day + "T08:00:00+07:00", day + "T10:00:00+07:00");
        jdbc.update("insert into rental_appointments (appointment_id, order_id) values (?, ?)",
                appointmentId, orderId);
        return new Fixture(orderId, appointmentId, unitId, customerId);
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private record Fixture(long orderId, long appointmentId, long unitId, long customerId) {
    }
}
