package vn.lemar.selfstorage.booking.application;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.lemar.selfstorage.audit.application.AuditEntry;
import vn.lemar.selfstorage.audit.application.AuditRecorder;
import vn.lemar.selfstorage.audit.domain.AuditAction;
import vn.lemar.selfstorage.audit.domain.AuditEntityType;
import vn.lemar.selfstorage.booking.domain.ProposalFeedback;
import vn.lemar.selfstorage.booking.domain.RentalOrder;
import vn.lemar.selfstorage.booking.domain.RentalRequest;
import vn.lemar.selfstorage.booking.domain.RentalRequestStatus;
import vn.lemar.selfstorage.booking.repository.ConversionGuardRepository;
import vn.lemar.selfstorage.booking.repository.ProposalFeedbackRepository;
import vn.lemar.selfstorage.booking.repository.RentalOrderRepository;
import vn.lemar.selfstorage.booking.repository.RentalRequestRepository;
import vn.lemar.selfstorage.facility.application.StorageUnitQuery;
import vn.lemar.selfstorage.facility.application.StorageUnitView;
import vn.lemar.selfstorage.identity.application.AccountQueryService;
import vn.lemar.selfstorage.identity.application.VerifiedAccount;
import vn.lemar.selfstorage.notification.application.NotificationRecipient;
import vn.lemar.selfstorage.notification.application.NotificationService;
import vn.lemar.selfstorage.notification.application.NotificationType;
import vn.lemar.selfstorage.pricing.application.PricingQuery;

/**
 * B2 — convert {@code RentalRequest} đã duyệt sang {@code RentalOrder(Pending)} +
 * {@code ProposalFeedback(Pending)} cho customer đã xác minh email
 * ({@code draft.md} › 1.1 "email đã có tài khoản", 1.2 Case A).
 *
 * <p>Hai điểm kích hoạt, đều nội bộ (không có endpoint):
 * <ul>
 *   <li>{@link #convertOnApproval} — B1 gọi trong chính transaction FM duyệt.</li>
 *   <li>{@link #claimForVerifiedAccount} — {@link EmailVerifiedClaimListener} gọi sau khi giao dịch
 *       xác minh email (A3a, event {@code EmailVerified}) đã commit.</li>
 * </ul>
 * Cả hai khoá advisory theo email khách trước khi kiểm trùng, nên không thể tạo hai đơn/proposal
 * đang hoạt động cho cùng (account, khoang) kể cả khi chạy đồng thời. Email đi qua
 * {@link NotificationService} — gửi sau commit, lỗi gửi không rollback conversion.
 */
@Service
public class RentalRequestConversionService {

    /** Spec {@code draft.md} › Các tham số sử dụng trong Flow 1. */
    static final String ORDER_EXPIRY_DAYS = "order.expiry_days";

    private static final Logger LOG = LoggerFactory.getLogger(RentalRequestConversionService.class);

    private final RentalRequestRepository requestRepository;
    private final RentalOrderRepository orderRepository;
    private final ProposalFeedbackRepository proposalRepository;
    private final ConversionGuardRepository conversionGuard;
    private final AccountQueryService accountQueryService;
    private final StorageUnitQuery storageUnitQuery;
    private final PricingQuery pricingQuery;
    private final NotificationService notificationService;
    private final AuditRecorder auditRecorder;
    private final String frontendUrl;
    private final vn.lemar.selfstorage.pricing.application.UnitTypeQueryService unitTypes;

    public RentalRequestConversionService(RentalRequestRepository requestRepository,
                                          RentalOrderRepository orderRepository,
                                          ProposalFeedbackRepository proposalRepository,
                                          ConversionGuardRepository conversionGuard,
                                          AccountQueryService accountQueryService,
                                          StorageUnitQuery storageUnitQuery, PricingQuery pricingQuery,
                                          NotificationService notificationService, AuditRecorder auditRecorder,
                                          @Value("${app.frontend-url}") String frontendUrl,
                                          vn.lemar.selfstorage.pricing.application.UnitTypeQueryService unitTypes) {
        this.unitTypes = unitTypes;
        this.requestRepository = requestRepository;
        this.orderRepository = orderRepository;
        this.proposalRepository = proposalRepository;
        this.conversionGuard = conversionGuard;
        this.accountQueryService = accountQueryService;
        this.storageUnitQuery = storageUnitQuery;
        this.pricingQuery = pricingQuery;
        this.notificationService = notificationService;
        this.auditRecorder = auditRecorder;
        this.frontendUrl = frontendUrl.replaceAll("/+$", "");
    }

