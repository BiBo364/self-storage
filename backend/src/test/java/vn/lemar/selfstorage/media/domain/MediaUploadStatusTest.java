package vn.lemar.selfstorage.media.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class MediaUploadStatusTest {

    @Test
    void mapsEveryDbValue() {
        for (MediaUploadStatus status : MediaUploadStatus.values()) {
            assertThat(MediaUploadStatus.fromDbValue(status.dbValue())).isEqualTo(status);
        }
    }

    @Test
    void rejectsUnknownDbValue() {
        assertThatThrownBy(() -> MediaUploadStatus.fromDbValue("Expired"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void converterRoundTrips() {
        MediaUploadStatusConverter converter = new MediaUploadStatusConverter();

        assertThat(converter.convertToDatabaseColumn(MediaUploadStatus.PENDING)).isEqualTo("Pending");
        assertThat(converter.convertToEntityAttribute("Pending")).isEqualTo(MediaUploadStatus.PENDING);
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }
}
