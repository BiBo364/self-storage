package vn.lemar.selfstorage.booking.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.lemar.selfstorage.audit.application.AuditEntry;
import vn.lemar.selfstorage.audit.application.AuditRecorder;
import vn.lemar.selfstorage.audit.domain.AuditAction;
import vn.lemar.selfstorage.audit.domain.AuditEntityType;
import vn.lemar.selfstorage.booking.application.exception.ProposalConflictException;
import vn.lemar.selfstorage.booking.application.exception.ProposalForbiddenException;
import vn.lemar.selfstorage.booking.application.exception.ProposalNotFoundException;
import vn.lemar.selfstorage.booking.application.exception.ProposalValidationException;
import vn.lemar.selfstorage.booking.application.exception.RentalOrderNotFoundException;
import vn.lemar.selfstorage.booking.application.exception.StorageUnitNotFoundException;
import vn.lemar.selfstorage.booking.domain.Invoice;
import vn.lemar.selfstorage.booking.domain.InvoiceStatus;
import vn.lemar.selfstorage.booking.domain.InvoiceType;
import vn.lemar.selfstorage.booking.domain.ProposalFeedback;
import vn.lemar.selfstorage.booking.domain.ProposalStatus;
import vn.lemar.selfstorage.booking.domain.RentalOrder;
import vn.lemar.selfstorage.booking.repository.InvoiceRepository;
import vn.lemar.selfstorage.booking.repository.ProposalFeedbackRepository;
import vn.lemar.selfstorage.booking.repository.RentalOrderRepository;
import vn.lemar.selfstorage.facility.application.StorageUnitQuery;
import vn.lemar.selfstorage.facility.application.StorageUnitService;
import vn.lemar.selfstorage.facility.application.StorageUnitView;
import vn.lemar.selfstorage.identity.application.Access;
import vn.lemar.selfstorage.notification.application.NotificationType;
import vn.lemar.selfstorage.pricing.application.PricingQuery;

/**
 * Flow 1.3 — khách đồng ý / từ chối đề xuất khoang, FM đề xuất lại ({@code specs/draft.md} › 1.3), theo
 * các điểm chốt ở issue #113 (lệch spec có chủ đích, spec sẽ cập nhật sau review):
 * <ul>
 *   <li>Proposal dùng {@code Canceled} thay {@code Expired}.</li>
 *   <li>Tiền cọc = giá thuê hiệu lực × {@code invoice.deposit_months}.</li>
 *   <li>Huỷ đơn tại lần từ chối thứ 3: {@code số Rejected + 1 >= proposal.max_rejection_count}.</li>
 * </ul>
 */
@Service
public class ProposalService {

    static final String PERMISSION_REPROPOSE = "proposal_feedback.repropose";
    static final String PERMISSION_OVERRIDE = "proposal_feedback.override";
    static final String POLICY_MAX_REJECTION = "proposal.max_rejection_count";
    static final String POLICY_UNIT_CHANGE = "fee.unit_change";
    static final String CANCEL_REASON_TOO_MANY_REJECTIONS = "Từ chối quá số khoang cho phép";

    private final ProposalFeedbackRepository proposalRepository;
    private final RentalOrderRepository orderRepository;
    private final StorageUnitQuery storageUnitQuery;
    private final StorageUnitService storageUnitService;
    private final PricingQuery pricingQuery;
    private final Access access;
    private final AuditRecorder auditRecorder;
    private final OrderNotifier orderNotifier;
    private final DepositInvoiceIssuer depositInvoiceIssuer;
    private final RentalOrderCancellation orderCancellation;
    private final InvoiceRepository invoiceRepository;
    private final String frontendUrl;

    ProposalService(ProposalFeedbackRepository proposalRepository, RentalOrderRepository orderRepository,
                    StorageUnitQuery storageUnitQuery, StorageUnitService storageUnitService,
                    PricingQuery pricingQuery, Access access,
                    AuditRecorder auditRecorder, OrderNotifier orderNotifier,
                    DepositInvoiceIssuer depositInvoiceIssuer, RentalOrderCancellation orderCancellation,
                    InvoiceRepository invoiceRepository, @Value("${app.frontend-url}") String frontendUrl) {
        this.proposalRepository = proposalRepository;
        this.orderRepository = orderRepository;
        this.storageUnitQuery = storageUnitQuery;
        this.storageUnitService = storageUnitService;
        this.pricingQuery = pricingQuery;
        this.access = access;
        this.auditRecorder = auditRecorder;
        this.orderNotifier = orderNotifier;
        this.depositInvoiceIssuer = depositInvoiceIssuer;
        this.orderCancellation = orderCancellation;
        this.invoiceRepository = invoiceRepository;
        this.frontendUrl = frontendUrl.replaceAll("/+$", "");
    }

