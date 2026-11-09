package vn.lemar.selfstorage.booking.domain;

/**
 * Trạng thái đề xuất khoang ({@code specs/db-table-draft.md} › ProposalFeedback).
 *
 * <p>DB lưu dạng PascalCase theo CHECK constraint của {@code proposal_feedbacks}; quy đổi qua
 * {@link ProposalStatusConverter}.
 *
 * <p>{@code Canceled} thay cho {@code Expired} của spec: proposal không có TTL, chỉ bị huỷ
 * khi khoang hết khả dụng lúc khách đồng ý hoặc khi đơn chạy Cancellation Cascade.
 */
public enum ProposalStatus {
    PENDING("Pending"),
    AGREED("Agreed"),
    REJECTED("Rejected"),
    CANCELED("Canceled");

    private final String dbValue;

    ProposalStatus(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    public static ProposalStatus fromDbValue(String value) {
        for (ProposalStatus status : values()) {
            if (status.dbValue.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Giá trị status không hợp lệ: " + value);
    }
}
