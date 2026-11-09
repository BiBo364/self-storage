package vn.lemar.selfstorage.backoffice.controller;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import vn.lemar.selfstorage.ApiError;

/**
 * Lỗi nghiệp vụ back-office: {@code IllegalArgumentException} → 400, {@code IllegalStateException} → 409. Chỉ áp cho
 * {@link BackofficeController} để không đổi cách map lỗi lập trình ở các controller khác.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = BackofficeController.class)
public class BackofficeExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> badRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(ApiError.now(ex.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiError> conflict(IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiError.now(ex.getMessage()));
    }
}
