package vn.lemar.selfstorage.media.repository;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.lemar.selfstorage.media.domain.MediaUpload;
import vn.lemar.selfstorage.media.domain.MediaUploadStatus;

public interface MediaUploadRepository extends JpaRepository<MediaUpload, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from MediaUpload m where m.id = :id")
    Optional<MediaUpload> findByIdForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from MediaUpload m where m.accountId = :accountId and m.batchId = :batchId order by m.id")
    List<MediaUpload> findByAccountIdAndBatchIdForUpdate(
            @Param("accountId") Long accountId, @Param("batchId") UUID batchId);

    boolean existsByBatchId(UUID batchId);

    /** URL (trong {@code urls}) của các upload thuộc account và đang ở một trong {@code statuses}. */
    @Query("select distinct m.url from MediaUpload m where m.accountId = :accountId and m.url in :urls "
            + "and m.status in :statuses")
    List<String> findUrlsOwnedBy(@Param("accountId") Long accountId, @Param("urls") Collection<String> urls,
                                 @Param("statuses") Collection<MediaUploadStatus> statuses);
}