    /**
     * Khách đồng ý proposal: {@code Agreed}, gán khoang vào đơn, tạo hoá đơn cọc. Khoang hết
     * {@code Available} → proposal {@code Canceled} + báo FM {@code PROPOSAL_REPROPOSAL_REQUIRED}, trả
     * {@link AgreeResult.Outcome#UNIT_UNAVAILABLE}.
     */
    @Transactional
    public AgreeResult agree(Long proposalId, Long customerAccountId) {
        ProposalFeedback proposal = lockPendingProposalOf(proposalId, customerAccountId);
        // Khoá đơn vì agree đọc/ghi tiền (hoá đơn cọc, chênh lệch cọc) và trạng thái đơn.
        RentalOrder order = orderRepository.findByIdForUpdate(proposal.getOrderId())
                .orElseThrow(RentalOrderNotFoundException::new);
        requireAgreeable(order);
        if (invoiceRepository.existsByOrderIdAndStatusInAndTypeIn(order.getId(),
                List.of(InvoiceStatus.UNPAID),
                List.of(InvoiceType.DEPOSIT, InvoiceType.DEPOSIT_SUPPLEMENT))) {
            throw new ProposalConflictException("Đơn đang có hoá đơn cọc chờ thanh toán");
        }
        Long oldUnitId = order.holdsUnit() ? order.getUnitId() : null;
        StorageUnitView unit = lockAgreedUnits(oldUnitId, proposal.getUnitId());

        if (!unit.isAvailable()) {
            proposal.cancel();
            auditRecorder.record(new AuditEntry(AuditAction.PROPOSAL_CANCELED, AuditEntityType.PROPOSAL_FEEDBACK,
                    String.valueOf(proposal.getId()), Map.of("status", ProposalStatus.PENDING.dbValue()),
                    Map.of("status", proposal.getStatus().dbValue(), "unitStatus", unit.status()),
                    "Khoang " + unit.code() + " không còn khả dụng", customerAccountId));
            if (order.holdsUnit()) {
                order.requireReproposal();
            }
            orderNotifier.notifyFacilityManager(order.getId(), NotificationType.PROPOSAL_REPROPOSAL_REQUIRED,
                    Map.of("orderCode", OrderNotifier.orderCode(order), "unitCode", unit.code()));
            return new AgreeResult(AgreeResult.Outcome.UNIT_UNAVAILABLE, ProposalView.of(proposal), null);
        }

        proposal.agree();
        order.assignUnit(proposal.getUnitId());
        releaseReplacedUnit(order, oldUnitId, proposal.getUnitId(), customerAccountId);
        auditRecorder.record(new AuditEntry(AuditAction.PROPOSAL_AGREED, AuditEntityType.PROPOSAL_FEEDBACK,
                String.valueOf(proposal.getId()), Map.of("status", ProposalStatus.PENDING.dbValue()),
                Map.of("status", proposal.getStatus().dbValue(), "unitId", proposal.getUnitId()), null,
                customerAccountId));
        Invoice invoice = issueDeposit(order, unit, customerAccountId);
        return new AgreeResult(AgreeResult.Outcome.AGREED, ProposalView.of(proposal),
                invoice == null ? null : InvoiceView.of(invoice));
    }

    /**
     * Khoá cả khoang cũ và khoang mới theo thứ tự id tăng dần để tránh deadlock khi hai đơn đổi khoang
     * chéo nhau. Khoang mới trả về cho caller kiểm tra {@code Available}.
     */
    private StorageUnitView lockAgreedUnits(Long oldUnitId, Long newUnitId) {
        if (oldUnitId != null && !oldUnitId.equals(newUnitId) && oldUnitId < newUnitId) {
            storageUnitQuery.lock(oldUnitId);
        }
        StorageUnitView unit = storageUnitQuery.lock(newUnitId)
                .orElseThrow(StorageUnitNotFoundException::new);
        if (oldUnitId != null && !oldUnitId.equals(newUnitId) && oldUnitId > newUnitId) {
            storageUnitQuery.lock(oldUnitId);
        }
        return unit;
    }

