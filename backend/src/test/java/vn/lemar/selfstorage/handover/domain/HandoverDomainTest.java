package vn.lemar.selfstorage.handover.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** Guard domain của HandoverRecord/RentalContract — không cần Spring hay DB. */
class HandoverDomainTest {

    @Test
    void cancelOpenRecordSetsReason() {
        HandoverRecord record = record(HandoverResult.IN_PROGRESS, null);

        record.cancel("Khách không đến");

        assertThat(record.getResult()).isEqualTo(HandoverResult.CANCELED);
        assertThat(record.getRejectReason()).isEqualTo("Khách không đến");
        assertThat(record.getUpdatedAt()).isNotNull();
    }

    @Test
    void cancelRejectsRecordNotInProgress() {
        HandoverRecord completed = record(HandoverResult.COMPLETED, null);
        HandoverRecord canceled = record(HandoverResult.CANCELED, null);

        assertThatThrownBy(() -> completed.cancel("lý do"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("không còn IN_PROGRESS");
        assertThatThrownBy(() -> canceled.cancel("lý do"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("không còn IN_PROGRESS");
    }

    @Test
    void setDueAtOnceThenRejectSecondCall() {
        HandoverRecord record = record(HandoverResult.IN_PROGRESS, null);
        Instant dueAt = Instant.parse("2026-11-10T08:00:00Z");

        record.setDueAt(dueAt);

        assertThat(record.getDueAt()).isEqualTo(dueAt);
        assertThatThrownBy(() -> record.setDueAt(dueAt.plusSeconds(60)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("đã có due_at");
    }

    @Test
    void setDueAtRejectsClosedRecord() {
        HandoverRecord canceled = record(HandoverResult.CANCELED, null);

        assertThatThrownBy(() -> canceled.setDueAt(Instant.now()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("không còn IN_PROGRESS");
    }

    @Test
    void contractCanBeCanceledFromDraftAndSigned() {
        RentalContract draft = contract(RentalContractStatus.DRAFT);
        RentalContract signed = contract(RentalContractStatus.SIGNED);

        draft.cancel();
        signed.cancel();

        assertThat(draft.getStatus()).isEqualTo(RentalContractStatus.CANCELED);
        assertThat(signed.getStatus()).isEqualTo(RentalContractStatus.CANCELED);
    }

    @Test
    void contractCancelRejectsActiveEndedAndCanceled() {
        for (RentalContractStatus status : new RentalContractStatus[]{
                RentalContractStatus.ACTIVE, RentalContractStatus.ENDED, RentalContractStatus.CANCELED}) {
            RentalContract contract = contract(status);
            assertThatThrownBy(contract::cancel)
                    .as("status %s", status)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("không huỷ được");
        }
    }

    private static HandoverRecord record(HandoverResult result, Instant dueAt) {
        HandoverRecord record = new HandoverRecord();
        ReflectionTestUtils.setField(record, "result", result);
        ReflectionTestUtils.setField(record, "dueAt", dueAt);
        return record;
    }

    private static RentalContract contract(RentalContractStatus status) {
        RentalContract contract = new RentalContract();
        ReflectionTestUtils.setField(contract, "status", status);
        return contract;
    }

    @Test
    void extendRecomputesEndDateFromStartDateAtMonthEnd() {
        RentalContract contract = RentalContract.draft(1L, 2L, 3L, "HD-T", "builtin-v1",
                new java.math.BigDecimal("100000"), new java.math.BigDecimal("300000"), 1,
                java.time.LocalDate.of(2026, 1, 31));
        assertThat(contract.getEndDate()).isEqualTo(java.time.LocalDate.of(2026, 2, 27));
        ReflectionTestUtils.setField(contract, "status", RentalContractStatus.ACTIVE);

        contract.extend(1);
        contract.extend(1);

        // Gia hạn phải ra cùng end_date với hợp đồng 3 tháng ký từ đầu (31/1 + 3 tháng − 1 ngày = 29/4); cộng tháng
        // vào end_date cũ sẽ trôi về 27/4 (27/2 → 27/3 → 27/4).
        RentalContract threeMonths = RentalContract.draft(1L, 2L, 3L, "HD-T3", "builtin-v1",
                new java.math.BigDecimal("100000"), new java.math.BigDecimal("300000"), 3,
                java.time.LocalDate.of(2026, 1, 31));
        assertThat(contract.getPeriod()).isEqualTo(3);
        assertThat(contract.getEndDate()).isEqualTo(threeMonths.getEndDate())
                .isEqualTo(java.time.LocalDate.of(2026, 4, 29));
    }
}
