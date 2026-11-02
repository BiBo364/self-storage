package vn.lemar.selfstorage.media.application.exception;

/** Lỗi tầng lưu trữ (provider) — trả 500 chung, chi tiết chỉ vào log. */
public class MediaStorageException extends RuntimeException {

    public MediaStorageException(String message) {
        super(message);
    }

    public MediaStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
