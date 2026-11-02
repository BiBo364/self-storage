package vn.lemar.selfstorage.media;

/**
 * Cổng lưu trữ file — cô lập provider theo issue #15 §6 (impl hiện tại: Cloudinary).
 */
public interface FileStorage {

    /** Kết quả một lần lưu file. */
    record StoredFile(String url, String publicId, long bytes, String format) {}

    /**
     * Lưu file lên storage và trả URL đọc lại được.
     *
     * @param folder thư mục đích (dùng để truy vết theo chủ sở hữu); null/blank thì lưu ở gốc
     * @throws vn.lemar.selfstorage.media.application.exception.InvalidUploadException
     *         provider từ chối nội dung file (không đọc được / sai định dạng)
     * @throws vn.lemar.selfstorage.media.application.exception.MediaStorageException lỗi provider
     */
    StoredFile store(byte[] content, String originalFilename, String contentType, String folder);

    /**
     * Xoá file theo {@code publicId}; idempotent khi asset không còn tồn tại.
     *
     * @throws vn.lemar.selfstorage.media.application.exception.MediaStorageException lỗi provider
     */
    void delete(String publicId);
}
