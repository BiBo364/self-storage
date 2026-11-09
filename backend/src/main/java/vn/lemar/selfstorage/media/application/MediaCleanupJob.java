package vn.lemar.selfstorage.media.application;

import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Cron dọn upload bỏ ngang: {@code Uploading}/{@code Pending} không đổi trạng thái quá
 * {@code media.orphan-cleanup.max-age} (mặc định 3 ngày) → xoá asset trên storage, row {@code Deleted}. Mỗi row một
 * transaction; storage lỗi thì row giữ nguyên cho lượt sau.
 */
@Component
public class MediaCleanupJob {

    private static final Logger LOG = LoggerFactory.getLogger(MediaCleanupJob.class);

    private final MediaService mediaService;
    private final Duration maxAge;

    MediaCleanupJob(MediaService mediaService, @Value("${media.orphan-cleanup.max-age:P3D}") Duration maxAge) {
        if (maxAge.isZero() || maxAge.isNegative()) {
            throw new IllegalArgumentException("Media orphan max age must be positive");
        }
        this.mediaService = mediaService;
        this.maxAge = maxAge;
    }

    @Scheduled(
            fixedDelayString = "${media.orphan-cleanup.fixed-delay-ms:3600000}",
            initialDelayString = "${media.orphan-cleanup.initial-delay-ms:300000}")
    public void run() {
        Instant cutoff = Instant.now().minus(maxAge);
        int done = 0;
        for (Long id : mediaService.abandonedIds(cutoff)) {
            try {
                if (mediaService.cleanupAbandoned(id, cutoff)) {
                    done++;
                }
            } catch (RuntimeException e) {
                LOG.warn("Không dọn được media upload {}: {}", id, e.getMessage());
            }
        }
        if (done > 0) {
            LOG.info("Dọn media upload bỏ ngang: {}", done);
        }
    }
}
