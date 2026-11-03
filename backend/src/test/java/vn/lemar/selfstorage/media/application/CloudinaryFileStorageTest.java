package vn.lemar.selfstorage.media.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cloudinary.Cloudinary;
import com.cloudinary.Uploader;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import vn.lemar.selfstorage.media.FileStorage;
import vn.lemar.selfstorage.media.application.exception.InvalidUploadException;
import vn.lemar.selfstorage.media.application.exception.MediaStorageException;

class CloudinaryFileStorageTest {

    private final Cloudinary cloudinary = mock(Cloudinary.class);
    private final Uploader uploader = mock(Uploader.class);
    private final CloudinaryFileStorage storage = new CloudinaryFileStorage(cloudinary);

    private void stubUpload(Map<String, Object> result) throws IOException {
        when(cloudinary.uploader()).thenReturn(uploader);
        when(uploader.upload(any(byte[].class), anyMap())).thenReturn(result);
    }

    private void stubDestroy(Map<String, Object> result) throws IOException {
        when(cloudinary.uploader()).thenReturn(uploader);
        when(uploader.destroy(eq("p"), anyMap())).thenReturn(result);
    }

    @Test
    void mapsCloudinaryResultToStoredFile() throws IOException {
        stubUpload(Map.of(
                "secure_url", "https://res.cloudinary.com/demo/image/upload/v1/uploads/acc-1/anh.png",
                "public_id", "uploads/acc-1/anh",
                "bytes", 20480,
                "format", "png"));

        FileStorage.StoredFile stored = storage.store(new byte[]{1}, "anh.png", "image/png", "uploads/acc-1");

        assertThat(stored.url()).isEqualTo("https://res.cloudinary.com/demo/image/upload/v1/uploads/acc-1/anh.png");
        assertThat(stored.publicId()).isEqualTo("uploads/acc-1/anh");
        assertThat(stored.bytes()).isEqualTo(20480);
        assertThat(stored.format()).isEqualTo("png");
    }

    @Test
    @SuppressWarnings("unchecked")
    void sendsImageResourceTypeAndFolder() throws IOException {
        stubUpload(Map.of("secure_url", "u", "public_id", "p", "bytes", 1, "format", "png"));

        storage.store(new byte[]{1}, "anh.png", "image/png", "uploads/acc-9");

        ArgumentCaptor<Map<String, Object>> optionsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(uploader).upload(any(byte[].class), optionsCaptor.capture());
        assertThat(optionsCaptor.getValue())
                .containsEntry("resource_type", "image")
                .containsEntry("folder", "uploads/acc-9");
    }

    @Test
    @SuppressWarnings("unchecked")
    void omitsFolderWhenBlank() throws IOException {
        stubUpload(Map.of("secure_url", "u", "public_id", "p", "bytes", 1, "format", "png"));

        storage.store(new byte[]{1}, "anh.png", "image/png", "  ");

        ArgumentCaptor<Map<String, Object>> optionsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(uploader).upload(any(byte[].class), optionsCaptor.capture());
        assertThat(optionsCaptor.getValue()).doesNotContainKey("folder");
    }

    @Test
    void wrapsIOExceptionAsStorageException() throws IOException {
        when(cloudinary.uploader()).thenReturn(uploader);
        when(uploader.upload(any(byte[].class), anyMap())).thenThrow(new IOException("boom"));

        assertThatThrownBy(() -> storage.store(new byte[]{1}, "anh.png", "image/png", "uploads/acc-1"))
                .isInstanceOf(MediaStorageException.class)
                .hasCauseInstanceOf(IOException.class);
    }

    @Test
    void mapsProviderRejectionToInvalidUpload() throws IOException {
        when(cloudinary.uploader()).thenReturn(uploader);
        when(uploader.upload(any(byte[].class), anyMap()))
                .thenThrow(new RuntimeException("Invalid image file"));

        assertThatThrownBy(() -> storage.store(new byte[]{1}, "fake.png", "image/png", "uploads/acc-1"))
                .isInstanceOf(InvalidUploadException.class)
                .hasMessage("File không đọc được hoặc không đúng định dạng ảnh/PDF");
    }

    @Test
    void deletesAsset() throws IOException {
        stubDestroy(Map.of("result", "ok"));

        storage.delete("p");

        verify(uploader).destroy(eq("p"), anyMap());
    }

    @Test
    void treatsNotFoundAsSuccess() throws IOException {
        stubDestroy(Map.of("result", "not found"));

        storage.delete("p");

        verify(uploader).destroy(eq("p"), anyMap());
    }

    @Test
    void wrapsDeleteIOException() throws IOException {
        when(cloudinary.uploader()).thenReturn(uploader);
        when(uploader.destroy(eq("p"), anyMap())).thenThrow(new IOException("boom"));

        assertThatThrownBy(() -> storage.delete("p"))
                .isInstanceOf(MediaStorageException.class)
                .hasCauseInstanceOf(IOException.class);
    }

    @Test
    void rejectsUnexpectedDeleteResult() throws IOException {
        stubDestroy(Map.of("result", "error"));

        assertThatThrownBy(() -> storage.delete("p"))
                .isInstanceOf(MediaStorageException.class);
    }

    @Test
    void ignoresBlankPublicId() {
        storage.delete(" ");

        verifyNoInteractions(uploader);
    }
}
