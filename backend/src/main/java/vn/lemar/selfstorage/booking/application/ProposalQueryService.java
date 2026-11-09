package vn.lemar.selfstorage.booking.application;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.lemar.selfstorage.booking.application.dto.CustomerProposalResponse;
import vn.lemar.selfstorage.booking.application.dto.FmRentalOrderPageResponse;
import vn.lemar.selfstorage.booking.application.dto.FmRentalOrderResponse;
import vn.lemar.selfstorage.booking.application.dto.FmRentalOrderSummaryResponse;
import vn.lemar.selfstorage.booking.application.exception.ProposalValidationException;
import vn.lemar.selfstorage.booking.application.exception.RentalOrderNotFoundException;
import vn.lemar.selfstorage.booking.application.exception.RentalOrderValidationException;
import vn.lemar.selfstorage.booking.domain.ProposalStatus;
import vn.lemar.selfstorage.booking.domain.RentalOrderStatus;
import vn.lemar.selfstorage.booking.repository.BookingReadRepository;
import vn.lemar.selfstorage.identity.application.Access;
import vn.lemar.selfstorage.pricing.application.PricingQuery;

/** Đọc dữ liệu màn hình Flow 1.3: trang đề xuất của khách và màn đề xuất lại của FM. */
@Service
public class ProposalQueryService {

    static final String PERMISSION_ORDER_READ = "rental_order.read";

    private final BookingReadRepository readRepository;
    private final PricingQuery pricingQuery;
    private final Access access;

    ProposalQueryService(BookingReadRepository readRepository, PricingQuery pricingQuery, Access access) {
        this.readRepository = readRepository;
        this.pricingQuery = pricingQuery;
        this.access = access;
    }

    /**
     * Proposal của chính khách đang đăng nhập (ownership: lọc theo {@code customer_id}).
     *
     * @param status {@code Pending} / {@code Agreed} / {@code Rejected} / {@code Canceled}; null = tất cả
     */
    @Transactional(readOnly = true)
    public List<CustomerProposalResponse> listForCustomer(Long customerAccountId, String status) {
        String filter = null;
        if (status != null && !status.isBlank()) {
            try {
                filter = ProposalStatus.fromDbValue(status.strip()).dbValue();
            } catch (IllegalArgumentException ex) {
                throw new ProposalValidationException("Trạng thái không hợp lệ: " + status);
            }
        }
        return readRepository.findCustomerProposals(customerAccountId, filter, maxRejection());
    }

    /** Danh sách đơn thuê của cơ sở cho FM; quyền {@code rental_order.read} theo cơ sở. */
    @Transactional(readOnly = true)
    public FmRentalOrderPageResponse ordersForFacilityManager(Long facilityId, String status, int page,
                                                              int size) {
        access.can(PERMISSION_ORDER_READ, facilityId);
        if (page < 0 || size < 1 || size > 100) {
            throw new RentalOrderValidationException("page phải >= 0 và size trong khoảng 1..100");
        }
        String dbStatus = null;
        if (status != null && !status.isBlank()) {
            try {
                dbStatus = RentalOrderStatus.fromDbValue(status.strip()).dbValue();
            } catch (IllegalArgumentException ex) {
                throw new RentalOrderValidationException("Trạng thái đơn không hợp lệ: " + status);
            }
        }
        int maxRejection = maxRejection();
        List<BookingReadRepository.OrderListRow> rows = readRepository.findOrders(facilityId, dbStatus, size,
                (long) page * size);
        long total = readRepository.countOrders(facilityId, dbStatus);
        return new FmRentalOrderPageResponse(rows.stream()
                .map(row -> summary(row, maxRejection))
                .toList(), page, size, total);
    }

    /** Chi tiết đơn cho FM; quyền {@code rental_order.read} theo cơ sở của đơn. */
    @Transactional(readOnly = true)
    public FmRentalOrderResponse orderForFacilityManager(Long orderId) {
        BookingReadRepository.OrderRow order = readRepository.findOrder(orderId)
                .orElseThrow(RentalOrderNotFoundException::new);
        access.can(PERMISSION_ORDER_READ, order.facilityId());

        List<FmRentalOrderResponse.Proposal> proposals = readRepository.findOrderProposals(orderId);
        long rejections = proposals.stream()
                .filter(proposal -> ProposalStatus.REJECTED.dbValue().equals(proposal.status()))
                .count();
        return new FmRentalOrderResponse(order.id(), order.status(), order.cancelReason(), order.expiresAt(),
                order.unitId(), order.facilityId(), order.customer(), order.requested(), rejections,
                maxRejection(), proposals,
                readRepository.findAvailableUnits(orderId, order.facilityId(), order.requested().unitTypeId()));
    }

    private int maxRejection() {
        return pricingQuery.intPolicy(ProposalService.POLICY_MAX_REJECTION);
    }

    private static FmRentalOrderSummaryResponse summary(BookingReadRepository.OrderListRow row, int maxRejection) {
        FmRentalOrderSummaryResponse.LastProposal lastProposal = row.lastProposalId() == null
                ? null
                : new FmRentalOrderSummaryResponse.LastProposal(row.lastProposalId(),
                        row.lastProposalUnitCode(), row.lastProposalStatus(), row.lastProposalNote(),
                        row.lastProposalCreatedAt());
        return new FmRentalOrderSummaryResponse(row.id(), row.status(), row.cancelReason(), row.expiresAt(),
                row.unitId(), row.unitCode(), row.requestId(), row.customerName(), row.customerEmail(),
                row.customerPhone(), row.needsReproposal(), needsRepropose(row), row.rejectionCount(),
                maxRejection, lastProposal);
    }

    private static boolean needsRepropose(BookingReadRepository.OrderListRow row) {
        if ("Canceled".equals(row.status()) || "Done".equals(row.status())
                || row.pendingCount() > 0 || "Agreed".equals(row.lastProposalStatus())) {
            return false;
        }
        return ("Pending".equals(row.status()) || row.needsReproposal())
                && (row.lastProposalStatus() == null
                        || "Rejected".equals(row.lastProposalStatus())
                        || "Canceled".equals(row.lastProposalStatus()));
    }
}
