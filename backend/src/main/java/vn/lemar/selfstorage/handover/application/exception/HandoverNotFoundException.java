package vn.lemar.selfstorage.handover.application.exception;

/** Lỗi nghiệp vụ Flow 2 — map HTTP ở HandoverExceptionHandler. */
public class HandoverNotFoundException extends RuntimeException {

    public HandoverNotFoundException(String message) {
        super(message);
    }
}
