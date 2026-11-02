package vn.lemar.selfstorage.media.application.exception;

/** Không có asset nào thuộc batch của account hiện tại — trả 404. */
public class MediaBatchNotFoundException extends RuntimeException {

    public MediaBatchNotFoundException(String message) {
        super(message);
    }
}
