package vn.lemar.selfstorage.media.application.exception;

/** Batch đã Canceled/Deleted, không attach được — trả 409. */
public class MediaBatchConflictException extends RuntimeException {

    public MediaBatchConflictException(String message) {
        super(message);
    }
}