    /**
     * Trigger 1 — FM vừa duyệt request. Email đã thuộc account xác minh thì convert ngay; chưa có
     * thì trả {@link ConversionResult.Outcome#NO_VERIFIED_ACCOUNT} và request giữ {@code Approved}
     * (caller gửi email "chưa có tài khoản" của Flow 1.1).
     *
     * <p>Audit: {@code RENTAL_REQUEST_CONVERTED} (System) + {@code RENTAL_ORDER_CREATED} +
     * {@code PROPOSAL_CREATED}; bản ghi {@code RENTAL_REQUEST_APPROVED} do B1 ghi.
     *
     * @throws IllegalArgumentException request không tồn tại
     */
    @Transactional
    public ConversionResult convertOnApproval(Long requestId) {
        // Đọc email bằng query scalar, chưa load entity: entity chỉ load sau khi đã khoá, nên không
        // dùng trạng thái cũ. Khoá email TRƯỚC khi tra account và giữ tới commit kể cả khi chưa có
        // account xác minh: claim của một lần xác minh đang chạy song song phải chờ duyệt commit rồi
        // mới select, nên không bỏ sót request vừa duyệt (review #149).
        String email = requestRepository.findEmailById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy RentalRequest " + requestId));
        conversionGuard.lockCustomerEmail(email);
        Optional<VerifiedAccount> account = accountQueryService.findVerifiedByEmail(email);
        if (account.isEmpty()) {
            return ConversionResult.skipped(requestId, ConversionResult.Outcome.NO_VERIFIED_ACCOUNT);
        }
        RentalRequest request = requestRepository.findById(requestId).orElseThrow();
        return convert(request.getId(), request.getUnitId(), account.get(), Instant.now(), false);
    }