    /** Đổi khoang sau khi đã cọc: nhả khoang cũ; khoang mới chưa reserve cho tới khi thanh toán (B4). */
    private void releaseReplacedUnit(RentalOrder order, Long oldUnitId, Long newUnitId, Long actorAccountId) {
        if (oldUnitId == null || oldUnitId.equals(newUnitId)) {
            return;
        }
        if (storageUnitService.releaseIfReserved(oldUnitId)) {
            auditRecorder.record(new AuditEntry(AuditAction.UNIT_CHANGE_RELEASED, AuditEntityType.STORAGE_UNIT,
                    String.valueOf(oldUnitId), Map.of("status", "Reserved", "orderId", order.getId()),
                    Map.of("status", "Available", "unitId", newUnitId), "Đổi khoang sau khi đã cọc",
                    actorAccountId));
        }
    }

    /**
     * Đơn chưa cọc: hoá đơn cọc đầy đủ. Đơn đã cọc (đổi khoang sau khi cọc, behavior-gap #165): so chênh
     * lệch với tiền cọc đã thu — thiếu thì hoá đơn bổ sung, dư thì báo FM hoàn thủ công, bằng thì không tạo gì.
     */
    private Invoice issueDeposit(RentalOrder order, StorageUnitView unit, Long actorAccountId) {
        if (!order.holdsUnit()) {
            return depositInvoiceIssuer.issue(order, unit, actorAccountId);
        }
        BigDecimal paidDeposit = invoiceRepository.sumAmount(order.getId(),
                List.of(InvoiceType.DEPOSIT, InvoiceType.DEPOSIT_SUPPLEMENT), InvoiceStatus.PAID);
        BigDecimal required = depositInvoiceIssuer.depositAmount(unit)
                .add(pricingQuery.decimalPolicy(POLICY_UNIT_CHANGE));
        BigDecimal delta = required.subtract(paidDeposit).setScale(2, RoundingMode.HALF_UP);
        if (delta.signum() <= 0) {
            // Cọc đã thu đủ cho khoang mới → không có hoá đơn bổ sung để chốt; giữ khoang mới ngay (đơn đã cọc
            // phải luôn giữ khoang của mình — đặt lịch check-in và bàn giao đều cần khoang Reserved).
            reserveReplacementUnit(order, unit, actorAccountId);
        }
        if (delta.signum() > 0) {
            Invoice supplemental = depositInvoiceIssuer.issueSupplemental(order, unit, delta, actorAccountId);
            orderNotifier.notifyCustomer(order, NotificationType.UNIT_CHANGE_PAYMENT_REQUIRED,
                    Map.of("orderCode", OrderNotifier.orderCode(order), "amount", delta.toPlainString()));
            return supplemental;
        }
        if (delta.signum() < 0) {
            // TODO(b4): hoàn tự động về thẻ đã liên kết; trước B4 chỉ báo FM hoàn thủ công.
            BigDecimal refund = delta.abs();
            auditRecorder.record(new AuditEntry(AuditAction.DEPOSIT_REFUND_REQUIRED, AuditEntityType.RENTAL_ORDER,
                    String.valueOf(order.getId()), Map.of("depositPaid", paidDeposit),
                    Map.of("refundAmount", refund), "Đổi khoang sau khi đã cọc", actorAccountId));
            orderNotifier.notifyFacilityManager(order.getId(), NotificationType.DEPOSIT_REFUND_REQUIRED,
                    Map.of("orderCode", OrderNotifier.orderCode(order), "refundAmount", refund.toPlainString()));
            orderNotifier.notifyCustomer(order, NotificationType.DEPOSIT_REFUND_PENDING,
                    Map.of("orderCode", OrderNotifier.orderCode(order), "refundAmount", refund.toPlainString()));
        }
        return null;
    }

