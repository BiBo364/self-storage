package vn.lemar.selfstorage.payment.repository;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.lemar.selfstorage.payment.domain.PaymentStatus;
import vn.lemar.selfstorage.payment.domain.PaymentTransaction;

public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, Long> {

    /**
     * Khoá row khi xử lý IPN ({@code SELECT ... FOR UPDATE}).
     *
     * <p>VNPay retry tới 10 lần và có thể gọi song song. Nếu chỉ đọc rồi kiểm trạng thái thì hai
     * IPN cùng đọc được {@code Pending} và cùng ghi {@code Success} — ghi nhận trùng tiền. Unique
     * constraint {@code vnp_txn_ref} không chặn được vì cả hai update cùng một row, không sinh row
     * thứ hai. Khoá ở đây buộc IPN thứ hai đợi, đọc lại thấy {@code Success} và trả về {@code 02}.
     *
     * <p>Phải gọi trong transaction. Hiện chỉ có luồng IPN dùng; thêm chỗ đọc thuần sau này thì
     * tách method riêng không khoá.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<PaymentTransaction> findByVnpTxnRef(String vnpTxnRef);

    /** Hoá đơn nào trong danh sách đang có giao dịch ở trạng thái {@code status} tạo sau mốc. */
    @Query("select distinct t.invoiceId from PaymentTransaction t where t.invoiceId in :invoiceIds "
            + "and t.status = :status and t.createdAt > :since")
    List<Long> findInvoiceIdsWithStatusSince(@Param("invoiceIds") Collection<Long> invoiceIds,
                                             @Param("status") PaymentStatus status, @Param("since") Instant since);

    /** Mốc cổng ghi nhận thu tiền sớm nhất của hoá đơn. */
    @Query("select min(t.paidAt) from PaymentTransaction t where t.invoiceId = :invoiceId and t.status = :status")
    Optional<Instant> findFirstPaidAt(@Param("invoiceId") Long invoiceId, @Param("status") PaymentStatus status);

    @Query("select t.vnpTxnRef from PaymentTransaction t where t.invoiceId in :invoiceIds "
            + "and t.status = vn.lemar.selfstorage.payment.domain.PaymentStatus.PENDING order by t.id")
    List<String> findPendingTxnRefsByInvoiceIds(@Param("invoiceIds") Collection<Long> invoiceIds);

    /** Giao dịch mới nhất của hoá đơn (đọc thuần, không khoá). */
    Optional<PaymentTransaction> findFirstByInvoiceIdOrderByCreatedAtDescIdDesc(Long invoiceId);

    /** Giao dịch {@code Pending} tạo trước mốc — đầu vào của job đối soát. */
    @Query("select t.vnpTxnRef from PaymentTransaction t where t.status = :status and t.createdAt < :before "
            + "order by t.createdAt")
    List<String> findTxnRefsByStatusCreatedBefore(@Param("status") PaymentStatus status,
                                                  @Param("before") Instant before);
}
