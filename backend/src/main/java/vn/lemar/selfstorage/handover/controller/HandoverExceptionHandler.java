package vn.lemar.selfstorage.handover.controller;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import vn.lemar.selfstorage.ApiError;
import vn.lemar.selfstorage.handover.application.exception.HandoverConflictException;
import vn.lemar.selfstorage.handover.application.exception.HandoverForbiddenException;
import vn.lemar.selfstorage.handover.application.exception.HandoverNotFoundException;
import vn.lemar.selfstorage.handover.application.exception.HandoverValidationException;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class HandoverExceptionHandler {

    @ExceptionHandler(HandoverNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(HandoverNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiError.now(ex.getMessage()));
    }

    @ExceptionHandler(HandoverForbiddenException.class)
    public ResponseEntity<ApiError> handleForbidden(HandoverForbiddenException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiError.now(ex.getMessage()));
    }

    @ExceptionHandler(HandoverConflictException.class)
    public ResponseEntity<ApiError> handleConflict(HandoverConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiError.now(ex.getMessage()));
    }

    @ExceptionHandler(HandoverValidationException.class)
    public ResponseEntity<ApiError> handleValidation(HandoverValidationException ex) {
        return ResponseEntity.badRequest().body(ApiError.now(ex.getMessage()));
    }
}
