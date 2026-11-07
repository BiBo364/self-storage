package vn.lemar.selfstorage.notification.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;

import vn.lemar.selfstorage.notification.domain.OrderNotification;
import vn.lemar.selfstorage.notification.domain.OrderNotificationId;

public interface OrderNotificationRepository extends JpaRepository<OrderNotification, OrderNotificationId> {

    List<OrderNotification> findByOrderId(Long orderId);

    List<OrderNotification> findByNotificationIdIn(Collection<Long> notificationIds);

    Optional<OrderNotification> findByNotificationId(Long notificationId);

    /** Đơn đã có notification loại này chưa — dedup cron nhắc (mỗi đơn nhắc một lần). */
    @Query("select count(n) > 0 from OrderNotification o, Notification n "
            + "where o.notificationId = n.id and o.orderId = :orderId and n.type = :type")
    boolean existsForOrderAndType(@Param("orderId") Long orderId, @Param("type") String type);
}
