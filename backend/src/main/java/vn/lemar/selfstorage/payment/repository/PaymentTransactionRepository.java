package vn.lemar.selfstorage.payment.repository;

import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.lemar.selfstorage.payment.domain.PaymentDirection;
import vn.lemar.selfstorage.payment.domain.PaymentStatus;
import vn.lemar.selfstorage.payment.domain.PaymentTransaction;

public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, Long> {

    /** Tổng tiền giao dịch {@code Success} theo chiều (PAY / REFUND) của hoá đơn. */
    @Query("select coalesce(sum(t.amount), 0) from PaymentTransaction t where t.invoiceId = :invoiceId "
            + "and t.status = vn.lemar.selfstorage.payment.domain.PaymentStatus.SUCCESS and t.direction = :direction")
    BigDecimal sumSucceeded(
            @Param("invoiceId") Long invoiceId,
            @Param("direction") PaymentDirection direction);

    /** Tổng tiền giao dịch theo chiều + trạng thái của hoá đơn (vd {@code PAY}/{@code Duplicate}). */
    @Query("select coalesce(sum(t.amount), 0) from PaymentTransaction t where t.invoiceId = :invoiceId "
            + "and t.direction = :direction and t.status = :status")
    BigDecimal sumByStatus(@Param("invoiceId") Long invoiceId, @Param("direction") PaymentDirection direction,
                           @Param("status") PaymentStatus status);

    List<PaymentTransaction> findByInvoiceIdAndDirectionOrderByIdAsc(Long invoiceId,
            PaymentDirection direction);

    /**
     * Khoá row khi xử lý IPN ({@code SELECT ... FOR UPDATE}).
     *
     * <p>VNPay retry tới 10 lần và có thể gọi song song. Nếu chỉ đọc rồi kiểm trạng thái thì hai
     * IPN cùng đọc được {@code Pending} và cùng ghi {@code Success} — ghi nhận trùng tiền. Unique
     * constraint {@code vnp_txn_ref} không chặn được vì cả hai update cùng một row, không sinh row
     * thứ hai. Khoá ở đây buộc IPN thứ hai đợi, đọc lại thấy {@code Success} và trả về {@code 02}.
     *
     * <p>Phải gọi trong transaction. IPN và đối soát thủ công đều dùng; thêm chỗ đọc thuần sau này
     * thì tách method riêng không khoá.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<PaymentTransaction> findByVnpTxnRef(String vnpTxnRef);

    /**
     * Có giao dịch nào của nhóm hóa đơn này còn mở không — {@code Pending} và tạo sau {@code since}.
     * Dùng cho khóa giao dịch ngắn hạn ở Flow 1.4; không khóa row vì chỉ là phép đọc.
     */
    boolean existsByInvoiceIdInAndStatusAndCreatedAtAfter(
            Collection<Long> invoiceIds, PaymentStatus status, Instant since);

    /** Đã có giao dịch thu tiền thành công cho hoá đơn này chưa — chặn mở phiên thứ hai. */
    boolean existsByInvoiceIdAndDirectionAndStatus(
            Long invoiceId, PaymentDirection direction, PaymentStatus status);

    /** Mã giao dịch cổng đã được ghi cho giao dịch khác chưa — chặn đối soát thủ công trùng. */
    boolean existsByGatewayTransactionNo(String gatewayTransactionNo);

    /**
     * Khóa theo invoice trong transaction hiện tại. Hai manual request trên hai transaction khác
     * nhau của cùng invoice phải serialize trước khi kiểm PAY/Success; khóa row đơn lẻ không đủ.
     */
    @Query(value = "SELECT pg_advisory_xact_lock(:invoiceId)", nativeQuery = true)
    void lockInvoiceForPayment(@Param("invoiceId") Long invoiceId);

    /** Phiên Pending còn hạn của invoice — dùng để dựng lại URL khi cache mất. */
    Optional<PaymentTransaction> findFirstByInvoiceIdAndStatusAndCreatedAtAfterOrderByCreatedAtDesc(
            Long invoiceId, PaymentStatus status, Instant since);

    /** Giao dịch PAY/Success gần nhất của invoice — job booking dùng để sửa state sau listener lỗi. */
    Optional<PaymentTransaction> findFirstByInvoiceIdAndDirectionAndStatusOrderByPaidAtDesc(
            Long invoiceId, PaymentDirection direction, PaymentStatus status);

    /** Toàn bộ giao dịch PAY của nhóm invoice, mới nhất trước — read model cho FM. */
    List<PaymentTransaction> findByInvoiceIdInAndDirectionOrderByCreatedAtDesc(
            Collection<Long> invoiceIds, PaymentDirection direction);
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

}
