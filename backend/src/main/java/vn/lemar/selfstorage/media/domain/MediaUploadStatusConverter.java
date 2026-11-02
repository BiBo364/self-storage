package vn.lemar.selfstorage.media.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Quy đổi enum viết HOA trong Java và giá trị PascalCase của CHECK constraint. */
@Converter
public class MediaUploadStatusConverter implements AttributeConverter<MediaUploadStatus, String> {

    @Override
    public String convertToDatabaseColumn(MediaUploadStatus attribute) {
        return attribute == null ? null : attribute.dbValue();
    }

    @Override
    public MediaUploadStatus convertToEntityAttribute(String dbData) {
        return dbData == null ? null : MediaUploadStatus.fromDbValue(dbData);
    }
}
