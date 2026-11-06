package vn.lemar.selfstorage.handover.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Quy đổi giữa enum viết HOA trong Java và giá trị PascalCase mà CHECK constraint yêu cầu. */
@Converter
public class RentalContractStatusConverter implements AttributeConverter<RentalContractStatus, String> {

    @Override
    public String convertToDatabaseColumn(RentalContractStatus attribute) {
        return attribute == null ? null : attribute.dbValue();
    }

    @Override
    public RentalContractStatus convertToEntityAttribute(String dbData) {
        return dbData == null ? null : RentalContractStatus.fromDbValue(dbData);
    }
}
