package vn.lemar.selfstorage.handover.application;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import vn.lemar.selfstorage.booking.application.AppointmentArrived;

/**
 * Flow 2.1: booking xác nhận khách đến → set {@code HandoverRecord.due_at}.
 *
 * <p>Chạy {@code BEFORE_COMMIT} trong transaction của arrive: lỗi ở đây rollback cả thao tác arrive,
 * nên record thiếu/đã đóng là no-op chứ không ném. Record do Flow 1 tạo khi đặt lịch; đến khi luồng đó
 * có code, listener vẫn chạy nhưng không tìm thấy gì.
 */
@Component
class AppointmentArrivedListener {

    private final HandoverService handoverService;

    AppointmentArrivedListener(HandoverService handoverService) {
        this.handoverService = handoverService;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    void onAppointmentArrived(AppointmentArrived event) {
        handoverService.onAppointmentArrived(event.appointmentId(), event.arrivedAt());
    }
}
