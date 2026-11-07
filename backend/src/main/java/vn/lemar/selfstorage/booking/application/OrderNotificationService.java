package vn.lemar.selfstorage.booking.application;

import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.lemar.selfstorage.booking.application.exception.RentalOrderNotFoundException;
import vn.lemar.selfstorage.booking.repository.RentalOrderRepository;
import vn.lemar.selfstorage.notification.application.NotificationType;

/**
 * Public API gửi thông báo gắn đơn (in-app + email, nối {@code order_notifications}) cho các module sau bàn giao
 * (tenancy, checkout, overdue, support) — cùng cách booking báo khách và FM của cơ sở.
 */
@Service
public class OrderNotificationService {

    private final RentalOrderRepository orderRepository;
    private final OrderNotifier orderNotifier;

    OrderNotificationService(RentalOrderRepository orderRepository, OrderNotifier orderNotifier) {
        this.orderRepository = orderRepository;
        this.orderNotifier = orderNotifier;
    }

    @Transactional
    public void notifyCustomer(Long orderId, NotificationType type, Map<String, ?> values) {
        orderNotifier.notifyCustomer(orderRepository.findById(orderId).orElseThrow(RentalOrderNotFoundException::new),
                type, values);
    }

    /** FM của cơ sở của đơn; cơ sở chưa gán FM thì chỉ log cảnh báo. */
    @Transactional
    public void notifyFacilityManager(Long orderId, NotificationType type, Map<String, ?> values) {
        orderNotifier.notifyFacilityManager(orderId, type, values);
    }
}
