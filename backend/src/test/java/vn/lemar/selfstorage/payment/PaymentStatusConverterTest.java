package vn.lemar.selfstorage.payment;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import vn.lemar.selfstorage.payment.domain.PaymentStatus;
import vn.lemar.selfstorage.payment.domain.PaymentStatusConverter;

/**
 * Giá trị ghi xuống DB phải khớp CHECK constraint của bảng {@code payment_transactions}
 * (`Pending`, `Failed`, `Success`, `Duplicate`). Sai chữ hoa chữ thường là insert bị DB chặn.
 */
class PaymentStatusConverterTest {

    private final PaymentStatusConverter converter = new PaymentStatusConverter();

    @Test
    void writesDbValuesMatchingCheckConstraint() {
        assertThat(converter.convertToDatabaseColumn(PaymentStatus.PENDING)).isEqualTo("Pending");
        assertThat(converter.convertToDatabaseColumn(PaymentStatus.FAILED)).isEqualTo("Failed");
        assertThat(converter.convertToDatabaseColumn(PaymentStatus.SUCCESS)).isEqualTo("Success");
        assertThat(converter.convertToDatabaseColumn(PaymentStatus.DUPLICATE)).isEqualTo("Duplicate");
    }

    @Test
    void readsValuesBackFromDb() {
        assertThat(converter.convertToEntityAttribute("Pending")).isEqualTo(PaymentStatus.PENDING);
        assertThat(converter.convertToEntityAttribute("Success")).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(converter.convertToEntityAttribute("Duplicate")).isEqualTo(PaymentStatus.DUPLICATE);
    }
}
