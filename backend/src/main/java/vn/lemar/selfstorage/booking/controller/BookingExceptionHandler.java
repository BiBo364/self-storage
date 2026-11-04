package vn.lemar.selfstorage.booking.controller;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import vn.lemar.selfstorage.ApiError;
import vn.lemar.selfstorage.booking.application.exception.AppointmentConflictException;
import vn.lemar.selfstorage.booking.application.exception.AppointmentNotFoundException;
import vn.lemar.selfstorage.booking.application.exception.AppointmentValidationException;
import vn.lemar.selfstorage.booking.application.exception.DepositNotAllowedException;
import vn.lemar.selfstorage.booking.application.exception.EmailNotVerifiedException;
import vn.lemar.selfstorage.booking.application.exception.FacilityNotFoundException;
import vn.lemar.selfstorage.booking.application.exception.InvoiceNotFoundException;
import vn.lemar.selfstorage.booking.application.exception.ProposalConflictException;
import vn.lemar.selfstorage.booking.application.exception.ProposalForbiddenException;
import vn.lemar.selfstorage.booking.application.exception.ProposalNotFoundException;
import vn.lemar.selfstorage.booking.application.exception.ProposalValidationException;
import vn.lemar.selfstorage.booking.application.exception.RentalRequestAlreadyHandledException;
import vn.lemar.selfstorage.booking.application.exception.RentalRequestDuplicateUnitException;
import vn.lemar.selfstorage.booking.application.exception.RentalRequestExpiredException;
import vn.lemar.selfstorage.booking.application.exception.RentalRequestNotFoundException;
import vn.lemar.selfstorage.booking.application.exception.RentalOrderNotFoundException;
import vn.lemar.selfstorage.booking.application.exception.RentalRequestValidationException;
import vn.lemar.selfstorage.booking.application.exception.StorageUnitFilterException;
import vn.lemar.selfstorage.booking.application.exception.StorageUnitNotFoundException;
import vn.lemar.selfstorage.booking.application.exception.StorageUnitUnavailableException;

/**
 * Lỗi nghiệp vụ của booking — phải HIGHEST_PRECEDENCE vì ApiExceptionHandler
 * (LOWEST) sẽ đè lên bằng catch-all 500 nếu thiếu @Order (xem error-handling.md).
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class BookingExceptionHandler {

    @ExceptionHandler({ProposalNotFoundException.class, RentalOrderNotFoundException.class})
    public ResponseEntity<ApiError> handleNotFound(RuntimeException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiError.now(ex.getMessage()));
    }

    @ExceptionHandler(ProposalForbiddenException.class)
    public ResponseEntity<ApiError> handleForbidden(ProposalForbiddenException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiError.now(ex.getMessage()));
    }

    @ExceptionHandler(ProposalConflictException.class)
    public ResponseEntity<ApiError> handleConflict(ProposalConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiError.now(ex.getMessage()));
    }

    @ExceptionHandler(ProposalValidationException.class)
    public ResponseEntity<ApiError> handleProposalValidation(ProposalValidationException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiError.now(ex.getMessage()));
    }

    @ExceptionHandler(RentalRequestValidationException.class)
    public ResponseEntity<ApiError> handleValidation(RentalRequestValidationException ex) {
        return ResponseEntity.badRequest().body(ApiError.now(ex.getMessage()));
    }

    @ExceptionHandler(FacilityNotFoundException.class)
    public ResponseEntity<ApiError> handleFacilityNotFound(FacilityNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiError.now(ex.getMessage()));
    }

    @ExceptionHandler(StorageUnitNotFoundException.class)
    public ResponseEntity<ApiError> handleStorageUnitNotFound(StorageUnitNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiError.now(ex.getMessage()));
    }

    @ExceptionHandler(RentalRequestNotFoundException.class)
    public ResponseEntity<ApiError> handleRentalRequestNotFound(RentalRequestNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiError.now(ex.getMessage()));
    }

    @ExceptionHandler(RentalRequestAlreadyHandledException.class)
    public ResponseEntity<ApiError> handleAlreadyHandled(RentalRequestAlreadyHandledException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiError.now(ex.getMessage()));
    }

    @ExceptionHandler(RentalRequestExpiredException.class)
    public ResponseEntity<ApiError> handleExpired(RentalRequestExpiredException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiError.now(ex.getMessage()));
    }

    @ExceptionHandler(RentalRequestDuplicateUnitException.class)
    public ResponseEntity<ApiError> handleDuplicateUnit(RentalRequestDuplicateUnitException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiError.now(ex.getMessage()));
    }

    @ExceptionHandler(StorageUnitUnavailableException.class)
    public ResponseEntity<ApiError> handleUnitUnavailable(StorageUnitUnavailableException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiError.now(ex.getMessage()));
    }

    /** Không tồn tại và không phải của mình trả về như nhau — không lộ sự tồn tại của hóa đơn. */
    @ExceptionHandler(InvoiceNotFoundException.class)
    public ResponseEntity<ApiError> handleInvoiceNotFound(InvoiceNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiError.now(ex.getMessage()));
    }

    @ExceptionHandler(EmailNotVerifiedException.class)
    public ResponseEntity<ApiError> handleUnverified(EmailNotVerifiedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiError.now(ex.getMessage()));
    }

    /** 409: hóa đơn hợp lệ nhưng hoàn cảnh hiện tại không cho thanh toán (Flow 1.4). */
    @ExceptionHandler(DepositNotAllowedException.class)
    public ResponseEntity<ApiError> handleDepositNotAllowed(DepositNotAllowedException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiError.now(ex.getMessage()));
    }
    // ---- Lịch hẹn check-in (spec 1.5)

    @ExceptionHandler(AppointmentNotFoundException.class)
    public ResponseEntity<ApiError> handleAppointmentNotFound(AppointmentNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiError.now(ex.getMessage()));
    }

    @ExceptionHandler(AppointmentConflictException.class)
    public ResponseEntity<ApiError> handleAppointmentConflict(AppointmentConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiError.now(ex.getMessage()));
    }

    @ExceptionHandler(AppointmentValidationException.class)
    public ResponseEntity<ApiError> handleAppointmentValidation(AppointmentValidationException ex) {
        return ResponseEntity.badRequest().body(ApiError.now(ex.getMessage()));
    }

    @ExceptionHandler(StorageUnitFilterException.class)
    public ResponseEntity<ApiError> handleStorageUnitFilter(StorageUnitFilterException ex) {
        return ResponseEntity.badRequest().body(ApiError.now(ex.getMessage()));
    }
}

