package vn.lemar.selfstorage.media.application.exception;

/** Account upload vượt ngưỡng trong cửa sổ thời gian → 429. */
public class MediaRateLimitedException extends RuntimeException {

    public MediaRateLimitedException(String message) {
        super(message);
    }
}
