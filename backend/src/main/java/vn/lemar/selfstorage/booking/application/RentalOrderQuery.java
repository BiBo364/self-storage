package vn.lemar.selfstorage.booking.application;

import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.lemar.selfstorage.booking.domain.RentalOrder;
import vn.lemar.selfstorage.booking.repository.RentalOrderRepository;

/**
 * Public API của booking cho module sau đọc thông tin đơn — hiện chỉ hạn giữ kho.
 *
 * <p>{@code handover} dùng để tính {@code HandoverRecord.due_at}; không module nào được gọi
 * thẳng repository của booking.
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
}
