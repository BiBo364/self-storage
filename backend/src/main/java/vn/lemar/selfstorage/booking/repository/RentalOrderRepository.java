package vn.lemar.selfstorage.booking.repository;

import jakarta.persistence.LockModeType;
import java.util.List;
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
}
