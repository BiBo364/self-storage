package vn.lemar.selfstorage.media.application.dto;

import java.util.UUID;
import vn.lemar.selfstorage.media.FileStorage;

/** Kết quả một lần upload, kèm batch để FE gọi delete/attach. */
public record MediaUploadResult(
        String url,
        String publicId,
        long bytes,
        String format,
        UUID batchId) {

    public FileStorage.StoredFile toStoredFile() {
        return new FileStorage.StoredFile(url, publicId, bytes, format);
    }
}
