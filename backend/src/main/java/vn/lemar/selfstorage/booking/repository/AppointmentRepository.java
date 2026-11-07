package vn.lemar.selfstorage.booking.repository;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
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

    /** Nối lịch với đơn ({@code rental_appointments} không có entity). */
    @Modifying
    @Query(value = "insert into rental_appointments (appointment_id, order_id) values (:appointmentId, :orderId)",
            nativeQuery = true)
    void linkToOrder(@Param("appointmentId") Long appointmentId, @Param("orderId") Long orderId);

    /** Đơn đang có lịch {@code Pending} — chặn đặt lịch thứ hai. */
    @Query(value = """
            select count(*) > 0 from appointments a
            join rental_appointments ra on ra.appointment_id = a.id
            where ra.order_id = :orderId and a.status = 'Pending'
            """, nativeQuery = true)
    boolean existsPendingByOrderId(@Param("orderId") Long orderId);

    /** Đơn đã từng có lịch check-in bị huỷ (no-show, FS huỷ, quá hạn bàn giao) — lần đặt tiếp theo là đặt lại. */
    @Query(value = """
            select count(*) > 0 from appointments a
            join rental_appointments ra on ra.appointment_id = a.id
            where ra.order_id = :orderId and a.type = 'CHECKIN' and a.status = 'Canceled'
            """, nativeQuery = true)
    boolean existsCanceledCheckinByOrderId(@Param("orderId") Long orderId);

    /**
     * Khung giờ đã có lịch còn hiệu lực ({@code Pending}/{@code Done}) tại cơ sở trong khoảng ngày — check-in và trả
     * kho dùng chung ba khung cố định (cùng FS phục vụ).
     */
    @Query("select a.startedAt from Appointment a "
            + "where a.facilityId = :facilityId "
            + "and a.type in (vn.lemar.selfstorage.booking.domain.AppointmentType.CHECKIN, "
            + "vn.lemar.selfstorage.booking.domain.AppointmentType.RETURN) "
            + "and a.status <> vn.lemar.selfstorage.booking.domain.AppointmentStatus.Canceled "
            + "and a.date between :from and :to")
    List<Instant> findTakenSlotStarts(@Param("facilityId") Long facilityId, @Param("from") LocalDate from,
                                      @Param("to") LocalDate to);

    /** Khoá row cơ sở để serialize việc giữ khung giờ (capacity 1 lịch / khung / cơ sở). */
    @Query(value = "select id from facilities where id = :facilityId for update", nativeQuery = true)
    Optional<Long> lockFacility(@Param("facilityId") Long facilityId);

    /** Lịch mới nhất của đơn (theo id) — đọc thông tin hiển thị cho khách. */
    @Query(value = """
            select a.id from appointments a
            join rental_appointments ra on ra.appointment_id = a.id
            where ra.order_id = :orderId order by a.id desc limit 1
            """, nativeQuery = true)
    Optional<Long> findLatestIdByOrderId(@Param("orderId") Long orderId);

    /** Id các lịch check-in Pending đã quá end_at — cron no-show. */
    @Query("select a.id from Appointment a "
            + "where a.type = vn.lemar.selfstorage.booking.domain.AppointmentType.CHECKIN "
            + "and a.status = vn.lemar.selfstorage.booking.domain.AppointmentStatus.Pending "
            + "and a.endAt < :now order by a.id")
    List<Long> findNoShowDueIds(@Param("now") Instant now);
}
