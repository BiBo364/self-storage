package vn.lemar.selfstorage.tenancy.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import vn.lemar.selfstorage.checkout.domain.CheckoutRecord;
import vn.lemar.selfstorage.handover.domain.RentalContract;

/** Guard trạng thái yêu cầu trả kho, biên bản trả kho và gia hạn hợp đồng. */
class PostRentalDomainTest {

    private static final Instant NOW = Instant.parse("2026-11-06T03:00:00Z");

    @Test
    void returnRequestLifecycle() {
        ReturnRequest request = ReturnRequest.open(1L, 2L, 3L, LocalDate.of(2026, 11, 10), null);
        assertThat(request.isOpen()).isTrue();
        assertThatThrownBy(() -> request.complete(NOW)).isInstanceOf(IllegalStateException.class);
        request.assign(5L, 9L);
        request.assign(5L, 10L);
        assertThat(request.getAppointmentId()).isEqualTo(10L);
        request.complete(NOW);
        assertThat(request.getStatus()).isEqualTo(ReturnRequest.COMPLETED);
        assertThatThrownBy(() -> request.cancel("x")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> request.assign(5L, 11L)).isInstanceOf(IllegalStateException.class);

        ReturnRequest other = ReturnRequest.open(1L, 2L, 3L, LocalDate.of(2026, 11, 10), "x");
        other.cancel("Khách huỷ");
        assertThat(other.isOpen()).isFalse();
        assertThat(other.getCancelReason()).isEqualTo("Khách huỷ");
    }

    @Test
    void checkoutRecordLifecycle() {
        CheckoutRecord record = CheckoutRecord.open(1L, 2L, 3L, 4L);
        assertThat(record.readyToConfirm()).isFalse();
        assertThatThrownBy(() -> record.complete(NOW)).isInstanceOf(IllegalStateException.class);
        record.inspect(false, false, null, null, null, null, null, NOW);
        assertThat(record.getResult()).isEqualTo(CheckoutRecord.PENDING_ITEMS);
        assertThat(record.getPhotos()).isEmpty();
        assertThatThrownBy(() -> record.settle(BigDecimal.TEN, BigDecimal.ONE, BigDecimal.ONE, null, NOW))
                .isInstanceOf(IllegalStateException.class);
        record.inspect(true, true, new BigDecimal("5"), "x", 1, List.of("u"), "n", NOW);
        assertThat(record.readyToConfirm()).isTrue();
        record.settle(BigDecimal.TEN, new BigDecimal("20"), BigDecimal.ZERO, 99L, NOW);
        assertThat(record.getResult()).isEqualTo(CheckoutRecord.AWAITING_PAYMENT);
        assertThatThrownBy(() -> record.inspect(true, false, null, null, 1, List.of(), null, NOW))
                .isInstanceOf(IllegalStateException.class);
        record.complete(NOW);
        assertThat(record.getResult()).isEqualTo(CheckoutRecord.COMPLETED);
        assertThatThrownBy(() -> record.complete(NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void contractExtendAndEndOnlyWhenActive() {
        RentalContract contract = RentalContract.draft(1L, 2L, 3L, "HD", "v1", BigDecimal.TEN, BigDecimal.TEN, 3,
                LocalDate.of(2026, 1, 1));
        assertThatThrownBy(() -> contract.extend(1)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(contract::end).isInstanceOf(IllegalStateException.class);
        contract.sign("pdf", NOW);
        contract.activate();
        assertThatThrownBy(() -> contract.extend(0)).isInstanceOf(IllegalArgumentException.class);
        contract.extend(2);
        assertThat(contract.getPeriod()).isEqualTo(5);
        assertThat(contract.getEndDate()).isEqualTo(LocalDate.of(2026, 5, 31));
        contract.end();
        assertThat(contract.getStatus().dbValue()).isEqualTo("Ended");
    }
}
