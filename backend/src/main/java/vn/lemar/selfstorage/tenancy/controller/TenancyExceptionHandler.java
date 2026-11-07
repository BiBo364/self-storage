package vn.lemar.selfstorage.tenancy.controller;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import vn.lemar.selfstorage.ApiError;
import vn.lemar.selfstorage.tenancy.application.TenancyException;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class TenancyExceptionHandler {

    @ExceptionHandler(TenancyException.class)
    public ResponseEntity<ApiError> handle(TenancyException ex) {
        return ResponseEntity.status(ex.kind().status()).body(ApiError.now(ex.getMessage()));
    }
}
