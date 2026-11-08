package vn.lemar.selfstorage.booking.repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.lemar.selfstorage.booking.domain.ProposalFeedback;
import vn.lemar.selfstorage.booking.domain.ProposalStatus;

public interface ProposalFeedbackRepository extends JpaRepository<ProposalFeedback, Long> {

    /**
     * Khoá row proposal khi khách đồng ý/từ chối — bấm đúp hoặc mở hai tab thì request sau phải
     * đợi và thấy proposal đã hết {@code Pending}. Phải gọi trong transaction.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from ProposalFeedback p where p.id = :id")
    Optional<ProposalFeedback> findByIdForUpdate(@Param("id") Long id);

    /** Đơn và khách của proposal, đọc không khoá và không nạp entity — để khoá đơn trước rồi mới khoá proposal. */
    @Query("select new vn.lemar.selfstorage.booking.repository.ProposalFeedbackRepository$Owner("
            + "p.orderId, p.customerId) from ProposalFeedback p where p.id = :id")
    Optional<Owner> findOwnerById(@Param("id") Long id);

    record Owner(Long orderId, Long customerId) {
    }

    boolean existsByOrderIdAndStatus(Long orderId, ProposalStatus status);

    long countByOrderIdAndStatus(Long orderId, ProposalStatus status);

    List<ProposalFeedback> findByOrderIdAndStatusIn(Long orderId, List<ProposalStatus> statuses);

    /** Lần khách đồng ý đề xuất gần nhất của đơn (không có → empty). */
    @Query("select max(p.agreedAt) from ProposalFeedback p where p.orderId = :orderId and p.status = :status")
    Optional<java.time.Instant> findLatestAgreedAt(@Param("orderId") Long orderId,
                                                   @Param("status") ProposalStatus status);

    /** Các khoang khách đã từ chối trong cùng đơn — mặc định không được đề xuất lại. */
    @Query("select distinct p.unitId from ProposalFeedback p where p.orderId = :orderId and p.status = :status")
    List<Long> findUnitIdsByOrderIdAndStatus(@Param("orderId") Long orderId, @Param("status") ProposalStatus status);
}
