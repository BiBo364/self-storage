package vn.lemar.selfstorage.handover.domain;

/**
 * Kết quả một phiên bàn giao ({@code specs/db-table-draft.md} › HandoverRecord).
 *
 * <p>Giá trị khớp nguyên văn CHECK {@code handover_records.result} của V1; DB đã dùng chữ HOA
 * nên map trực tiếp bằng {@code @Enumerated(EnumType.STRING)}, không cần converter.
 */
public enum HandoverResult {
    IN_PROGRESS,
    COMPLETED,
    REJECTED,
    CANCELED
}
