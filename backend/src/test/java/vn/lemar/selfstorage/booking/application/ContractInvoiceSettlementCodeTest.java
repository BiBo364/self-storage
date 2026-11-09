package vn.lemar.selfstorage.booking.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Mã hoá đơn quyết toán trả kho: phí quá hạn bỏ qua khi hợp đồng đã có một trong các mã này. */
class ContractInvoiceSettlementCodeTest {

    @Test
    void recognizesEverySettlementKind() {
        assertThat(ContractInvoiceService.SETTLEMENT_KINDS).containsExactlyInAnyOrder("CLN", "DMG", "KEY", "PEN");
        assertThat(ContractInvoiceService.isSettlementCode("INV-CLN-Q7-20261107-ABC123")).isTrue();
        assertThat(ContractInvoiceService.isSettlementCode("INV-DMG-Q7-20261107-ABC123")).isTrue();
        assertThat(ContractInvoiceService.isSettlementCode("INV-KEY-Q7-20261107-ABC123")).isTrue();
        assertThat(ContractInvoiceService.isSettlementCode("INV-PEN-Q7-20261107-ABC123")).isTrue();
    }

    @Test
    void ignoresOtherContractInvoices() {
        assertThat(ContractInvoiceService.isSettlementCode("INV-OVD-Q7-20261107-ABC123")).isFalse();
        assertThat(ContractInvoiceService.isSettlementCode("INV-SVC-Q7-20261107-ABC123")).isFalse();
        assertThat(ContractInvoiceService.isSettlementCode("INV-EXT-Q7-20261107-ABC123")).isFalse();
        assertThat(ContractInvoiceService.isSettlementCode("INV-PENDING")).isFalse();
        assertThat(ContractInvoiceService.isSettlementCode(null)).isFalse();
    }
}
