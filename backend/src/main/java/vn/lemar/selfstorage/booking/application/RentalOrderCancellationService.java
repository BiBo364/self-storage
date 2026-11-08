package vn.lemar.selfstorage.booking.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.lemar.selfstorage.booking.application.exception.RentalOrderNotFoundException;
import vn.lemar.selfstorage.booking.domain.RentalOrder;
import vn.lemar.selfstorage.booking.repository.RentalOrderRepository;

/**
 * Public API chạy Cancellation Cascade cho module sau; cron handover quá hạn gọi khi đơn cũng
 * hết hạn giữ kho. Khoá đơn trước rồi mới chạy cascade hiện có, actor System.
 */
@Service
public class RentalOrderCancellationService {

    private final RentalOrderRepository orderRepository;
    private final RentalOrderCancellation cancellation;

    RentalOrderCancellationService(RentalOrderRepository orderRepository,
                                   RentalOrderCancellation cancellation) {
        this.orderRepository = orderRepository;
        this.cancellation = cancellation;
    }

    @Transactional
    public void cancel(Long orderId, String reason) {
        RentalOrder order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(RentalOrderNotFoundException::new);
        cancellation.cancel(order, reason, null);
    }
}
