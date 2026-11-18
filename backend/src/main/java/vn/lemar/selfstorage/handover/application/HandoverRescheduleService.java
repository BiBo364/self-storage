package vn.lemar.selfstorage.handover.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.lemar.selfstorage.booking.application.AppointmentBookingService.SlotWindow;
import vn.lemar.selfstorage.booking.application.CheckinRescheduleService;
import vn.lemar.selfstorage.booking.application.CheckinRescheduleService.Rescheduled;
import vn.lemar.selfstorage.booking.application.CheckinRescheduleService.Target;
import vn.lemar.selfstorage.booking.application.HandoverOrderService;
import vn.lemar.selfstorage.booking.application.HandoverOrderService.ContractInvoice;
import vn.lemar.selfstorage.handover.application.exception.HandoverConflictException;
import vn.lemar.selfstorage.handover.application.exception.HandoverNotFoundException;
import vn.lemar.selfstorage.handover.application.exception.HandoverValidationException;
import vn.lemar.selfstorage.handover.domain.RentalContract;
import vn.lemar.selfstorage.handover.domain.RentalContractStatus;
import vn.lemar.selfstorage.handover.repository.HandoverRecordRepository;
import vn.lemar.selfstorage.handover.repository.RentalContractRepository;
import vn.lemar.selfstorage.identity.application.CurrentAccountProvider;
import vn.lemar.selfstorage.payment.application.PaymentService;

/**
 * FS dời lịch nhận kho trước bước thanh toán tháng đầu — điều phối giữa booking (lịch hẹn) và handover (biên bản, hợp
 * đồng). Một transaction, khoá theo thứ tự đơn → lịch → cơ sở → biên bản → hợp đồng:
 * <ol>
 *   <li>khoá đơn; hoá đơn tháng đầu đã trả → 409, đang có phiên cổng mở → 409 (không huỷ ngang giao dịch);</li>
 *   <li>booking dời lịch (kiểm khung, FS, huỷ lịch cũ nếu khách chưa đến, tạo lịch mới, phát
 *       {@code CheckinAppointmentCreated});</li>
 *   <li>đóng biên bản cũ ({@code CANCELED}, lý do "Dời lịch: …" — giữ làm lịch sử), hợp đồng {@code Draft}/
 *       {@code Signed} → {@code Canceled}, hoá đơn tháng đầu chưa trả → {@code Canceled}; flush trước commit để
 *       listener {@code BEFORE_COMMIT} mở được biên bản mới (partial unique index: một biên bản mở / đơn).</li>
 * </ol>
 * Phiên mới làm lại xác minh danh tính + kiểm tra khoang; hợp đồng nháp mới sinh khi khách đồng ý hiện trạng, mốc tính
 * tiền theo ngày mới.
 */
@Service
public class HandoverRescheduleService {

    static final String MSG_PAID = "Khách đã thanh toán tháng đầu — hoàn tất bàn giao, không dời lịch được nữa";
    static final String MSG_PAYING = "Khách đang thanh toán tháng đầu — đợi giao dịch kết thúc rồi dời lịch";

    private final CheckinRescheduleService rescheduler;
    private final HandoverOrderService orders;
    private final RentalContractRepository contracts;
    private final HandoverRecordRepository records;
    private final HandoverService handoverService;
    private final PaymentService paymentService;
    private final CurrentAccountProvider currentAccount;

    HandoverRescheduleService(CheckinRescheduleService rescheduler, HandoverOrderService orders,
                              RentalContractRepository contracts, HandoverRecordRepository records,
                              HandoverService handoverService, PaymentService paymentService,
                              CurrentAccountProvider currentAccount) {
        this.rescheduler = rescheduler;
        this.orders = orders;
        this.contracts = contracts;
        this.records = records;
        this.handoverService = handoverService;
        this.paymentService = paymentService;
        this.currentAccount = currentAccount;
    }

    /** Khung để dời (đúng FS phụ trách). */
    @Transactional(readOnly = true)
    public SlotWindow slots(Long appointmentId) {
        return rescheduler.slots(appointmentId);
    }

    @Transactional
    public Rescheduled reschedule(Long appointmentId, LocalDate date, Instant startAt, String reason) {
        String why = reason == null ? "" : reason.trim();
        if (why.isEmpty()) {
            throw new HandoverValidationException("Vui lòng nhập lý do dời lịch");
        }
        if (why.length() > 500) {
            throw new HandoverValidationException("Lý do tối đa 500 ký tự");
        }
        Target target = rescheduler.target(appointmentId);
        orders.lock(target.orderId()).orElseThrow(() -> new HandoverNotFoundException("Không tìm thấy đơn thuê"));
        // Khoá hợp đồng đang xử lý (đơn → … → hợp đồng): không chạy song song với bước ký / thanh toán.
        for (RentalContract contract : contracts.findByOrderIdAndStatusInForUpdate(target.orderId(),
                List.of(RentalContractStatus.DRAFT, RentalContractStatus.SIGNED))) {
            if (contract.getStatus() != RentalContractStatus.SIGNED) {
                continue;
            }
            ContractInvoice invoice = orders.firstMonthInvoice(contract.getId()).orElse(null);
            if (invoice != null && invoice.paid()) {
                throw new HandoverConflictException(MSG_PAID);
            }
            if (invoice != null && "Unpaid".equals(invoice.status())
                    && !paymentService.invoicesWithOpenSession(List.of(invoice.invoiceId())).isEmpty()) {
                throw new HandoverConflictException(MSG_PAYING);
            }
        }
        Rescheduled result = rescheduler.reschedule(appointmentId, date, startAt, why,
                currentAccount.getCurrentAccountId());
        handoverService.cancelByAppointmentId(appointmentId, "Dời lịch: " + why);
        records.flush();
        contracts.flush();
        return result;
    }
}
