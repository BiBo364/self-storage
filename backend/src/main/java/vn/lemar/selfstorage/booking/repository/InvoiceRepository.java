package vn.lemar.selfstorage.booking.repository;

import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.lemar.selfstorage.booking.domain.Invoice;
import vn.lemar.selfstorage.booking.domain.InvoiceStatus;
import vn.lemar.selfstorage.booking.domain.InvoiceType;

public interface InvoiceRepository extends JpaRepository<Invoice, Long> {

    List<Invoice> findByOrderIdAndStatus(Long orderId, InvoiceStatus status);

    /** Invoice còn Unpaid theo loại — job sửa state booking khi listener IPN lỗi. */
    List<Invoice> findByStatusAndTypeIn(InvoiceStatus status, Collection<InvoiceType> types);

    /** Tổng tiền đã thu của các loại hoá đơn trên đơn — dùng để tính chênh lệch cọc. */
    @Query("select coalesce(sum(i.amount), 0) from Invoice i where i.orderId = :orderId "
            + "and i.type in :types and i.status = :status")
    BigDecimal sumAmount(@Param("orderId") Long orderId, @Param("types") Collection<InvoiceType> types,
                         @Param("status") InvoiceStatus status);

    /** Có hoá đơn cọc/bổ sung còn hiệu lực chưa? — chặn 2 hoá đơn cọc active trên cùng đơn. */
    boolean existsByOrderIdAndStatusInAndTypeIn(Long orderId, Collection<InvoiceStatus> statuses,
                                                Collection<InvoiceType> types);

    /** Hóa đơn của một nhóm đơn — dùng để hỏi payment xem khoang có phiên nào đang mở. */
    List<Invoice> findByOrderIdIn(List<Long> orderIds);

    /** Chỉ lấy đơn của hoá đơn, không nạp entity — để khoá row đơn trước khi đụng tới hoá đơn. */
    @Query("select i.orderId from Invoice i where i.id = :id")
    Optional<Long> findOrderIdById(@Param("id") Long id);

    /** Như trên nhưng lọc đúng khách — endpoint khách không khoá row đơn của người khác. */
    @Query("select i.orderId from Invoice i where i.id = :id and i.customerId = :customerId")
    Optional<Long> findOrderIdByIdAndCustomerId(@Param("id") Long id, @Param("customerId") Long customerId);

    /**
     * Khoá row hoá đơn ({@code SELECT ... FOR UPDATE}).
     *
     * <p>Huỷ hoá đơn và ghi nhận thanh toán là hai luồng khác nhau cùng sửa một dòng: B3 huỷ khi
     * đề xuất lại, IPN đánh {@code Paid}. Kiểm trạng thái trong bộ nhớ rồi ghi đè là không đủ —
     * phải khoá. Luôn khoá <strong>sau</strong> row đơn để cùng thứ tự với B3, tránh deadlock.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Invoice i where i.id = :id")
    Optional<Invoice> findByIdForUpdate(@Param("id") Long id);
}
