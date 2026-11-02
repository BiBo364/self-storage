package vn.lemar.selfstorage.media.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Một asset đã/đang upload, gắn account + batch để dọn khi FE Hủy.
 *
 * <p>Row được tạo trước khi gọi Cloudinary ({@code Uploading}); upload completion khoá row và
 * chuyển {@code Pending}, hoặc thấy {@code Canceled} thì xoá asset vừa lưu.
 */
@Entity
@Table(name = "media_uploads")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MediaUpload {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "batch_id", nullable = false)
    private UUID batchId;

    @Column(name = "public_id")
    private String publicId;

    @Column
    private String url;

    @Column
    private Long bytes;

    @Column(length = 20)
    private String format;

    @Convert(converter = MediaUploadStatusConverter.class)
    @Column(nullable = false, length = 20)
    private MediaUploadStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    private MediaUpload(Long accountId, UUID batchId) {
        this.accountId = accountId;
        this.batchId = batchId;
        this.status = MediaUploadStatus.UPLOADING;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public static MediaUpload uploading(long accountId, UUID batchId) {
        return new MediaUpload(accountId, batchId);
    }

    public void markPending(String url, String publicId, long bytes, String format) {
        if (status != MediaUploadStatus.UPLOADING) {
            throw new IllegalStateException("Media upload " + id + " không còn Uploading: " + status);
        }
        this.url = url;
        this.publicId = publicId;
        this.bytes = bytes;
        this.format = format;
        this.status = MediaUploadStatus.PENDING;
        touch();
    }

    public void markCanceled() {
        if (status != MediaUploadStatus.UPLOADING && status != MediaUploadStatus.PENDING) {
            throw new IllegalStateException("Media upload " + id + " không huỷ được ở trạng thái " + status);
        }
        this.status = MediaUploadStatus.CANCELED;
        touch();
    }

    public void markDeleted() {
        this.status = MediaUploadStatus.DELETED;
        touch();
    }

    public void markFailed() {
        if (status == MediaUploadStatus.UPLOADING) {
            this.status = MediaUploadStatus.FAILED;
            touch();
        }
    }

    public void markAttached() {
        if (status != MediaUploadStatus.PENDING) {
            throw new IllegalStateException("Media upload " + id + " không attach được ở trạng thái " + status);
        }
        this.status = MediaUploadStatus.ATTACHED;
        touch();
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }
}
