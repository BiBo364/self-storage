package vn.lemar.selfstorage.media.application;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.lemar.selfstorage.media.MediaAttachments;
import vn.lemar.selfstorage.media.domain.MediaUpload;
import vn.lemar.selfstorage.media.domain.MediaUploadStatus;
import vn.lemar.selfstorage.media.repository.MediaUploadRepository;

/** Attach phía server khi flow lưu URL — không phụ thuộc FE gọi {@code /attach}. */
@Service
public class MediaAttachmentService implements MediaAttachments {

    private final MediaUploadRepository mediaUploads;

    public MediaAttachmentService(MediaUploadRepository mediaUploads) {
        this.mediaUploads = mediaUploads;
    }

    @Override
    @Transactional
    public int attach(long accountId, Collection<String> urls) {
        if (urls == null) {
            return 0;
        }
        List<String> wanted = urls.stream().filter(Objects::nonNull).map(String::trim).filter(u -> !u.isEmpty())
                .distinct().toList();
        if (wanted.isEmpty()) {
            return 0;
        }
        List<MediaUpload> rows = mediaUploads.findByAccountAndUrlsForUpdate(accountId, wanted,
                MediaUploadStatus.PENDING);
        rows.forEach(MediaUpload::markAttached);
        mediaUploads.saveAll(rows);
        return rows.size();
    }
}
