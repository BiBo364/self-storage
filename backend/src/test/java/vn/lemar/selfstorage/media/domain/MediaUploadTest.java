package vn.lemar.selfstorage.media.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class MediaUploadTest {

    @Test
    void rejectsInvalidTransitions() {
        MediaUpload row = MediaUpload.uploading(1L, UUID.randomUUID());
        row.markCanceled();

        assertThatThrownBy(() -> row.markPending("u", "p", 1, "png"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(row::markAttached).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(row::markCanceled).isInstanceOf(IllegalStateException.class);

        row.markDeleted();
        assertThat(row.getStatus()).isEqualTo(MediaUploadStatus.DELETED);
        row.markFailed();
        assertThat(row.getStatus()).isEqualTo(MediaUploadStatus.DELETED);
    }

    @Test
    void markPendingStoresStoredFileFields() {
        MediaUpload row = MediaUpload.uploading(1L, UUID.randomUUID());

        row.markPending("https://x/y.png", "uploads/acc-1/y", 42, "png");

        assertThat(row.getStatus()).isEqualTo(MediaUploadStatus.PENDING);
        assertThat(row.getUrl()).isEqualTo("https://x/y.png");
        assertThat(row.getPublicId()).isEqualTo("uploads/acc-1/y");
        assertThat(row.getBytes()).isEqualTo(42);
        assertThat(row.getFormat()).isEqualTo("png");
    }
}
