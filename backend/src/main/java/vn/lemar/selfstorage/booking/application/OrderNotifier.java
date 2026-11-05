package vn.lemar.selfstorage.booking.application;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import vn.lemar.selfstorage.booking.application.exception.RentalOrderNotFoundException;
import vn.lemar.selfstorage.booking.domain.RentalOrder;
import vn.lemar.selfstorage.booking.repository.RentalOrderRepository;
import vn.lemar.selfstorage.facility.application.FacilityAccess;
import vn.lemar.selfstorage.identity.application.AccountQueryService;
import vn.lemar.selfstorage.notification.application.NotificationRecipient;
import vn.lemar.selfstorage.notification.application.NotificationService;
import vn.lemar.selfstorage.notification.application.NotificationType;

/**
 * Gửi notification (web + email sau commit) gắn với một đơn thuê cho khách hoặc FM của cơ sở. Email lỗi
 * không rollback nghiệp vụ — {@link NotificationService} đã lo việc đó.
 */
@Component
public class OrderNotifier {

    private static final Logger LOG = LoggerFactory.getLogger(OrderNotifier.class);

    private final RentalOrderRepository orderRepository;
    private final FacilityAccess facilityAccess;
    private final AccountQueryService accountQueryService;
    private final NotificationService notificationService;

    public OrderNotifier(RentalOrderRepository orderRepository, FacilityAccess facilityAccess,
                  AccountQueryService accountQueryService, NotificationService notificationService) {
        this.orderRepository = orderRepository;
        this.facilityAccess = facilityAccess;
        this.accountQueryService = accountQueryService;
        this.notificationService = notificationService;
    }

    public void notifyCustomer(RentalOrder order, NotificationType type, Map<String, ?> values) {
        notifyCustomer(order.getCustomerId(), order.getId(), type, values);
    }

    /** Báo khách của lịch hẹn; {@code orderId} null thì chỉ ghi notification, không nối order. */
    public void notifyCustomer(Long customerId, Long orderId, NotificationType type, Map<String, ?> values) {
        notificationService.notify(customerId, accountQueryService.requireEmailById(customerId),
                NotificationRecipient.CUSTOMER, type, values, orderId);
    }

    /** FM = {@code facilities.fm_account_id} của cơ sở của đơn; cơ sở chưa gán FM thì chỉ log cảnh báo. */
    public void notifyFacilityManager(Long orderId, NotificationType type, Map<String, ?> values) {
        Long facilityId = orderRepository.findFacilityId(orderId).orElseThrow(RentalOrderNotFoundException::new);
        facilityAccess.managerAccountId(facilityId).ifPresentOrElse(
                fmId -> notificationService.notify(fmId, accountQueryService.requireEmailById(fmId),
                        NotificationRecipient.FM, type, values, orderId),
                () -> LOG.warn("Cơ sở {} chưa gán FM — bỏ qua notification {} cho đơn {}", facilityId, type, orderId));
    }

    /** Spec RentalOrder: {@code id} dùng làm mã đơn hiển thị cho khách trong thông báo/email. */
    static String orderCode(RentalOrder order) {
        return String.valueOf(order.getId());
    }
}
