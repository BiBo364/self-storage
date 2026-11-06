package vn.lemar.selfstorage.booking.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Quy đổi refund status giữa enum Java và giá trị PascalCase của DB. */
@Converter
public class InvoiceRefundStatusConverter implements AttributeConverter<InvoiceRefundStatus, String> {

    @Override
    public String convertToDatabaseColumn(InvoiceRefundStatus attribute) {
        return attribute == null ? null : attribute.dbValue();
    }

    @Override
    public InvoiceRefundStatus convertToEntityAttribute(String dbData) {
        return dbData == null ? null : InvoiceRefundStatus.fromDbValue(dbData);
    }
}
