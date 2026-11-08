package vn.lemar.selfstorage.media.controller;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import vn.lemar.selfstorage.ApiError;
import vn.lemar.selfstorage.media.application.MediaService;
import vn.lemar.selfstorage.media.application.exception.InvalidUploadException;
import vn.lemar.selfstorage.media.application.exception.MediaBatchConflictException;
import vn.lemar.selfstorage.media.application.exception.MediaBatchForbiddenException;
import vn.lemar.selfstorage.media.application.exception.MediaBatchNotFoundException;
import vn.lemar.selfstorage.media.application.exception.MediaRateLimitedException;

/** Lỗi upload của module media — 400 với message cụ thể thay vì message chung của tầng khung. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class MediaExceptionHandler {

    @ExceptionHandler(InvalidUploadException.class)
    public ResponseEntity<ApiError> handleInvalidUpload(InvalidUploadException ex) {
        return ResponseEntity.badRequest().body(ApiError.now(ex.getMessage()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> handleTooLarge(MaxUploadSizeExceededException ex) {
        return ResponseEntity.badRequest().body(ApiError.now(MediaService.MAX_SIZE_MESSAGE));
    }

    @ExceptionHandler(MediaBatchNotFoundException.class)
    public ResponseEntity<ApiError> handleBatchNotFound(MediaBatchNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiError.now(ex.getMessage()));
    }

    @ExceptionHandler(MediaBatchForbiddenException.class)
    public ResponseEntity<ApiError> handleBatchForbidden(MediaBatchForbiddenException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiError.now(ex.getMessage()));
    }

    @ExceptionHandler(MediaRateLimitedException.class)
    public ResponseEntity<ApiError> handleRateLimited(MediaRateLimitedException ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(ApiError.now(ex.getMessage()));
    }

    @ExceptionHandler(MediaBatchConflictException.class)
    public ResponseEntity<ApiError> handleBatchConflict(MediaBatchConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiError.now(ex.getMessage()));
    }
}
