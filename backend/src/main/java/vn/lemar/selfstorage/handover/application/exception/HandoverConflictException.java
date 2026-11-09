package vn.lemar.selfstorage.handover.application.exception;

/** Lỗi nghiệp vụ Flow 2 — map HTTP ở HandoverExceptionHandler. */
public class HandoverConflictException extends RuntimeException {

    public HandoverConflictException(String message) {
        super(message);
    }
}
