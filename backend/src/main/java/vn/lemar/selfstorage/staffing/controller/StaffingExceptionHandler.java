package vn.lemar.selfstorage.staffing.controller;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import vn.lemar.selfstorage.ApiError;
import vn.lemar.selfstorage.staffing.application.StaffingException;

/** {@link StaffingException} → đúng mã HTTP của lỗi, body {@code {message, timestamp}}. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class StaffingExceptionHandler {

    @ExceptionHandler(StaffingException.class)
    public ResponseEntity<ApiError> handle(StaffingException ex) {
        return ResponseEntity.status(ex.kind().status()).body(ApiError.now(ex.getMessage()));
    }
}
