package vn.lemar.selfstorage.tenancy.application;

/** Lỗi nghiệp vụ sau bàn giao (tenancy, checkout, overdue) — map HTTP ở {@code TenancyExceptionHandler}. */
public class TenancyException extends RuntimeException {

    /** Loại lỗi → mã HTTP. */
    public enum Kind {
        NOT_FOUND(404), FORBIDDEN(403), CONFLICT(409), VALIDATION(400);

        private final int status;

        Kind(int status) {
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    private final Kind kind;

    public TenancyException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    public static TenancyException notFound(String message) {
        return new TenancyException(Kind.NOT_FOUND, message);
    }

    public static TenancyException conflict(String message) {
        return new TenancyException(Kind.CONFLICT, message);
    }

    public static TenancyException invalid(String message) {
        return new TenancyException(Kind.VALIDATION, message);
    }
}
