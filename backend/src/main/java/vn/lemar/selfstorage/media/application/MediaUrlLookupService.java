package vn.lemar.selfstorage.media.application;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.lemar.selfstorage.media.MediaUrlLookup;
import vn.lemar.selfstorage.media.domain.MediaUploadStatus;
import vn.lemar.selfstorage.media.repository.MediaUploadRepository;

/** Đối chiếu URL với {@code media_uploads} theo chủ sở hữu — chỉ đọc. */
@Service
public class MediaUrlLookupService implements MediaUrlLookup {

    private static final List<MediaUploadStatus> USABLE = List.of(MediaUploadStatus.PENDING,
            MediaUploadStatus.ATTACHED);

    private final MediaUploadRepository mediaUploads;

    public MediaUrlLookupService(MediaUploadRepository mediaUploads) {
        this.mediaUploads = mediaUploads;
    }

    @Override
    @Transactional(readOnly = true)
    public Set<String> notUploadedBy(long accountId, Collection<String> urls) {
        Set<String> missing = new LinkedHashSet<>();
        if (urls == null) {
            return missing;
        }
        urls.stream().filter(Objects::nonNull).forEach(missing::add);
        if (missing.isEmpty()) {
            return missing;
        }
        missing.removeAll(mediaUploads.findUrlsOwnedBy(accountId, List.copyOf(missing), USABLE));
        return missing;
    }
}
