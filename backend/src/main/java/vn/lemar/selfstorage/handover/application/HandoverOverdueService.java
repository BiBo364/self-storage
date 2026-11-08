package vn.lemar.selfstorage.handover.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.lemar.selfstorage.booking.application.RentalOrderCancellationService;
import vn.lemar.selfstorage.booking.application.RentalOrderLifecycle;
import vn.lemar.selfstorage.booking.application.RentalOrderQuery;
import vn.lemar.selfstorage.handover.domain.HandoverRecord;
import vn.lemar.selfstorage.handover.domain.HandoverResult;
import vn.lemar.selfstorage.handover.repository.HandoverRecordRepository;
import vn.lemar.selfstorage.identity.application.AccountQueryService;
import vn.lemar.selfstorage.notification.application.NotificationRecipient;
import vn.lemar.selfstorage.notification.application.NotificationService;
import vn.lemar.selfstorage.notification.application.NotificationType;

/**
 * Cron Flow 2: đóng phiên bàn giao quá {@code due_at}.
 *
 * <ul>
 *   <li>Record + contract nháp bị huỷ trước (một cascade của {@link HandoverService}).</li>
 *   <li>Còn hạn giữ kho: đơn về {@code Deposited}, khoang giữ {@code Reserved}, báo khách
 *       {@code HANDOVER_OVERDUE}.</li>
 *   <li>Hết hạn giữ kho: chạy Cancellation Cascade, đơn/khoang/hóa đơn xử lý theo luồng huỷ.</li>
 * </ul>
 *
 * <p>Chạy một instance (cùng scope cron Appointment); record row lock + điều kiện trạng thái giữ
 * idempotent. Một record lỗi không chặn cả lượt — job bắt và log.
 */
@Service
public class HandoverOverdueService {

    static final String REASON = "Chưa hoàn tất bàn giao trong hạn";

    private static final Logger LOG = LoggerFactory.getLogger(HandoverOverdueService.class);

    private final HandoverRecordRepository handoverRecords;
    private final HandoverService handoverService;
    private final RentalOrderQuery orderQuery;
    private final RentalOrderLifecycle orderLifecycle;
    private final RentalOrderCancellationService orderCancellation;
    private final NotificationService notificationService;
    private final AccountQueryService accountQueryService;
    private final String frontendUrl;

    HandoverOverdueService(HandoverRecordRepository handoverRecords, HandoverService handoverService,
                           RentalOrderQuery orderQuery, RentalOrderLifecycle orderLifecycle,
                           RentalOrderCancellationService orderCancellation,
                           NotificationService notificationService, AccountQueryService accountQueryService,
                           @Value("${app.frontend-url}") String frontendUrl) {
        this.handoverRecords = handoverRecords;
        this.handoverService = handoverService;
        this.orderQuery = orderQuery;
        this.orderLifecycle = orderLifecycle;
        this.orderCancellation = orderCancellation;
        this.notificationService = notificationService;
        this.accountQueryService = accountQueryService;
        this.frontendUrl = frontendUrl;
    }

    @Transactional(readOnly = true)
    public List<Long> findOverdueIds() {
        return handoverRecords.findOverdueIds(Instant.now());
    }

    /** Huỷ một record nếu đang quá hạn; trả {@code true} khi thực sự xử lý. */
    @Transactional
    public boolean cancelIfOverdue(Long recordId) {
        HandoverRecord record = handoverRecords.findByIdForUpdate(recordId).orElse(null);
        Instant now = Instant.now();
        if (record == null || record.getResult() != HandoverResult.IN_PROGRESS
                || record.getDueAt() == null || !record.getDueAt().isBefore(now)) {
            return false;
        }
        RentalOrderQuery.Snapshot order = orderQuery.find(record.getOrderId()).orElse(null);
        if (order == null) {
            LOG.warn("HandoverRecord {} trỏ đơn không tồn tại {}", recordId, record.getOrderId());
            return false;
        }
        if (order.done()) {
            LOG.warn("Đơn {} đã Done nhưng record {} còn IN_PROGRESS — bỏ qua", order.id(), recordId);
            return false;
        }
        handoverService.cancelByOrderId(order.id(), REASON);
        if (!order.inProgress()) {
            LOG.warn("Đơn {} không InProgress ({}) — chỉ đóng record/contract", order.id(), order.status());
            return true;
        }
        if (order.expiresAt() == null || now.isBefore(order.expiresAt())) {
            orderLifecycle.returnToDeposited(order.id(), REASON);
            notifyOverdue(order);
        } else {
            orderCancellation.cancel(order.id(), REASON);
        }
        return true;
    }

    private void notifyOverdue(RentalOrderQuery.Snapshot order) {
        notificationService.notify(order.customerId(), accountQueryService.requireEmailById(order.customerId()),
                NotificationRecipient.CUSTOMER, NotificationType.HANDOVER_OVERDUE,
                Map.of("rescheduleLink", frontendUrl + "/appointments/new"), order.id());
    }
}
