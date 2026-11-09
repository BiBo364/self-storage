package vn.lemar.selfstorage.handover.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Guard trạng thái của biên bản, hợp đồng và khoá truy cập trong Flow 2.2–2.5. */
class HandoverFlowDomainTest {

    private static final Instant NOW = Instant.parse("2026-11-06T03:00:00Z");

    @Test
    void identityAndInspectionFollowTheChecklistOrder() {
        HandoverRecord record = HandoverRecord.open(1L, 2L, 3L);
        assertThatThrownBy(() -> record.recordInspection("x", List.of(), NOW))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> record.agreeInspection(null, NOW)).isInstanceOf(IllegalStateException.class);

        record.verifyIdentity(false, NOW);
        assertThat(record.getIdentityStatus()).isEqualTo(HandoverRecord.FAILED);
        assertThat(record.getIdentityVerifiedAt()).isNull();
        record.verifyIdentity(true, NOW);
        assertThat(record.identityVerified()).isTrue();
        assertThatThrownBy(() -> record.verifyIdentity(true, NOW)).isInstanceOf(IllegalStateException.class);

        record.recordInspection("Sạch", null, NOW);
        assertThat(record.getInspectionPhotos()).isEmpty();
        record.agreeInspection("  ", NOW);
        assertThat(record.getInspectionNotes()).isEqualTo("Sạch");
        assertThat(record.inspectionAgreed()).isTrue();
        assertThatThrownBy(() -> record.recordInspection("y", List.of(), NOW))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void customerNoteWithoutStaffNotesBecomesTheNote() {
        HandoverRecord record = HandoverRecord.open(1L, 2L, 3L);
        record.verifyIdentity(true, NOW);
        record.agreeInspection("Cửa kẹt", NOW);
        assertThat(record.getInspectionNotes()).isEqualTo("Khách lưu ý: Cửa kẹt");
    }

