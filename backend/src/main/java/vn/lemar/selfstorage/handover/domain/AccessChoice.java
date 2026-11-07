package vn.lemar.selfstorage.handover.domain;

/**
 * Hình thức nhận quyền truy cập khách chọn khi xác nhận bàn giao (spec 2.4) — map {@code
 * handover_records.requested_access} (V19).
 */
public enum AccessChoice {
    /** Mã khóa số — xác nhận là chốt bàn giao, không cần FS. */
    ACCESS_CODE("AccessCode"),
    /** Khóa cơ — FS giao chìa và xác nhận thì chốt. */
    PHYSICAL_KEY("PhysicalKey"),
    /** Cả hai — FS giao chìa; mã số khách lấy trong tài khoản. */
    BOTH("Both");

    private final String dbValue;

    AccessChoice(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    public boolean needsPhysicalKey() {
        return this != ACCESS_CODE;
    }

    public boolean needsCode() {
        return this != PHYSICAL_KEY;
    }

    public static AccessChoice fromDbValue(String value) {
        for (AccessChoice choice : values()) {
            if (choice.dbValue.equals(value)) {
                return choice;
            }
        }
        throw new IllegalArgumentException("requested_access không hợp lệ: " + value);
    }
}
