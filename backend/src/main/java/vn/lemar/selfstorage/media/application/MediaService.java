package vn.lemar.selfstorage.media.application;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.lemar.selfstorage.media.FileStorage;
import vn.lemar.selfstorage.media.application.dto.MediaBatchAttachResult;
import vn.lemar.selfstorage.media.application.dto.MediaBatchDeleteResult;
import vn.lemar.selfstorage.media.application.dto.MediaUploadResult;
import vn.lemar.selfstorage.media.application.exception.InvalidUploadException;
import vn.lemar.selfstorage.media.application.exception.MediaBatchConflictException;
import vn.lemar.selfstorage.media.application.exception.MediaBatchForbiddenException;
import vn.lemar.selfstorage.media.application.exception.MediaBatchNotFoundException;
import vn.lemar.selfstorage.media.domain.MediaFileType;
import vn.lemar.selfstorage.media.domain.MediaUpload;
import vn.lemar.selfstorage.media.domain.MediaUploadStatus;
import vn.lemar.selfstorage.media.repository.MediaUploadRepository;

/**
 * Validate file theo contract rồi chuyển cho {@link FileStorage}.
 *
 * <p>Hai lớp kiểm loại file: content-type client khai phải nằm trong allowlist, và nội dung
 * phải khớp loại đó theo chữ ký (magic bytes) — chặn file khai sai định dạng trước khi upload.
 *
 * <p>Giới hạn 10MB khớp cap upload của Cloudinary free plan; servlet multipart đặt 12MB
 * (`application.yml`) để file tới được đây và lỗi kích thước có message 400 riêng.
 *
 * <p>Mỗi account có folder riêng (`uploads/acc-{id}`) — file mồ côi khi flow không hoàn tất
 * vẫn truy vết được chủ sở hữu trong console storage.
 *
 * <p>Mỗi lần upload ghi một row {@code media_uploads} theo {@code batchId}: Hủy batch xoá asset
 * chưa attach, upload completion thấy row {@code Canceled} thì xoá asset vừa lưu.
 */
@Service
public class MediaService {

    public static final long MAX_SIZE_BYTES = 10L * 1024 * 1024;
    public static final String MAX_SIZE_MESSAGE = "File vượt quá kích thước cho phép (tối đa 10MB)";

    static final String FOLDER_PREFIX = "uploads/acc-";

    private final FileStorage fileStorage;
    private final MediaUploadRepository mediaUploads;

    public MediaService(FileStorage fileStorage, MediaUploadRepository mediaUploads) {
        this.fileStorage = fileStorage;
        this.mediaUploads = mediaUploads;
    }

    /** Giữ chữ ký cũ cho caller không cần batch (không còn caller production). */
    @Transactional
    public FileStorage.StoredFile upload(byte[] content, String originalFilename, String contentType,
                                         long accountId) {
        return upload(content, originalFilename, contentType, accountId, null).toStoredFile();
    }

    @Transactional
    public MediaUploadResult upload(byte[] content, String originalFilename, String contentType,
                                    long accountId, UUID batchId) {
        validate(content, contentType);
        UUID batch = batchId == null ? UUID.randomUUID() : batchId;
        MediaUpload row = MediaUpload.uploading(accountId, batch);
        mediaUploads.save(row);

        FileStorage.StoredFile stored = fileStorage.store(content, originalFilename, contentType,
                FOLDER_PREFIX + accountId);
        MediaUpload locked = mediaUploads.findByIdForUpdate(row.getId())
                .orElseThrow(() -> new IllegalStateException("Không tìm thấy media upload " + row.getId()));
        if (locked.getStatus() == MediaUploadStatus.CANCELED) {
            fileStorage.delete(stored.publicId());
            locked.markDeleted();
        } else {
            locked.markPending(stored.url(), stored.publicId(), stored.bytes(), stored.format());
        }
        mediaUploads.save(locked);
        return new MediaUploadResult(stored.url(), stored.publicId(), stored.bytes(), stored.format(), batch);
    }

    /** Hủy batch: xoá asset Uploading/Pending; bỏ qua asset đã Attached. */
    @Transactional
    public MediaBatchDeleteResult deleteBatch(long accountId, UUID batchId) {
        List<MediaUpload> rows = mediaUploads.findByAccountIdAndBatchIdForUpdate(accountId, batchId);
        assertBatchOwned(batchId, rows);

        int deleted = 0;
        int skipped = 0;
        for (MediaUpload row : rows) {
            if (row.getStatus() == MediaUploadStatus.UPLOADING
                    || row.getStatus() == MediaUploadStatus.PENDING) {
                row.markCanceled();
                if (row.getPublicId() != null) {
                    fileStorage.delete(row.getPublicId());
                    row.markDeleted();
                }
                deleted++;
            } else {
                skipped++;
            }
        }
        mediaUploads.saveAll(rows);
        return new MediaBatchDeleteResult(deleted, skipped);
    }

    /** Flow đã submit URL: đánh dấu Pending -> Attached để Hủy batch không xoá nữa. */
    @Transactional
    public MediaBatchAttachResult attachBatch(long accountId, UUID batchId) {
        List<MediaUpload> rows = mediaUploads.findByAccountIdAndBatchIdForUpdate(accountId, batchId);
        assertBatchOwned(batchId, rows);

        boolean canceled = rows.stream().anyMatch(row ->
                row.getStatus() == MediaUploadStatus.CANCELED
                        || row.getStatus() == MediaUploadStatus.DELETED);
        if (canceled) {
            throw new MediaBatchConflictException("Batch đã bị huỷ");
        }

        int attached = 0;
        for (MediaUpload row : rows) {
            if (row.getStatus() == MediaUploadStatus.PENDING) {
                row.markAttached();
                attached++;
            }
        }
        mediaUploads.saveAll(rows);
        return new MediaBatchAttachResult(attached);
    }

    private void assertBatchOwned(UUID batchId, List<MediaUpload> rows) {
        if (!rows.isEmpty()) {
            return;
        }
        if (mediaUploads.existsByBatchId(batchId)) {
            throw new MediaBatchForbiddenException("Batch thuộc tài khoản khác");
        }
        throw new MediaBatchNotFoundException("Không tìm thấy batch");
    }

    private void validate(byte[] content, String contentType) {
        if (content == null || content.length == 0) {
            throw new InvalidUploadException("File rỗng");
        }
        MediaFileType declared = declaredType(contentType);
        if (content.length > MAX_SIZE_BYTES) {
            throw new InvalidUploadException(MAX_SIZE_MESSAGE);
        }
        MediaFileType actual = MediaFileType.detect(content)
                .orElseThrow(() -> new InvalidUploadException(
                        "File không đọc được (không nhận diện được định dạng)"));
        if (actual != declared) {
            throw new InvalidUploadException("Nội dung file không khớp với định dạng khai báo");
        }
    }

    /** Header có thể kèm tham số (`image/png; charset=binary`); `image/jpg` map về JPEG. */
    private MediaFileType declaredType(String contentType) {
        String mediaType = contentType == null ? "" : contentType.split(";")[0].trim().toLowerCase(Locale.ROOT);
        return MediaFileType.fromContentType(mediaType)
                .orElseThrow(() -> new InvalidUploadException(
                        "Định dạng file không được hỗ trợ (chỉ nhận JPEG, PNG, WebP, PDF)"));
    }
}
