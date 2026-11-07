package vn.lemar.selfstorage.booking.repository;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.lemar.selfstorage.booking.domain.RentalRequest;
import vn.lemar.selfstorage.booking.domain.RentalRequestRejectReason;
import vn.lemar.selfstorage.booking.domain.RentalRequestStatus;

public interface RentalRequestRepository extends JpaRepository<RentalRequest, Long> {

    Page<RentalRequest> findByFacilityIdAndStatus(Long facilityId, RentalRequestStatus status,
            Pageable pageable);

    Page<RentalRequest> findByFacilityId(Long facilityId, Pageable pageable);

    /**
     * Conditional update chống race duyệt (spec draft.md:135): khoá row, tx thứ 2 re-evaluate
     * {@code WHERE status = 'Pending'} và nhận 0 row. Trả về số row cập nhật — 0 nghĩa là request
     * đã được xử lý bởi FM khác.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update RentalRequest r set r.status = :newStatus, r.unitId = :unitId, "
            + "r.respondedAt = :respondedAt, r.expiresAt = :expiresAt "
            + "where r.id = :id and r.status = :expectedStatus")
    int approveIfPending(@Param("id") Long id, @Param("unitId") Long unitId,
            @Param("respondedAt") Instant respondedAt, @Param("expiresAt") Instant expiresAt,
            @Param("expectedStatus") RentalRequestStatus expectedStatus,
            @Param("newStatus") RentalRequestStatus newStatus);

    /**
     * B2 — {@code Approved → Converted} khi đã sinh đơn, cùng cơ chế {@link #approveIfPending}:
     * chỉ request {@code Approved} chưa quá {@code expires_at} mới chuyển được (spec RentalRequest ›
     * CONSTRAINTS). 0 row nghĩa là request không còn convert được.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update RentalRequest r "
            + "set r.status = vn.lemar.selfstorage.booking.domain.RentalRequestStatus.Converted "
            + "where r.id = :id "
            + "and r.status = vn.lemar.selfstorage.booking.domain.RentalRequestStatus.Approved "
            + "and r.expiresAt > :now")
    int convertIfApproved(@Param("id") Long id, @Param("now") Instant now);

    /** Cron: request {@code Pending}/{@code Approved} đã quá {@code expires_at}. */
    @Query("select r.id from RentalRequest r where r.status in :statuses and r.expiresAt < :now order by r.id")
    List<Long> findExpiredIds(@Param("statuses") Collection<RentalRequestStatus> statuses, @Param("now") Instant now);

    /** Cron: {@code -> Expired} có điều kiện trạng thái — FM duyệt đúng lúc đó thì 0 row, không ghi đè. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update RentalRequest r set r.status = vn.lemar.selfstorage.booking.domain.RentalRequestStatus.Expired "
            + "where r.id = :id and r.status in :statuses and r.expiresAt < :now")
    int expireIfOpen(@Param("id") Long id, @Param("statuses") Collection<RentalRequestStatus> statuses,
                     @Param("now") Instant now);

    @Query("select r.status from RentalRequest r where r.id = :id")
    Optional<RentalRequestStatus> findStatusById(@Param("id") Long id);

    @Query("select r.normalizedCustomerEmail from RentalRequest r where r.id = :id")
    Optional<String> findEmailById(@Param("id") Long id);

    /**
     * B2 — request {@code Approved} còn hạn khớp email của account (spec 1.2 Case A), khoá row, cũ
     * trước: request duyệt trước được convert trước khi trùng khoang. Phải gọi trong transaction.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RentalRequest r where r.normalizedCustomerEmail = :email "
            + "and r.status = vn.lemar.selfstorage.booking.domain.RentalRequestStatus.Approved "
            + "and r.expiresAt > :now order by r.id")
    List<RentalRequest> findClaimableForUpdate(@Param("email") String normalizedEmail, @Param("now") Instant now);

    /** Conditional update chống race từ chối — cùng cơ chế {@link #approveIfPending}. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update RentalRequest r set r.status = :newStatus, r.rejectReason = :rejectReason, "
            + "r.rejectNote = :rejectNote, r.respondedAt = :respondedAt "
            + "where r.id = :id and r.status = :expectedStatus")
    int rejectIfPending(@Param("id") Long id,
            @Param("rejectReason") RentalRequestRejectReason rejectReason,
            @Param("rejectNote") String rejectNote, @Param("respondedAt") Instant respondedAt,
            @Param("expectedStatus") RentalRequestStatus expectedStatus,
            @Param("newStatus") RentalRequestStatus newStatus);
}
