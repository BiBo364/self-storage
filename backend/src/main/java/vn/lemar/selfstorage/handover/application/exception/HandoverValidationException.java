package vn.lemar.selfstorage.handover.application.exception;

/** Lỗi nghiệp vụ Flow 2 — map HTTP ở HandoverExceptionHandler. */
public class HandoverValidationException extends RuntimeException {

    public HandoverValidationException(String message) {
        super(message);
    }
}
