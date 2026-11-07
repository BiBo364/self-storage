package vn.lemar.selfstorage.booking.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.lemar.selfstorage.booking.application.exception.BookingNotFoundException;
import vn.lemar.selfstorage.booking.application.exception.BookingValidationException;
import vn.lemar.selfstorage.booking.repository.OrderOverviewReadRepository;
import vn.lemar.selfstorage.booking.repository.OrderOverviewReadRepository.OrderOverviewRow;
import vn.lemar.selfstorage.identity.application.Access;

/**
 * Tổng quan đơn thuê cho khách ("Kho của tôi") và FM (theo dõi đơn của cơ sở). {@code availableActions} do backend
 * tính từ trạng thái — FE chỉ render nút theo danh sách này, không tự suy luật.
 */
@Service
public class OrderOverviewQueryService {

    static final String PERMISSION_READ = "rental_order.read";
    private static final Set<String> ORDER_STATUSES =
            Set.of("Pending", "Deposited", "Scheduled", "InProgress", "Canceled", "Done");

    /** Hành động khách được làm trên đơn. */
    public static final String ACTION_RESPOND_PROPOSAL = "RESPOND_PROPOSAL";
    public static final String ACTION_PAY_INVOICE = "PAY_INVOICE";
    public static final String ACTION_BOOK_CHECKIN = "BOOK_CHECKIN";
    public static final String ACTION_HANDOVER = "HANDOVER";
    public static final String ACTION_CANCEL = "CANCEL";
    public static final String ACTION_VIEW_CONTRACT = "VIEW_CONTRACT";

    /** Hành động FM được làm trên đơn. */
    public static final String ACTION_REPROPOSE = "REPROPOSE";
    public static final String ACTION_ASSIGN_STAFF = "ASSIGN_STAFF";

    private final OrderOverviewReadRepository repository;
    private final Access access;

    OrderOverviewQueryService(OrderOverviewReadRepository repository, Access access) {
        this.repository = repository;
        this.access = access;
    }

    /** Đơn kèm danh sách hành động. */
    public record OrderOverview(OrderOverviewRow order, List<String> availableActions) {
    }

    @Transactional(readOnly = true)
    public List<OrderOverview> listForCustomer(Long customerId) {
        return repository.findByCustomer(customerId).stream()
                .map(row -> new OrderOverview(row, customerActions(row)))
                .toList();
    }

    @Transactional(readOnly = true)
    public OrderOverview detailForCustomer(Long orderId, Long customerId) {
        OrderOverviewRow row = repository.findById(orderId)
                .filter(o -> o.customerId().equals(customerId))
                .orElseThrow(() -> new BookingNotFoundException("Không tìm thấy đơn thuê"));
        return new OrderOverview(row, customerActions(row));
    }

    @Transactional(readOnly = true)
    public List<OrderOverview> listForFacility(Long facilityId, String status) {
        access.can(PERMISSION_READ, facilityId);
        String normalized = status == null || status.isBlank() ? null : status.strip();
        if (normalized != null && !ORDER_STATUSES.contains(normalized)) {
            throw new BookingValidationException("Trạng thái đơn không hợp lệ: " + status);
        }
        return repository.findByFacility(facilityId, normalized).stream()
                .map(row -> new OrderOverview(row, managerActions(row)))
                .toList();
    }

    static List<String> customerActions(OrderOverviewRow row) {
        List<String> actions = new ArrayList<>();
        String status = row.status();
        boolean closed = "Canceled".equals(status) || "Done".equals(status);
        if ("Pending".equals(row.proposalStatus()) && !closed) {
            actions.add(ACTION_RESPOND_PROPOSAL);
        }
        if ("Unpaid".equals(row.invoiceStatus()) && !closed) {
            actions.add(ACTION_PAY_INVOICE);
        }
        if ("Deposited".equals(status) && !"Unpaid".equals(row.invoiceStatus())) {
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

    static List<String> managerActions(OrderOverviewRow row) {
        List<String> actions = new ArrayList<>();
        String status = row.status();
        boolean closed = "Canceled".equals(status) || "Done".equals(status);
        if (!closed && !"Pending".equals(row.proposalStatus())
                && ("Pending".equals(status) || row.needsReproposal())) {
            actions.add(ACTION_REPROPOSE);
        }
        if ("Scheduled".equals(status) && row.staffId() == null && "Pending".equals(row.appointmentStatus())) {
            actions.add(ACTION_ASSIGN_STAFF);
        }
        if (!closed) {
            actions.add(ACTION_CANCEL);
        }
        return actions;
    }
}
