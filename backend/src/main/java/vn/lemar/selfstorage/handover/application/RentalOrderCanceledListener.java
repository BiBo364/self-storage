package vn.lemar.selfstorage.handover.application;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import vn.lemar.selfstorage.booking.application.RentalOrderCanceled;

/**
 * Cancellation Cascade phần handover: đơn bị huỷ → record đang mở {@code CANCELED} + hợp đồng {@code Draft}/{@code
 * Signed} {@code Canceled}, cùng transaction huỷ đơn. {@link HandoverService#cancelByOrderId} idempotent theo trạng
 * thái nên gọi lặp không ghi audit trùng.
 */
@Component
class RentalOrderCanceledListener {

    private final HandoverService handoverService;

    RentalOrderCanceledListener(HandoverService handoverService) {
        this.handoverService = handoverService;
    }

    @EventListener
    public void on(RentalOrderCanceled event) {
        handoverService.cancelByOrderId(event.orderId(), event.reason());
    }
}