    private void reserveReplacementUnit(RentalOrder order, StorageUnitView unit, Long actorAccountId) {
        if (storageUnitService.reserveIfAvailable(unit.id())) {
            auditRecorder.record(new AuditEntry(AuditAction.STORAGE_UNIT_RESERVED, AuditEntityType.STORAGE_UNIT,
                    String.valueOf(unit.id()), Map.of("status", "Available"),
                    Map.of("status", "Reserved", "orderId", order.getId()), "Đổi khoang sau khi đã cọc",
                    actorAccountId));
        }
    }

    /** Guard trạng thái đơn khi khách đồng ý (behavior-gap #165). */
    private void requireAgreeable(RentalOrder order) {
        if (order.getStatus().isClosed()) {
            throw new ProposalConflictException("Đơn thuê đã đóng, không thể đồng ý khoang");
        }
        if (order.getExpiresAt() != null && order.getExpiresAt().isBefore(Instant.now())) {
            throw new ProposalConflictException("Đơn thuê đã hết hạn, không thể đồng ý khoang");
        }
    }

    /**
     * Khách từ chối proposal kèm lý do. Chưa chạm ngưỡng → báo FM {@code PROPOSAL_REJECTED}; chạm ngưỡng (lần
     * thứ {@code proposal.max_rejection_count}) → Cancellation Cascade, lý do "Từ chối quá số khoang cho phép".
     */
    @Transactional
    public RejectResult reject(Long proposalId, Long customerAccountId, String note) {
        if (note == null || note.isBlank()) {
            throw new ProposalValidationException("Vui lòng nhập lý do từ chối");
        }
        ProposalFeedback proposal = lockPendingProposalOf(proposalId, customerAccountId);
        // Khoá đơn để việc đếm số lần từ chối và cập nhật nằm gọn trong một transaction.
        RentalOrder order = orderRepository.findByIdForUpdate(proposal.getOrderId())
                .orElseThrow(RentalOrderNotFoundException::new);
        if (order.getStatus().isClosed()) {
            throw new ProposalConflictException("Đơn thuê đã đóng, không thể từ chối đề xuất");
        }
        String unitCode = storageUnitQuery.find(proposal.getUnitId())
                .map(StorageUnitView::code)
                .orElseThrow(StorageUnitNotFoundException::new);
        long previousRejections = proposalRepository.countByOrderIdAndStatus(order.getId(), ProposalStatus.REJECTED);
        boolean reachesLimit = previousRejections + 1 >= pricingQuery.intPolicy(POLICY_MAX_REJECTION);

        proposal.reject(note.strip());
        auditRecorder.record(new AuditEntry(AuditAction.PROPOSAL_REJECTED, AuditEntityType.PROPOSAL_FEEDBACK,
                String.valueOf(proposal.getId()), Map.of("status", ProposalStatus.PENDING.dbValue()),
                Map.of("status", proposal.getStatus().dbValue()), proposal.getNote(), customerAccountId));

        if (reachesLimit) {
            orderCancellation.cancel(order, CANCEL_REASON_TOO_MANY_REJECTIONS, customerAccountId);
            return new RejectResult(ProposalView.of(proposal), true);
        }
        if (order.holdsUnit()) {
            order.requireReproposal();
        }
        orderNotifier.notifyFacilityManager(order.getId(), NotificationType.PROPOSAL_REJECTED,
                Map.of("unitCode", unitCode, "reason", proposal.getNote()));
        return new RejectResult(ProposalView.of(proposal), false);
    }

