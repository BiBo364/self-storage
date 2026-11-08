package vn.lemar.selfstorage.booking.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class BookingDomainTest {

    @ParameterizedTest
    @EnumSource(ProposalStatus.class)
    void proposalStatusRoundTripsThroughConverter(ProposalStatus status) {
        ProposalStatusConverter converter = new ProposalStatusConverter();

        assertThat(converter.convertToEntityAttribute(converter.convertToDatabaseColumn(status))).isEqualTo(status);
    }

    @ParameterizedTest
    @EnumSource(RentalOrderStatus.class)
    void rentalOrderStatusRoundTripsThroughConverter(RentalOrderStatus status) {
        RentalOrderStatusConverter converter = new RentalOrderStatusConverter();

        assertThat(converter.convertToEntityAttribute(converter.convertToDatabaseColumn(status))).isEqualTo(status);
    }

    @ParameterizedTest
    @EnumSource(InvoiceStatus.class)
    void invoiceStatusRoundTripsThroughConverter(InvoiceStatus status) {
        InvoiceStatusConverter converter = new InvoiceStatusConverter();

        assertThat(converter.convertToEntityAttribute(converter.convertToDatabaseColumn(status))).isEqualTo(status);
    }

    @ParameterizedTest
    @EnumSource(InvoiceType.class)
    void invoiceTypeRoundTripsThroughConverter(InvoiceType type) {
        InvoiceTypeConverter converter = new InvoiceTypeConverter();

        assertThat(converter.convertToEntityAttribute(converter.convertToDatabaseColumn(type))).isEqualTo(type);
    }

    @Test
    void convertersPassNullThrough() {
        assertThat(new ProposalStatusConverter().convertToDatabaseColumn(null)).isNull();
        assertThat(new ProposalStatusConverter().convertToEntityAttribute(null)).isNull();
        assertThat(new RentalOrderStatusConverter().convertToDatabaseColumn(null)).isNull();
        assertThat(new RentalOrderStatusConverter().convertToEntityAttribute(null)).isNull();
        assertThat(new InvoiceStatusConverter().convertToDatabaseColumn(null)).isNull();
        assertThat(new InvoiceStatusConverter().convertToEntityAttribute(null)).isNull();
        assertThat(new InvoiceTypeConverter().convertToDatabaseColumn(null)).isNull();
        assertThat(new InvoiceTypeConverter().convertToEntityAttribute(null)).isNull();
    }

    @Test
    void unknownDbValuesAreRejected() {
        assertThatThrownBy(() -> ProposalStatus.fromDbValue("PENDING")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProposalStatus.fromDbValue("Expired")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RentalOrderStatus.fromDbValue("pending"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RentalOrderStatus.fromDbValue("Expired"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> InvoiceStatus.fromDbValue("UNPAID")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> InvoiceType.fromDbValue("deposit")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void onlyCanceledAndDoneOrdersAreClosed() {
        assertThat(RentalOrderStatus.values()).filteredOn(RentalOrderStatus::isClosed)
                .containsExactlyInAnyOrder(RentalOrderStatus.CANCELED, RentalOrderStatus.DONE);
    }

    @Test
    void handledProposalCannotChangeStateAgain() {
        ProposalFeedback proposal = ProposalFeedback.propose(new RentalOrder(), 7L);
        proposal.agree();

        assertThat(proposal.getStatus()).isEqualTo(ProposalStatus.AGREED);
        assertThatThrownBy(proposal::agree).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> proposal.reject("x")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void agreedProposalCanStillBeCanceledButRejectedCannot() {
        ProposalFeedback agreed = ProposalFeedback.propose(new RentalOrder(), 7L);
        agreed.agree();
        agreed.cancel();
        assertThat(agreed.getStatus()).isEqualTo(ProposalStatus.CANCELED);

        ProposalFeedback rejected = ProposalFeedback.propose(new RentalOrder(), 7L);
        rejected.reject("Không hợp");
        assertThat(rejected.isCancelable()).isFalse();
        assertThatThrownBy(rejected::cancel).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void onlyUnpaidInvoiceCanBeCanceled() {
        Invoice invoice = Invoice.deposit(new RentalOrder(), "INV-DEP-Q7-260101-ABCDEF", "t", "d",
                BigDecimal.TEN, Instant.now());
        invoice.cancel();

        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.CANCELED);
        assertThatThrownBy(invoice::cancel).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void onlyExpiredRentIsFoldedIntoSettlement() {
        Invoice rent = Invoice.forContract(1L, 2L, InvoiceType.RENTAL, "INV-RNT-T", "Tiền thuê", null,
                new BigDecimal("100000"), Instant.now());
        Invoice extension = Invoice.forContract(1L, 2L, InvoiceType.EXTENSION, "INV-EXT-T", "Gia hạn", null,
                new BigDecimal("100000"), Instant.now());

        // Còn Unpaid thì đi nhánh gộp hoá đơn Unpaid, không phải nợ thuê quá hạn.
        assertThat(rent.isExpiredRent()).isFalse();
        assertThatThrownBy(rent::foldExpiredRent).isInstanceOf(IllegalStateException.class);
        // Gia hạn quá hạn chỉ là khách không gia hạn — không phải nợ.
        extension.expire();
        assertThat(extension.isExpiredRent()).isFalse();
        assertThatThrownBy(extension::foldExpiredRent).isInstanceOf(IllegalStateException.class);

        rent.expire();
        assertThat(rent.isExpiredRent()).isTrue();
        rent.foldExpiredRent();
        assertThat(rent.getStatus()).isEqualTo(InvoiceStatus.CANCELED);
        assertThat(rent.isExpiredRent()).isFalse();
    }
}