    /**
     * Trigger 2 — customer vừa xác minh email: claim mọi request {@code Approved} còn hạn khớp
     * email (spec 1.2 Case A). Account chưa xác minh → không claim gì.
     *
     * <p>Luôn chạy transaction riêng ({@code REQUIRES_NEW}): caller chính là
     * {@link EmailVerifiedClaimListener} ở pha after-commit, lúc transaction xác minh đã đóng.
     *
     * @return kết quả theo từng request còn hạn (rỗng nếu không có — Case B)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<ConversionResult> claimForVerifiedAccount(Long accountId) {
        Optional<VerifiedAccount> account = accountQueryService.findVerifiedById(accountId);
        if (account.isEmpty()) {
            return List.of();
        }
        // Khoá email trước select: duyệt đang mở cho cùng email phải commit xong thì select mới chạy.
        conversionGuard.lockCustomerEmail(account.get().email());
        Instant now = Instant.now();
        // Lấy id + unit trước: conditional update clear persistence context sau mỗi vòng.
        List<Claimable> claimable = requestRepository.findClaimableForUpdate(account.get().email(), now).stream()
                .map(r -> new Claimable(r.getId(), r.getUnitId()))
                .toList();
        return claimable.stream()
                .map(r -> convert(r.requestId(), r.unitId(), account.get(), now, true))
                .toList();
    }

    private record Claimable(Long requestId, Long unitId) {
    }

    private ConversionResult convert(Long requestId, Long unitId, VerifiedAccount account, Instant now,
                                     boolean claimed) {
        if (unitId == null) {
            return ConversionResult.skipped(requestId, ConversionResult.Outcome.NOT_CONVERTIBLE);
        }
        // Kiểm trùng trước khi đổi trạng thái: request trùng phải giữ nguyên Approved.
        if (conversionGuard.hasActiveConversion(account.id(), unitId)) {
            LOG.info("Bỏ qua convert request {}: account {} đã có đơn/proposal đang hoạt động cho khoang {}",
                    requestId, account.id(), unitId);
            return ConversionResult.skipped(requestId, ConversionResult.Outcome.DUPLICATE_UNIT);
        }
        // Conditional update là nguồn sự thật (giống approveIfPending của B1): không còn Approved
        // hoặc đã quá hạn → 0 row, không tạo gì.
        if (requestRepository.convertIfApproved(requestId, now) == 0) {
            return ConversionResult.skipped(requestId, ConversionResult.Outcome.NOT_CONVERTIBLE);
        }
        StorageUnitView unit = storageUnitQuery.find(unitId)
                .orElseThrow(() -> new IllegalStateException("Không tìm thấy StorageUnit " + unitId));
        Instant expiresAt = now.plus(orderExpiryDays(), ChronoUnit.DAYS);

        // saveAndFlush: hasActiveConversion đọc bằng JDBC, phải thấy đơn vừa tạo ở vòng lặp trước.
        RentalOrder order = orderRepository.saveAndFlush(RentalOrder.open(requestId, account.id(), expiresAt));
        ProposalFeedback proposal = proposalRepository.saveAndFlush(ProposalFeedback.propose(order, unitId));

        if (claimed) {
            auditRecorder.record(new AuditEntry(AuditAction.RENTAL_REQUEST_CLAIMED, AuditEntityType.RENTAL_REQUEST,
                    String.valueOf(requestId),
                    Map.of("status", RentalRequestStatus.Approved.name()),
                    Map.of("status", RentalRequestStatus.Converted.name(), "orderId", order.getId(),
                            "proposalId", proposal.getId()),
                    null, account.id()));
        } else {
            auditRecorder.record(new AuditEntry(AuditAction.RENTAL_REQUEST_CONVERTED,
                    AuditEntityType.RENTAL_REQUEST, String.valueOf(requestId),
                    Map.of("status", RentalRequestStatus.Approved.name()),
                    Map.of("status", RentalRequestStatus.Converted.name(), "orderId", order.getId(),
                            "proposalId", proposal.getId()),
                    null, null));
        }
        auditRecorder.record(new AuditEntry(AuditAction.RENTAL_ORDER_CREATED,
                AuditEntityType.RENTAL_ORDER, String.valueOf(order.getId()), null,
                Map.of("status", "Pending", "requestId", requestId, "expiresAt", expiresAt.toString()),
                null, null));
        auditRecorder.record(new AuditEntry(AuditAction.PROPOSAL_CREATED,
                AuditEntityType.PROPOSAL_FEEDBACK, String.valueOf(proposal.getId()), null,
                Map.of("status", "Pending", "orderId", order.getId(), "unitId", unitId),
                null, null));
        notificationService.notify(account.id(), account.email(), NotificationRecipient.CUSTOMER,
                NotificationType.RENTAL_REQUEST_APPROVED,
                Map.of("unitCode", unit.code(), "unitType", unitTypeLabel(unit.unitTypeId()),
                        "proposalLink", frontendUrl + "/proposals"), order.getId());
        return ConversionResult.converted(requestId, order.getId(), proposal.getId());
    }

    /**
     * {@code order.expiry_days} theo spec — không có fallback: thiếu key hoặc sai kiểu là lỗi cấu
     * hình (bug #130), convert rollback.
     */
    /** "Small (1 × 2 × 2,2 m)" cho nội dung thông báo duyệt (spec 1.1). */
    private String unitTypeLabel(Long unitTypeId) {
        return unitTypes.find(unitTypeId)
                .map(t -> t.name() + " (" + t.width().stripTrailingZeros().toPlainString() + " × "
                        + t.depth().stripTrailingZeros().toPlainString() + " × "
                        + t.height().stripTrailingZeros().toPlainString() + " m)")
                .orElse("");
    }

    private int orderExpiryDays() {
        String value = pricingQuery.valueOf(ORDER_EXPIRY_DAYS)
                .orElseThrow(() -> new IllegalStateException("Thiếu policy '" + ORDER_EXPIRY_DAYS + "'"));
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Policy '" + ORDER_EXPIRY_DAYS + "' không phải số nguyên: " + value, e);
        }
    }
}
