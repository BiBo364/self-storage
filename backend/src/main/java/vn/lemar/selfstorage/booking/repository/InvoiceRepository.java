package vn.lemar.selfstorage.booking.repository;

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
import vn.lemar.selfstorage.booking.domain.Invoice;
import vn.lemar.selfstorage.booking.domain.InvoiceStatus;
import vn.lemar.selfstorage.booking.domain.InvoiceType;

public interface InvoiceRepository extends JpaRepository<Invoice, Long> {

    List<Invoice> findByOrderIdAndStatus(Long orderId, InvoiceStatus status);

    /** Cơ sở của hoá đơn — hoá đơn gắn đơn hoặc gắn hợp đồng (qua đơn của hợp đồng). */
    @Query(value = "select r.facility_id from invoices i "
            + "left join rental_contracts c on c.id = i.contract_id "
            + "join rental_orders o on o.id = coalesce(i.order_id, c.order_id) "
            + "join rental_requests r on r.id = o.request_id where i.id = :invoiceId", nativeQuery = true)
    Optional<Long> findFacilityIdOfInvoice(@Param("invoiceId") Long invoiceId);

    /** Khoá row hoá đơn — thanh toán, huỷ, hết hạn đều khoá rồi mới kiểm trạng thái. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Invoice i where i.id = :id")
    Optional<Invoice> findByIdForUpdate(@Param("id") Long id);

    /**
     * Đơn của hoá đơn — đọc trước để khoá đơn rồi mới khoá hoá đơn. Hoá đơn gắn hợp đồng (tháng đầu) không có
     * {@code order_id} (CHECK V1: đúng một nguồn) → lấy đơn qua {@code rental_contracts.order_id}.
     */
    @Query(value = "select coalesce(i.order_id, c.order_id) from invoices i "
            + "left join rental_contracts c on c.id = i.contract_id where i.id = :id", nativeQuery = true)
    Optional<Long> findOrderIdById(@Param("id") Long id);

    /** Hợp đồng của hoá đơn (projection) — hoá đơn tháng đầu/gia hạn gắn {@code contract_id}. */
    @Query("select i.contractId from Invoice i where i.id = :id")
    Optional<Long> findContractIdById(@Param("id") Long id);

    /** Hoá đơn cọc/bổ sung {@code Unpaid} của mọi đơn đang trỏ khoang — đầu vào kiểm phiên thanh toán đang mở. */
    @Query(value = """
            select i.id from invoices i
            join rental_orders o on o.id = i.order_id
            where o.unit_id = :unitId and i.status = 'Unpaid' and i.type in ('Deposit', 'DepositSupplement')
            """, nativeQuery = true)
    List<Long> findUnpaidDepositIdsByUnitId(@Param("unitId") Long unitId);

    /** Hoá đơn {@code Unpaid} đã quá {@code due_date} — cron hết hạn. */
    @Query("select i.id from Invoice i where i.status = vn.lemar.selfstorage.booking.domain.InvoiceStatus.UNPAID "
            + "and i.dueDate < :now order by i.id")
    List<Long> findOverdueUnpaidIds(@Param("now") Instant now);

    /** Hoá đơn cọc đầy đủ đã thanh toán của đơn (V11: tối đa một hoá đơn {@code Deposit} active). */
    Optional<Invoice> findFirstByOrderIdAndTypeAndStatusOrderByIdDesc(Long orderId, InvoiceType type,
                                                                     InvoiceStatus status);

    List<Invoice> findByContractIdOrderByIdDesc(Long contractId);

    /** Tổng tiền đã thu của các loại hoá đơn trên đơn — dùng để tính chênh lệch cọc. */
    @Query("select coalesce(sum(i.amount), 0) from Invoice i where i.orderId = :orderId "
            + "and i.type in :types and i.status = :status")
    BigDecimal sumAmount(@Param("orderId") Long orderId, @Param("types") Collection<InvoiceType> types,
                         @Param("status") InvoiceStatus status);

    /** Có hoá đơn cọc/bổ sung còn hiệu lực chưa? — chặn 2 hoá đơn cọc active trên cùng đơn. */
    boolean existsByOrderIdAndStatusInAndTypeIn(Long orderId, Collection<InvoiceStatus> statuses,
                                                Collection<InvoiceType> types);

    @Query(value = "select distinct contract_id from invoices where status = 'Unpaid' and contract_id is not null "
            + "and code like :prefix order by contract_id", nativeQuery = true)
    List<Long> findUnpaidContractIdsByCodePrefix(@Param("prefix") String prefix);
}