    /**
     * FM đề xuất khoang khác cho đơn. Khoang khách đã từ chối trong cùng đơn chỉ được chọn lại khi
     * FM có quyền override và nhập lý do — lý do ghi vào AuditLog.
     *
     * @param overrideReason bắt buộc khi {@code unitId} đã bị khách từ chối; bỏ qua trong trường hợp khác
     */
    @Transactional
    public ProposalView repropose(Long orderId, Long unitId, String overrideReason, Long actorAccountId) {
        Long facilityId = orderRepository.findFacilityId(orderId).orElseThrow(RentalOrderNotFoundException::new);
        access.can(PERMISSION_REPROPOSE, facilityId);

        RentalOrder order = orderRepository.findByIdForUpdate(orderId).orElseThrow(RentalOrderNotFoundException::new);
        // Chốt PR #136: !đóng && !có proposal Pending && (Pending || needs_reproposal).
        if (order.getStatus().isClosed()) {
            throw new ProposalConflictException("Đơn thuê đã đóng, không thể đề xuất khoang");
        }
        if (order.getExpiresAt() != null && order.getExpiresAt().isBefore(Instant.now())) {
            throw new ProposalConflictException("Đơn thuê đã hết hạn, không thể đề xuất khoang");
        }
        if (proposalRepository.existsByOrderIdAndStatus(orderId, ProposalStatus.PENDING)) {
            throw new ProposalConflictException("Đơn đang có đề xuất chờ khách phản hồi");
        }
        if (!order.allowsReproposal()) {
            throw new ProposalConflictException("Đơn không ở trạng thái cho phép đề xuất lại khoang");
        }

        StorageUnitView unit = storageUnitQuery.lock(unitId)
                .filter(found -> found.facilityId().equals(facilityId))
                .orElseThrow(StorageUnitNotFoundException::new);
        if (!unit.isAvailable()) {
            throw new ProposalConflictException("Khoang " + unit.code() + " không còn trống");
        }

        String reason = null;
        List<Long> rejectedUnitIds = proposalRepository.findUnitIdsByOrderIdAndStatus(orderId,
                ProposalStatus.REJECTED);
        if (rejectedUnitIds.contains(unitId)) {
            if (overrideReason == null || overrideReason.isBlank()) {
                throw new ProposalValidationException(
                        "Khách đã từ chối khoang " + unit.code() + ", cần nhập lý do để đề xuất lại");
            }
            access.can(PERMISSION_OVERRIDE, facilityId);
            reason = overrideReason.strip();
        }

        cancelUnpaidDepositInvoices(order, actorAccountId);
        ProposalFeedback proposal = proposalRepository.save(ProposalFeedback.propose(order, unitId));
        // Chốt: cờ chỉ dùng cho gate đề xuất lại, clear ngay khi proposal mới được tạo.
        order.clearNeedsReproposal();
        auditRecorder.record(new AuditEntry(AuditAction.PROPOSAL_REPROPOSED, AuditEntityType.PROPOSAL_FEEDBACK,
                String.valueOf(proposal.getId()), null,
                Map.of("status", proposal.getStatus().dbValue(), "unitId", unitId, "orderId", orderId), reason,
                actorAccountId));
        orderNotifier.notifyCustomer(order, NotificationType.PROPOSAL_REPROPOSED,
                Map.of("confirmLink", frontendUrl + "/proposals"));
        return ProposalView.of(proposal);
    }

    /**
     * Chốt PR #136: khách đã đồng ý nhưng chưa trả cọc mà FM đổi khoang → hoá đơn Deposit {@code Unpaid} của đơn
     * chuyển {@code Canceled}, tránh khách thanh toán nhầm cho khoang cũ. Proposal cũ giữ nguyên làm lịch sử.
     */
    private void cancelUnpaidDepositInvoices(RentalOrder order, Long actorAccountId) {
        for (Invoice invoice : invoiceRepository.findByOrderIdAndStatus(order.getId(), InvoiceStatus.UNPAID)) {
            if (invoice.getType() != InvoiceType.DEPOSIT
                    && invoice.getType() != InvoiceType.DEPOSIT_SUPPLEMENT) {
                continue;
            }
            invoice.cancel();
            auditRecorder.record(new AuditEntry(AuditAction.INVOICE_CANCELED, AuditEntityType.INVOICE,
                    String.valueOf(invoice.getId()), Map.of("status", InvoiceStatus.UNPAID.dbValue()),
                    Map.of("status", invoice.getStatus().dbValue()), "FM đề xuất lại khoang khác", actorAccountId));
        }
    }

    private ProposalFeedback lockPendingProposalOf(Long proposalId, Long customerAccountId) {
        ProposalFeedback proposal = proposalRepository.findByIdForUpdate(proposalId)
                .orElseThrow(ProposalNotFoundException::new);
        if (!proposal.getCustomerId().equals(customerAccountId)) {
            throw new ProposalForbiddenException();
        }
        if (!proposal.isPending()) {
            throw new ProposalConflictException("Đề xuất này đã được xử lý");
        }
        return proposal;
    }
}
