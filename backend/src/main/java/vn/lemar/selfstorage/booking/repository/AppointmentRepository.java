package vn.lemar.selfstorage.booking.repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.lemar.selfstorage.booking.domain.Appointment;

public interface AppointmentRepository extends JpaRepository<Appointment, Long> {

    /** Cơ sở của lịch hẹn — đọc trước để kiểm quyền theo cơ sở rồi mới khoá row. */
    @Query("select a.facilityId from Appointment a where a.id = :id")
    Optional<Long> findFacilityId(@Param("id") Long id);

    /**
     * Khoá row lịch hẹn khi FM phân công FS — hai FM / bấm đúp thì request sau đợi và thấy lịch đã có FS.
     * Phải gọi trong transaction.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Appointment a where a.id = :id")
    Optional<Appointment> findByIdForUpdate(@Param("id") Long id);

    /** Đơn thuê nối với lịch hẹn qua {@code rental_appointments} (bảng nối, không có entity). */
    @Query(value = "select order_id from rental_appointments where appointment_id = :id", nativeQuery = true)
    Optional<Long> findOrderId(@Param("id") Long id);

    /**
     * Id các lịch {@code Pending} của đơn — cascade huỷ đơn lấy danh sách rồi khoá từng row theo thứ tự id.
     */
    @Query(value = """
            select a.id from appointments a
            join rental_appointments ra on ra.appointment_id = a.id
            where ra.order_id = :orderId and a.status = 'Pending'
            order by a.id""", nativeQuery = true)
    List<Long> findPendingIdsByOrderId(@Param("orderId") Long orderId);
}
