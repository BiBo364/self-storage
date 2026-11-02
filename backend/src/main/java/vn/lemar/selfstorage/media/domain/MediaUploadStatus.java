package vn.lemar.selfstorage.media.domain;

/**
 * Trạng thái một lần upload media. DB lưu PascalCase theo CHECK constraint của V12.
 *
 * <p>{@code Uploading}: đã tạo row, chưa có kết quả Cloudinary. {@code Pending}: đã lưu xong,
 * chờ flow attach. {@code Attached}: flow đã dùng URL, không xoá khi Hủy batch.
 */
public enum MediaUploadStatus {
    UPLOADING("Uploading"),
    PENDING("Pending"),
    ATTACHED("Attached"),
    CANCELED("Canceled"),
    DELETED("Deleted"),
    FAILED("Failed");

    private final String dbValue;

    MediaUploadStatus(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    public static MediaUploadStatus fromDbValue(String value) {
        for (MediaUploadStatus status : values()) {
            if (status.dbValue.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Giá trị media upload status không hợp lệ: " + value);
    }
}
