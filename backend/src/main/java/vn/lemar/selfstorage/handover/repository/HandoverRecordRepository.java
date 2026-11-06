package vn.lemar.selfstorage.handover.repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.lemar.selfstorage.handover.domain.HandoverRecord;

public interface HandoverRecordRepository extends JpaRepository<HandoverRecord, Long> {

    Optional<HandoverRecord> findByAppointmentId(Long appointmentId);

    List<HandoverRecord> findByOrderId(Long orderId);

    /** Record đang mở của đơn — partial unique index V1 bảo đảm tối đa một dòng. */
    @Query("select r from HandoverRecord r where r.orderId = :orderId "
            + "and r.result = vn.lemar.selfstorage.handover.domain.HandoverResult.IN_PROGRESS")
    Optional<HandoverRecord> findOpenByOrderId(@Param("orderId") Long orderId);

    /**
     * Khoá record khi listener đặt {@code due_at} — event arrive chỉ chạy trong transaction đã khoá
     * {@code appointments}; khoá thêm record để hai đường ghi không chồng nhau.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from HandoverRecord r where r.appointmentId = :appointmentId")
    Optional<HandoverRecord> findByAppointmentIdForUpdate(@Param("appointmentId") Long appointmentId);

    /**
     * Khoá record đang mở của đơn trước khi huỷ — dùng chung cho huỷ theo lịch hẹn và theo đơn.
     * Phải gọi trong transaction.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from HandoverRecord r where r.orderId = :orderId "
            + "and r.result = vn.lemar.selfstorage.handover.domain.HandoverResult.IN_PROGRESS")
    Optional<HandoverRecord> findOpenByOrderIdForUpdate(@Param("orderId") Long orderId);
}
