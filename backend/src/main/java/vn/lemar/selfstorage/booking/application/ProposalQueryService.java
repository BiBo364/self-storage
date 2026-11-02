package vn.lemar.selfstorage.booking.application;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.lemar.selfstorage.booking.application.dto.CustomerProposalResponse;
import vn.lemar.selfstorage.booking.application.dto.FmRentalOrderResponse;
import vn.lemar.selfstorage.booking.application.exception.ProposalValidationException;
import vn.lemar.selfstorage.booking.application.exception.RentalOrderNotFoundException;
import vn.lemar.selfstorage.booking.domain.ProposalStatus;
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
                order.unitId(), order.facilityId(), order.requestId(), order.requestStatus(),
                order.needsReproposal(), order.customer(), order.requested(), rejections, maxRejection(),
                proposals,
                readRepository.findAvailableUnits(orderId, order.facilityId(), order.requested().unitTypeId()));
    }

    private int maxRejection() {
        return pricingQuery.intPolicy(ProposalService.POLICY_MAX_REJECTION);
    }
}
