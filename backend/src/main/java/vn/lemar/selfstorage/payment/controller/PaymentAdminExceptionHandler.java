package vn.lemar.selfstorage.payment.controller;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import vn.lemar.selfstorage.ApiError;
import vn.lemar.selfstorage.payment.application.exception.ManualReconciliationConflictException;
import vn.lemar.selfstorage.payment.application.exception.ManualReconciliationNotFoundException;
import vn.lemar.selfstorage.payment.application.exception.ManualReconciliationValidationException;
import vn.lemar.selfstorage.payment.application.exception.PendingPaymentFilterException;

/** Lỗi riêng của endpoint đối soát thủ công, trước handler 500 chung. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class PaymentAdminExceptionHandler {

    @ExceptionHandler(ManualReconciliationNotFoundException.class)
    public ResponseEntity<ApiError> notFound(ManualReconciliationNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiError.now(ex.getMessage()));
    }

    @ExceptionHandler(ManualReconciliationConflictException.class)
    public ResponseEntity<ApiError> conflict(ManualReconciliationConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiError.now(ex.getMessage()));
    }

    @ExceptionHandler(ManualReconciliationValidationException.class)
    public ResponseEntity<ApiError> badRequest(ManualReconciliationValidationException ex) {
        return ResponseEntity.badRequest().body(ApiError.now(ex.getMessage()));
    }

    @ExceptionHandler(PendingPaymentFilterException.class)
    public ResponseEntity<ApiError> badPendingFilter(PendingPaymentFilterException ex) {
        return ResponseEntity.badRequest().body(ApiError.now(ex.getMessage()));
    }
}
