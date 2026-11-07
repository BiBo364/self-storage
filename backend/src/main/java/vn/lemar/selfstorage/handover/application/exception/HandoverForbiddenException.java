package vn.lemar.selfstorage.handover.application.exception;

/** Lỗi nghiệp vụ Flow 2 — map HTTP ở HandoverExceptionHandler. */
public class HandoverForbiddenException extends RuntimeException {

    public HandoverForbiddenException(String message) {
        super(message);
    }
}
