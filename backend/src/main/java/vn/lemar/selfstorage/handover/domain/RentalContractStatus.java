package vn.lemar.selfstorage.handover.domain;

/**
 * Trạng thái hợp đồng thuê ({@code specs/db-table-draft.md} › RentalContract).
 *
 * <p>DB lưu dạng PascalCase theo CHECK {@code rental_contracts.status} của V1; quy đổi qua
 * {@link RentalContractStatusConverter}.
 */
public enum RentalContractStatus {
    DRAFT("Draft"),
    SIGNED("Signed"),
    ACTIVE("Active"),
    ENDED("Ended"),
    CANCELED("Canceled");

    private final String dbValue;

    RentalContractStatus(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    /** Chỉ hợp đồng chưa bàn giao mới huỷ được; {@code Active}/{@code Ended} giữ nguyên lịch sử. */
    public boolean isCancelable() {
        return this == DRAFT || this == SIGNED;
    }

    public static RentalContractStatus fromDbValue(String value) {
        for (RentalContractStatus status : values()) {
            if (status.dbValue.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Giá trị status không hợp lệ: " + value);
    }
}
