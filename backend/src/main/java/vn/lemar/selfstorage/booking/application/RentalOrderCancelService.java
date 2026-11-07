package vn.lemar.selfstorage.booking.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.lemar.selfstorage.booking.application.exception.BookingConflictException;
import vn.lemar.selfstorage.booking.application.exception.BookingNotFoundException;
import vn.lemar.selfstorage.booking.application.exception.BookingValidationException;
import vn.lemar.selfstorage.booking.domain.RentalOrder;
import vn.lemar.selfstorage.booking.repository.RentalOrderRepository;
import vn.lemar.selfstorage.identity.application.Access;

/**
 * Huỷ đơn chủ động — chạy RentalOrder Cancellation Cascade với lý do tương ứng.
 *
 * <ul>
 *   <li><b>FM</b> (spec 1.3 "Khách không phản hồi proposal" → Hủy đơn): quyền {@code rental_order.cancel} theo cơ sở,
 *       đơn chưa đóng; lý do mặc định "Khách không còn nhu cầu".</li>
 *   <li><b>Khách</b> (spec 1.5 NOTES + 2.5): chủ đơn, đơn chưa đóng; lý do "Khách hủy đơn". Huỷ sau khi đã cọc thì
 *       mất cọc (cảnh báo hiển thị ở FE trước khi gọi). Đơn đang bàn giao ({@code InProgress}) vẫn huỷ được — cascade
 *       đóng biên bản + hợp đồng chưa kích hoạt.</li>
 * </ul>
 */
@Service
public class RentalOrderCancelService {

    static final String PERMISSION_CANCEL = "rental_order.cancel";
    static final String REASON_FM_DEFAULT = "Khách không còn nhu cầu";
    static final String REASON_CUSTOMER = "Khách hủy đơn";
    static final String MSG_NOT_FOUND = "Không tìm thấy đơn thuê";
    static final String MSG_CLOSED = "Đơn thuê đã đóng";

    private final RentalOrderRepository orderRepository;
    private final RentalOrderCancellation cancellation;
    private final Access access;

    RentalOrderCancelService(RentalOrderRepository orderRepository, RentalOrderCancellation cancellation,
                             Access access) {
        this.orderRepository = orderRepository;
        this.cancellation = cancellation;
        this.access = access;
    }

    /** Kết quả huỷ. */
    public record CancelResult(Long orderId, String status, String cancelReason) {
    }

    @Transactional
    public CancelResult cancelByManager(Long orderId, String reason, Long actorAccountId) {
        Long facilityId = orderRepository.findFacilityId(orderId)
                .orElseThrow(() -> new BookingNotFoundException(MSG_NOT_FOUND));
        access.can(PERMISSION_CANCEL, facilityId);
        String effective = reason == null || reason.isBlank() ? REASON_FM_DEFAULT : reason.trim();
        if (effective.length() > 500) {
            throw new BookingValidationException("Lý do huỷ tối đa 500 ký tự");
        }
        RentalOrder order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new BookingNotFoundException(MSG_NOT_FOUND));
        return cancel(order, effective, actorAccountId);
    }

    @Transactional
    public CancelResult cancelByCustomer(Long orderId, Long customerId) {
        RentalOrder order = orderRepository.findByIdForUpdate(orderId)
                .filter(o -> o.getCustomerId().equals(customerId))
                .orElseThrow(() -> new BookingNotFoundException(MSG_NOT_FOUND));
        return cancel(order, REASON_CUSTOMER, customerId);
    }

    private CancelResult cancel(RentalOrder order, String reason, Long actorAccountId) {
        if (order.getStatus().isClosed()) {
            throw new BookingConflictException(MSG_CLOSED);
        }
        cancellation.cancel(order, reason, actorAccountId);
        return new CancelResult(order.getId(), order.getStatus().dbValue(), order.getCancelReason());
    }
}
