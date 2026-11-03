package vn.lemar.selfstorage.media.application.exception;

/** Batch tồn tại nhưng thuộc account khác — trả 403. */
public class MediaBatchForbiddenException extends RuntimeException {

    public MediaBatchForbiddenException(String message) {
        super(message);
    }
}
