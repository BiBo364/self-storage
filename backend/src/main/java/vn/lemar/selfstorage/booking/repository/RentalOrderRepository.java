package vn.lemar.selfstorage.booking.repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.lemar.selfstorage.booking.domain.RentalOrder;

public interface RentalOrderRepository extends JpaRepository<RentalOrder, Long> {

    /**
     * Khoá row đơn khi đề xuất lại: hai FM cùng bấm "Đề xuất lại" một lúc thì người sau phải đợi
     * và thấy proposal {@code Pending} người trước vừa tạo. Phải gọi trong transaction.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from RentalOrder o where o.id = :id")
    Optional<RentalOrder> findByIdForUpdate(@Param("id") Long id);

    /**
     * Cơ sở của đơn — đi qua {@code rental_requests.facility_id} vì {@code rental_orders} không có
     * cột facility. Native query để không phải map entity RentalRequest (thuộc luồng B1).
     */
    /** Các đơn cùng trỏ vào một khoang — nền để suy ra khóa phiên thanh toán theo khoang. */
    List<RentalOrder> findByUnitId(Long unitId);

    @Query(value = "select r.facility_id from rental_orders o join rental_requests r on r.id = o.request_id "
            + "where o.id = :orderId", nativeQuery = true)
    Optional<Long> findFacilityId(@Param("orderId") Long orderId);

    /** Cron catch-all: đơn chưa đóng đã quá {@code expires_at}. */
    @Query("select o.id from RentalOrder o where o.expiresAt < :now "
            + "and o.status not in (vn.lemar.selfstorage.booking.domain.RentalOrderStatus.CANCELED, "
            + "vn.lemar.selfstorage.booking.domain.RentalOrderStatus.DONE) order by o.id")
    List<Long> findExpiredOpenIds(@Param("now") Instant now);

    /** Cron nhắc: đơn chưa đóng sẽ hết hạn trong khoảng {@code (now, until]}. */
    @Query("select o.id from RentalOrder o where o.expiresAt > :now and o.expiresAt <= :until "
            + "and o.status not in (vn.lemar.selfstorage.booking.domain.RentalOrderStatus.CANCELED, "
            + "vn.lemar.selfstorage.booking.domain.RentalOrderStatus.DONE) order by o.id")
    List<Long> findExpiringOpenIds(@Param("now") Instant now, @Param("until") Instant until);

    /** Khách đã đến nhận lịch check-in nào của đơn chưa (phân biệt lý do huỷ catch-all). */
    @Query(value = """
            select count(*) > 0 from appointments a
            join rental_appointments ra on ra.appointment_id = a.id
            where ra.order_id = :orderId and a.arrived_at is not null
            """, nativeQuery = true)
    boolean hasArrivedAppointment(@Param("orderId") Long orderId);

    /** Trạng thái proposal mới nhất của đơn. */
    @Query(value = "select status from proposal_feedbacks where order_id = :orderId order by id desc limit 1",
            nativeQuery = true)
    Optional<String> findLatestProposalStatus(@Param("orderId") Long orderId);
}