    @Test
    void confirmAndCompleteRunOnce() {
        HandoverRecord record = HandoverRecord.open(1L, 2L, 3L);
        assertThatThrownBy(() -> record.confirmByCustomer(AccessChoice.ACCESS_CODE, NOW))
                .isInstanceOf(IllegalStateException.class);
        assertThat(record.accessChoice()).isNull();
        record.verifyIdentity(true, NOW);
        assertThatThrownBy(() -> record.confirmByCustomer(AccessChoice.BOTH, NOW))
                .isInstanceOf(IllegalStateException.class);
        record.agreeInspection(null, NOW);
        record.confirmByCustomer(AccessChoice.BOTH, NOW);
        assertThat(record.accessChoice()).isEqualTo(AccessChoice.BOTH);
        record.complete(NOW);
        assertThat(record.getResult()).isEqualTo(HandoverResult.COMPLETED);
        assertThat(record.isOpen()).isFalse();
        assertThatThrownBy(() -> record.complete(NOW)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> record.reject("x", NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectClosesRecord() {
        HandoverRecord record = HandoverRecord.open(1L, 2L, 3L);
        record.reject("Ẩm", NOW);
        assertThat(record.getResult()).isEqualTo(HandoverResult.REJECTED);
        assertThat(record.getInspectionStatus()).isEqualTo(HandoverRecord.REJECTED);
        assertThat(record.getRejectReason()).isEqualTo("Ẩm");
    }

    @Test
    void accessChoiceMapping() {
        assertThat(AccessChoice.fromDbValue("PhysicalKey")).isEqualTo(AccessChoice.PHYSICAL_KEY);
        assertThat(AccessChoice.ACCESS_CODE.needsCode()).isTrue();
        assertThat(AccessChoice.ACCESS_CODE.needsPhysicalKey()).isFalse();
        assertThat(AccessChoice.PHYSICAL_KEY.needsCode()).isFalse();
        assertThat(AccessChoice.BOTH.needsCode()).isTrue();
        assertThat(AccessChoice.BOTH.needsPhysicalKey()).isTrue();
        assertThatThrownBy(() -> AccessChoice.fromDbValue("Magic")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void contractLifecycleAndStartDateOverride() {
        RentalContract contract = RentalContract.draft(1L, 2L, 3L, "HD-1", "builtin-v1", new BigDecimal("500000"),
                new BigDecimal("500000"), 3, LocalDate.of(2026, 11, 6));
        assertThat(contract.getEndDate()).isEqualTo(LocalDate.of(2027, 2, 5));
        assertThatThrownBy(contract::approveStartDateOverride).isInstanceOf(IllegalStateException.class);

        contract.requestStartDateOverride(LocalDate.of(2026, 11, 10), "Dời ngày");
        assertThat(contract.hasPendingOverride()).isTrue();
        assertThatThrownBy(() -> contract.requestStartDateOverride(LocalDate.of(2026, 11, 11), "x"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> contract.sign("pdf", NOW)).isInstanceOf(IllegalStateException.class);
        contract.rejectStartDateOverride();
        assertThat(contract.getStartDate()).isEqualTo(LocalDate.of(2026, 11, 6));
        assertThat(contract.getStartDateOverrideStatus()).isEqualTo(RentalContract.OVERRIDE_REJECTED);

        contract.requestStartDateOverride(LocalDate.of(2026, 11, 10), "Dời ngày");
        contract.approveStartDateOverride();
        assertThat(contract.getStartDate()).isEqualTo(LocalDate.of(2026, 11, 10));
        assertThat(contract.getEndDate()).isEqualTo(LocalDate.of(2027, 2, 9));

        assertThatThrownBy(contract::activate).isInstanceOf(IllegalStateException.class);
        contract.sign("pdf", NOW);
        assertThat(contract.getStatus()).isEqualTo(RentalContractStatus.SIGNED);
        assertThatThrownBy(() -> contract.requestStartDateOverride(LocalDate.of(2026, 12, 1), "x"))
                .isInstanceOf(IllegalStateException.class);
        contract.activate();
        assertThat(contract.getStatus()).isEqualTo(RentalContractStatus.ACTIVE);
    }

    @Test
    void inspectionCorrectionOnlyAfterAgreementWhileOpen() {
        HandoverRecord record = HandoverRecord.open(1L, 2L, 3L);
        record.verifyIdentity(true, NOW);
        record.recordInspection("Sạch", List.of("a"), NOW);
        assertThatThrownBy(() -> record.correctInspection("x", List.of(), NOW))
                .isInstanceOf(IllegalStateException.class);
        record.agreeInspection(null, NOW);
        Instant later = NOW.plusSeconds(60);
        record.correctInspection("Có vết xước", null, later);
        assertThat(record.getInspectionNotes()).isEqualTo("Có vết xước");
        assertThat(record.getInspectionPhotos()).isEmpty();
        assertThat(record.getUnitInspectedAt()).isEqualTo(NOW);
        assertThat(record.getUpdatedAt()).isEqualTo(later);
        record.complete(later);
        assertThatThrownBy(() -> record.correctInspection("y", List.of(), later))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void contractDocumentFreezesOnceAfterSigning() {
        RentalContract contract = RentalContract.draft(1L, 2L, 3L, "HD-2", "builtin-v1", new BigDecimal("500000"),
                new BigDecimal("500000"), 3, LocalDate.of(2026, 11, 6));
        assertThat(contract.documentFrozen()).isFalse();
        assertThatThrownBy(() -> contract.freezeDocument("<html>")).isInstanceOf(IllegalStateException.class);
        contract.sign("pdf", NOW);
        assertThatThrownBy(() -> contract.freezeDocument(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> contract.freezeDocument(null)).isInstanceOf(IllegalArgumentException.class);
        contract.freezeDocument("<html>v1</html>");
        assertThat(contract.documentFrozen()).isTrue();
        assertThat(contract.getDocumentHtml()).isEqualTo("<html>v1</html>");
        assertThatThrownBy(() -> contract.freezeDocument("<html>v2</html>"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(contract.getDocumentHtml()).isEqualTo("<html>v1</html>");
    }

    @Test
    void accessKeyGuards() {
        assertThatThrownBy(() -> UnitAccessKey.physicalKey(1L, 2L, 0, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        UnitAccessKey key = UnitAccessKey.physicalKey(1L, 2L, 2, NOW);
        assertThat(key.getQuantity()).isEqualTo(2);
        UnitAccessKey code = UnitAccessKey.accessCode(1L, 2L, "hash", NOW);
        assertThat(code.getAccessType()).isEqualTo(UnitAccessKey.ACCESS_CODE);
        code.revoke(NOW);
        assertThat(code.getStatus()).isEqualTo(UnitAccessKey.REVOKED);
        assertThatThrownBy(() -> code.revoke(NOW)).isInstanceOf(IllegalStateException.class);
    }
}
