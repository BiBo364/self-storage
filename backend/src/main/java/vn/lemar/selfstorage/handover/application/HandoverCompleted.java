package vn.lemar.selfstorage.handover.application;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import vn.lemar.selfstorage.booking.application.HandoverOrderService;
import vn.lemar.selfstorage.notification.application.NotificationType;

/** Bàn giao vừa chốt (spec 2.4) — phát trong transaction chốt, xử lý sau commit. */
public record HandoverCompleted(Long handoverId, Long orderId, String unitCode) {

    /**
     * Gửi {@code HANDOVER_COMPLETED} (in-app + email) sau commit, transaction riêng: lỗi gửi thông báo không rollback
     * bàn giao (spec 2.4).
     */
    @Component
    static class Notifier {

        private static final Logger LOG = LoggerFactory.getLogger(Notifier.class);

        private final HandoverOrderService orders;
        private final String frontendUrl;

        Notifier(HandoverOrderService orders, @Value("${app.frontend-url}") String frontendUrl) {
            this.orders = orders;
            this.frontendUrl = frontendUrl.replaceAll("/+$", "");
        }

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public void on(HandoverCompleted event) {
            try {
                orders.notifyCustomer(event.orderId(), NotificationType.HANDOVER_COMPLETED,
                        Map.of("orderCode", String.valueOf(event.orderId()), "unitCode", event.unitCode(),
                                "handoverLink", frontendUrl + "/handovers/" + event.handoverId()));
            } catch (RuntimeException ex) {
                LOG.error("Không gửi được HANDOVER_COMPLETED cho đơn {}", event.orderId(), ex);
            }
        }
    }
}
