package vn.lemar.selfstorage.booking.application;

import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.lemar.selfstorage.audit.application.AuditEntry;
import vn.lemar.selfstorage.audit.application.AuditRecorder;
import vn.lemar.selfstorage.audit.domain.AuditAction;
import vn.lemar.selfstorage.audit.domain.AuditEntityType;
import vn.lemar.selfstorage.booking.application.exception.RentalOrderNotFoundException;
import vn.lemar.selfstorage.booking.domain.RentalOrder;
import vn.lemar.selfstorage.booking.repository.RentalOrderRepository;

/**
 * Public API đổi trạng thái đơn cho module sau. Cron handover quá hạn dùng để trả đơn về
 * {@code Deposited} khi còn hạn giữ kho; mọi thay đổi đều ghi audit với actor System.
 */
@Service
public class RentalOrderLifecycle {

    private final RentalOrderRepository orderRepository;
    private final AuditRecorder auditRecorder;

    RentalOrderLifecycle(RentalOrderRepository orderRepository, AuditRecorder auditRecorder) {
        this.orderRepository = orderRepository;
        this.auditRecorder = auditRecorder;
    }

    /**
     * {@code InProgress -> Deposited} sau khi phiên bàn giao quá hạn nhưng đơn còn hạn giữ kho.
     * Chạy trong transaction của caller và khoá row đơn.
     */
    @Transactional
    public void returnToDeposited(Long orderId, String reason) {
        RentalOrder order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(RentalOrderNotFoundException::new);
        String previous = order.getStatus().dbValue();
        order.returnToDeposited();
        auditRecorder.record(new AuditEntry(AuditAction.RENTAL_ORDER_DEPOSITED,
                AuditEntityType.RENTAL_ORDER, String.valueOf(orderId),
                Map.of("status", previous),
                Map.of("status", order.getStatus().dbValue()),
                reason, null));
    }
}
