package vn.lemar.selfstorage.booking.application;

import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.lemar.selfstorage.booking.domain.RentalOrder;
import vn.lemar.selfstorage.booking.repository.RentalOrderRepository;

/**
 * Public API của booking cho module sau đọc thông tin đơn — hạn giữ kho và trạng thái.
 *
 * <p>{@code handover} dùng để tính {@code HandoverRecord.due_at} và quyết định nhánh cron quá hạn;
 * không module nào được gọi thẳng repository của booking.
 */
@Service
public class RentalOrderQuery {

    private final RentalOrderRepository orderRepository;

    RentalOrderQuery(RentalOrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    /**
     * Hạn giữ kho của đơn; rỗng khi đơn không tồn tại hoặc chưa set {@code expires_at}.
     * Caller phải xử lý cả hai như "không có trần".
     */
    @Transactional(readOnly = true)
    public Optional<Instant> expiresAt(Long orderId) {
        return orderRepository.findById(orderId).map(RentalOrder::getExpiresAt);
    }

    /** Ảnh chụp đơn cho module khác — trạng thái dạng giá trị DB, không lộ entity/domain. */
    @Transactional(readOnly = true)
    public Optional<Snapshot> find(Long orderId) {
        return orderRepository.findById(orderId)
                .map(order -> new Snapshot(order.getId(), order.getCustomerId(),
                        order.getStatus().dbValue(), order.getExpiresAt()));
    }

    public record Snapshot(Long id, Long customerId, String status, Instant expiresAt) {

        public boolean inProgress() {
            return "InProgress".equals(status);
        }

        public boolean done() {
            return "Done".equals(status);
        }
    }
}
