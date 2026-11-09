package vn.lemar.selfstorage.booking.application;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.lemar.selfstorage.booking.application.exception.BookingNotFoundException;
import vn.lemar.selfstorage.booking.repository.OrderOverviewReadRepository;
import vn.lemar.selfstorage.booking.repository.OrderOverviewReadRepository.OrderOverviewRow;

/**
 * Tổng quan đơn thuê cho khách ("Kho của tôi"). {@code availableActions} do backend
 * tính từ trạng thái — FE chỉ render nút theo danh sách này, không tự suy luật.
 */
@Service
public class OrderOverviewQueryService {

    /** Hành động khách được làm trên đơn. */
    public static final String ACTION_RESPOND_PROPOSAL = "RESPOND_PROPOSAL";
    public static final String ACTION_PAY_INVOICE = "PAY_INVOICE";
    public static final String ACTION_BOOK_CHECKIN = "BOOK_CHECKIN";
    public static final String ACTION_HANDOVER = "HANDOVER";
    public static final String ACTION_CANCEL = "CANCEL";
    public static final String ACTION_VIEW_CONTRACT = "VIEW_CONTRACT";

    /** Hành động FM được làm trên đơn (danh sách đơn của FM — {@link ProposalQueryService}). */
    public static final String ACTION_REPROPOSE = "REPROPOSE";

    private final OrderOverviewReadRepository repository;
    private final AppointmentBookingService bookingService;

    OrderOverviewQueryService(OrderOverviewReadRepository repository, AppointmentBookingService bookingService) {
        this.repository = repository;
        this.bookingService = bookingService;
    }

    private List<String> customerActionsChecked(OrderOverviewRow row) {
        boolean bookable = !"Deposited".equals(row.status()) || bookingService.bookable(row.id());
        return customerActions(row, bookable);
    }

    /** Đơn kèm danh sách hành động. */
    public record OrderOverview(OrderOverviewRow order, List<String> availableActions) {
    }

    @Transactional(readOnly = true)
    public List<OrderOverview> listForCustomer(Long customerId) {
        return repository.findByCustomer(customerId).stream()
                .map(row -> new OrderOverview(row, customerActionsChecked(row)))
                .toList();
    }

    @Transactional(readOnly = true)
    public OrderOverview detailForCustomer(Long orderId, Long customerId) {
        OrderOverviewRow row = repository.findById(orderId)
                .filter(o -> o.customerId().equals(customerId))
                .orElseThrow(() -> new BookingNotFoundException("Không tìm thấy đơn thuê"));
        return new OrderOverview(row, customerActionsChecked(row));
    }


    static List<String> customerActions(OrderOverviewRow row) {
        return customerActions(row, true);
    }

    /** @param bookable đơn {@code Deposited} còn đặt lịch được (không chờ đề xuất lại, còn trong cửa sổ đặt lịch) */
    static List<String> customerActions(OrderOverviewRow row, boolean bookable) {
        List<String> actions = new ArrayList<>();
        String status = row.status();
        boolean closed = "Canceled".equals(status) || "Done".equals(status);
        if ("Pending".equals(row.proposalStatus()) && !closed) {
            actions.add(ACTION_RESPOND_PROPOSAL);
        }
        if ("Unpaid".equals(row.invoiceStatus()) && !closed) {
            actions.add(ACTION_PAY_INVOICE);
        }
        if ("Deposited".equals(status) && !"Unpaid".equals(row.invoiceStatus()) && bookable) {
            actions.add(ACTION_BOOK_CHECKIN);
        }
        if ("InProgress".equals(status) && "IN_PROGRESS".equals(row.handoverResult())
                && "Done".equals(row.appointmentStatus())) {
            actions.add(ACTION_HANDOVER);
        }
        if (row.contractId() != null
                && ("Active".equals(row.contractStatus()) || "Ended".equals(row.contractStatus()))) {
            actions.add(ACTION_VIEW_CONTRACT);
        }
        if (!closed) {
            actions.add(ACTION_CANCEL);
        }
        return actions;
    }

}
