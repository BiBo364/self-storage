package vn.lemar.selfstorage.staffing.application;

/** Lỗi nghiệp vụ nhân sự, mang sẵn mã HTTP. */
public class StaffingException extends RuntimeException {

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

    public StaffingException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    public static StaffingException notFound(String message) {
        return new StaffingException(Kind.NOT_FOUND, message);
    }

    public static StaffingException forbidden(String message) {
        return new StaffingException(Kind.FORBIDDEN, message);
    }

    public static StaffingException conflict(String message) {
        return new StaffingException(Kind.CONFLICT, message);
    }

    public static StaffingException invalid(String message) {
        return new StaffingException(Kind.VALIDATION, message);
    }
}
