package vn.lemar.selfstorage.booking.application;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.lemar.selfstorage.audit.application.AuditEntry;
import vn.lemar.selfstorage.audit.application.AuditRecorder;
import vn.lemar.selfstorage.audit.domain.AuditAction;
import vn.lemar.selfstorage.audit.domain.AuditEntityType;
import vn.lemar.selfstorage.booking.application.dto.ApproveRentalRequestResponse;
import vn.lemar.selfstorage.booking.application.dto.CreateRentalRequestRequest;
import vn.lemar.selfstorage.booking.application.dto.RejectRentalRequestResponse;
import vn.lemar.selfstorage.booking.application.dto.RentalRequestPageResponse;
import vn.lemar.selfstorage.booking.application.dto.RentalRequestResponse;
import vn.lemar.selfstorage.booking.application.dto.RentalRequestSummaryResponse;
import vn.lemar.selfstorage.booking.application.exception.FacilityNotFoundException;
import vn.lemar.selfstorage.booking.application.exception.RentalRequestAlreadyHandledException;
import vn.lemar.selfstorage.booking.application.exception.RentalRequestDuplicateUnitException;
import vn.lemar.selfstorage.booking.application.exception.RentalRequestExpiredException;
import vn.lemar.selfstorage.booking.application.exception.RentalRequestNotFoundException;
import vn.lemar.selfstorage.booking.application.exception.RentalRequestValidationException;
import vn.lemar.selfstorage.booking.application.exception.StorageUnitNotFoundException;
import vn.lemar.selfstorage.booking.application.exception.StorageUnitUnavailableException;
import vn.lemar.selfstorage.booking.domain.RentalRequest;
import vn.lemar.selfstorage.booking.domain.RentalRequestRejectReason;
import vn.lemar.selfstorage.booking.domain.RentalRequestStatus;
import vn.lemar.selfstorage.booking.repository.ConversionGuardRepository;
import vn.lemar.selfstorage.booking.repository.RentalRequestRepository;
import vn.lemar.selfstorage.facility.application.FacilityAccess;
import vn.lemar.selfstorage.facility.application.StorageUnitQuery;
import vn.lemar.selfstorage.facility.application.StorageUnitView;
import vn.lemar.selfstorage.identity.application.Access;
import vn.lemar.selfstorage.identity.application.AccountQueryService;
import vn.lemar.selfstorage.identity.application.CurrentAccountProvider;
import vn.lemar.selfstorage.notification.EmailSender;
import vn.lemar.selfstorage.notification.application.NotificationEmailLayout;
import vn.lemar.selfstorage.notification.application.NotificationRecipient;
import vn.lemar.selfstorage.notification.application.NotificationService;
import vn.lemar.selfstorage.notification.application.NotificationTemplateCatalog;
import vn.lemar.selfstorage.notification.application.NotificationType;
import vn.lemar.selfstorage.pricing.application.PricingQuery;
import vn.lemar.selfstorage.pricing.application.UnitTypeQueryService;

/**
 * Tạo yêu cầu đặt kho từ form public (B1, spec draft.md §1.1).
 *
 * <p>Transition trạng thái (approve/reject) ghi AuditLog một bản ghi/entity trong cùng
 * transaction (A8 — catalog db-table-draft.md › AuditLog). Tạo mới từ form public không có
 * action trong catalog nên không ghi audit.
 */
@Service
public class RentalRequestService {

    // Phạm vi hiện tại chỉ cơ sở trong nước — hardcode giờ VN là chấp nhận được. Spec draft.md:122
    // ghi "theo timezone của Facility"; khi có kế hoạch chi nhánh ngoài VN thì bổ sung cột timezone
    // vào facilities (hoặc cập nhật spec) rồi đọc theo từng cơ sở.
    private static final ZoneId FACILITY_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final String PENDING_EXPIRY_KEY = "request.pending_expiry_days";
    private static final String CLAIM_EXPIRY_KEY = "account.claim_ttl_days";
    private static final long EXPIRY_FALLBACK_DAYS = 7L;
    private static final DateTimeFormatter VI_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final RentalRequestRepository repository;
    private final FacilityAccess facilityAccess;
    private final StorageUnitQuery storageUnitQuery;
    private final Access access;
    private final AccountQueryService accountQueryService;
    private final NotificationService notificationService;
    private final NotificationTemplateCatalog templateCatalog;
    private final EmailSender emailSender;
    private final UnitTypeQueryService unitTypeQueryService;
    private final PricingQuery pricingQuery;
    private final AuditRecorder auditRecorder;
    private final CurrentAccountProvider currentAccountProvider;
    private final RentalRequestConversionService conversionService;
    private final ConversionGuardRepository conversionGuard;

    /** Gốc URL FE cho link trong email; field injection để test dựng service bằng mock không phải truyền. */
    @org.springframework.beans.factory.annotation.Value("${app.frontend-url:}")
    private String frontendUrl = "";

    public RentalRequestService(RentalRequestRepository repository, FacilityAccess facilityAccess,
                                StorageUnitQuery storageUnitQuery, Access access,
                                AccountQueryService accountQueryService,
                                NotificationService notificationService,
                                NotificationTemplateCatalog templateCatalog, EmailSender emailSender,
                                UnitTypeQueryService unitTypeQueryService,
                                PricingQuery pricingQuery, AuditRecorder auditRecorder,
                                CurrentAccountProvider currentAccountProvider,
                                RentalRequestConversionService conversionService,
                                ConversionGuardRepository conversionGuard) {
        this.repository = repository;
        this.facilityAccess = facilityAccess;
        this.storageUnitQuery = storageUnitQuery;
        this.access = access;
        this.accountQueryService = accountQueryService;
        this.notificationService = notificationService;
        this.templateCatalog = templateCatalog;
        this.emailSender = emailSender;
        this.unitTypeQueryService = unitTypeQueryService;
        this.pricingQuery = pricingQuery;
        this.auditRecorder = auditRecorder;
        this.currentAccountProvider = currentAccountProvider;
        this.conversionService = conversionService;
        this.conversionGuard = conversionGuard;
    }

    @Transactional
    public RentalRequestResponse create(CreateRentalRequestRequest request) {
        if (!facilityAccess.exists(request.facilityId())) {
            throw new FacilityNotFoundException();
        }
        if (facilityAccess.isInactive(request.facilityId())) {
            throw new RentalRequestValidationException("Cơ sở đang ngừng hoạt động, không nhận yêu cầu thuê mới");
        }
        if (!unitTypeQueryService.exists(request.unitTypeId())) {
            throw new RentalRequestValidationException("Loại khoang không tồn tại");
        }
        if (!unitTypeQueryService.existsForFacility(request.facilityId(), request.unitTypeId())) {
            throw new RentalRequestValidationException(
                    "Loại khoang không được cung cấp tại cơ sở này");
        }
        LocalDate today = LocalDate.now(FACILITY_ZONE);
        if (request.startDate().isBefore(today)) {
            throw new RentalRequestValidationException("Ngày bắt đầu thuê không được ở quá khứ");
        }
        String normalizedEmail = request.customerEmail().strip().toLowerCase();
        String phoneDigits = request.customerPhone().replaceAll("\\D", "");
        if (phoneDigits.length() < 7 || phoneDigits.length() > 15) {
            throw new RentalRequestValidationException("Số điện thoại không đúng định dạng hỗ trợ");
        }
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(pendingExpiryDays() * 24L * 60L * 60L);
        RentalRequest saved = repository.save(new RentalRequest(request.facilityId(),
                request.customerName().strip(), normalizedEmail, request.customerPhone().strip(),
                request.unitTypeId(), request.startDate(), request.period(), expiresAt));
        return RentalRequestResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public RentalRequestPageResponse queue(Long facilityId, RentalRequestStatus status, int page,
            int size) {
        if (!facilityAccess.exists(facilityId)) {
            throw new FacilityNotFoundException();
        }
        PageRequest pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Page<RentalRequest> result = status == null
                ? repository.findByFacilityId(facilityId, pageable)
                : repository.findByFacilityIdAndStatus(facilityId, status, pageable);
        return new RentalRequestPageResponse(
                result.getContent().stream().map(RentalRequestSummaryResponse::from).toList(), page,
                size, result.getTotalElements());
    }

    /**
     * FM duyệt yêu cầu (B1, spec draft.md §1.1); email đã thuộc account xác minh thì convert ngay
     * sang đơn + proposal (B2, {@link RentalRequestConversionService#convertOnApproval}).
     *
     * <p>Guard {@code rental_request.approve} đặt trong service (thay vì controller) vì cần
     * {@code facilityId} từ entity mới guard được theo cơ sở — controller không có sẵn.
     * Thứ tự kiểm: 404 nếu request không tồn tại, 403 chéo cơ sở, 409 nếu request
     * không còn Pending, 404 nếu khoang không tồn tại hoặc thuộc cơ sở khác, 409 nếu khoang mất
     * Available, 409 nếu khoang đã gán cho request khác của cùng email (giữ Pending, không noti).
     *
     * <p>Cửa chặn race: conditional update {@code WHERE status = 'Pending'} (spec draft.md:135) —
     * 2 FM duyệt song song chỉ 1 tx thắng, tx sau nhận 0 row → 409. Response dựng từ giá trị
     * update, nguồn sự thật là DB.
     *
     * <p>Sau duyệt: email chưa có account nhận mail đăng ký theo template draft.md:156
     * (after-commit); email đã có account xác minh thì B2 convert và thông báo kèm proposal link.
     */
    @Transactional
    public ApproveRentalRequestResponse approve(Long requestId, Long unitId) {
        RentalRequest request = repository.findById(requestId)
                .orElseThrow(RentalRequestNotFoundException::new);
        access.can("rental_request.approve", request.getFacilityId());
        if (request.getStatus() != RentalRequestStatus.Pending) {
            throw new RentalRequestAlreadyHandledException();
        }
        if (request.getExpiresAt() != null && request.getExpiresAt().isBefore(Instant.now())) {
            throw new RentalRequestExpiredException();
        }
        if (accountQueryService.isBanned(request.getNormalizedCustomerEmail())) {
            return rejectBannedCustomer(requestId, request);
        }
        StorageUnitView unit = storageUnitQuery.find(unitId)
                .orElseThrow(StorageUnitNotFoundException::new);
        if (!unit.facilityId().equals(request.getFacilityId())) {
            throw new StorageUnitNotFoundException();
        }
        if (!unit.isAvailable()) {
            throw new StorageUnitUnavailableException();
        }
        // Khoá advisory theo email khách trước pre-check trùng, giữ tới commit: hai FM duyệt cùng
        // một khách phải tuần tự, để pre-check thấy đơn vừa tạo của lần duyệt trước — tránh ca race
        // duyệt trùng khoang. Pre-check theo đơn đang hoạt động (proposal Pending/Agreed trên đơn
        // chưa đóng), không theo status request: request cũ `Converted` không bao giờ đóng, nên
        // nếu check theo request thì khách không bao giờ được gán lại khoang sau khi đơn cũ đóng.
        conversionGuard.lockCustomerEmail(request.getNormalizedCustomerEmail());
        if (conversionGuard.hasActiveConversionForEmail(request.getNormalizedCustomerEmail(), unitId)) {
            throw new RentalRequestDuplicateUnitException();
        }
        Instant respondedAt = Instant.now();
        Instant expiresAt = respondedAt.plusSeconds(claimExpiryDays() * 24L * 60L * 60L);
        int updated = repository.approveIfPending(requestId, unitId, respondedAt, expiresAt,
                RentalRequestStatus.Pending, RentalRequestStatus.Approved);
        if (updated == 0) {
            throw new RentalRequestAlreadyHandledException();
        }
        auditRecorder.record(new AuditEntry(AuditAction.RENTAL_REQUEST_APPROVED,
                AuditEntityType.RENTAL_REQUEST, String.valueOf(requestId),
                Map.of("status", RentalRequestStatus.Pending.name()),
                Map.of("status", RentalRequestStatus.Approved.name(), "unitId", unitId),
                null, currentAccountProvider.getCurrentAccountId()));
        // B2: email đã thuộc account xác minh → tạo đơn + proposal ngay trong transaction duyệt.
        // Convert lỗi thì duyệt rollback theo.
        ConversionResult conversion = conversionService.convertOnApproval(requestId);
        if (conversion.outcome() == ConversionResult.Outcome.DUPLICATE_UNIT) {
            // Race hiếm: request khác của cùng khách vừa được convert cho khoang này (pre-check
            // chạy trước khi request kia commit). Trả 409 như pre-check và rollback cả transaction
            // duyệt — request giữ `Pending`, không có ca "duyệt xong nhưng không có đơn".
            throw new RentalRequestDuplicateUnitException();
        }
        sendApprovalEmail(request, expiresAt);
        return new ApproveRentalRequestResponse(request.getId(), RentalRequestStatus.Approved.name(),
                unitId, respondedAt, expiresAt);
    }

    /**
     * Account khách đã bị chặn: không duyệt, chuyển request sang {@code Rejected} và gửi mail.
     * Dùng reason {@code OTHER} + note cố định để không phải mở rộng CHECK của {@code reject_reason}.
     */
    private ApproveRentalRequestResponse rejectBannedCustomer(Long requestId, RentalRequest request) {
        Instant respondedAt = Instant.now();
        String note = "Tài khoản khách đã bị chặn";
        int updated = repository.rejectIfPending(requestId, RentalRequestRejectReason.OTHER, note,
                respondedAt, RentalRequestStatus.Pending, RentalRequestStatus.Rejected);
        if (updated == 0) {
            throw new RentalRequestAlreadyHandledException();
        }
        Map<String, Object> newValue = new LinkedHashMap<>();
        newValue.put("status", RentalRequestStatus.Rejected.name());
        newValue.put("rejectReason", RentalRequestRejectReason.OTHER.name());
        newValue.put("rejectNote", note);
        auditRecorder.record(new AuditEntry(AuditAction.RENTAL_REQUEST_REJECTED,
                AuditEntityType.RENTAL_REQUEST, String.valueOf(requestId),
                Map.of("status", RentalRequestStatus.Pending.name()), newValue, null,
                currentAccountProvider.getCurrentAccountId()));

        NotificationTemplateCatalog.Template template = templateCatalog.resolve(
                NotificationType.RENTAL_REQUEST_REJECTED_BANNED, NotificationRecipient.CUSTOMER);
        String title = template.renderTitle(Map.of());
        emailSender.sendHtml(request.getNormalizedCustomerEmail(), title,
                NotificationEmailLayout.wrap(title, template.renderBody(Map.of())));

        return new ApproveRentalRequestResponse(requestId, RentalRequestStatus.Rejected.name(),
                null, respondedAt, null);
    }

    /**
     * Mail duyệt cho nhánh email chưa có account (template draft.md:156) — after-commit,
     * lỗi mail chỉ log, không rollback. Nhánh đã có account nhận thông báo kèm link đề xuất ở bước đề xuất khoang.
     */
    private void sendApprovalEmail(RentalRequest request, Instant expiresAt) {
        // findIdByEmail (không throw) thay requireIdByEmail: throw từ @Transactional proxy sẽ mark
        // transaction hiện tại rollback-only → commit nổ UnexpectedRollbackException (bug có từ
        // bản đầu PR, unit test mock không thấy được).
        if (accountQueryService.findIdByEmail(request.getNormalizedCustomerEmail()).isPresent()) {
            return;
        }
        String deadline = VI_DATE.format(expiresAt.atZone(FACILITY_ZONE));
        String title = "Yêu cầu đặt kho của bạn đã được duyệt";
        String body = "Yêu cầu đặt kho của bạn đã được duyệt, nhưng hệ thống nhận thấy email này "
                + "chưa có tài khoản trên website, vui lòng đăng ký tại " + frontendUrl + "/register trước "
                + deadline
                + " và đăng nhập để xác nhận và đặt cọc.\n\nLưu ý: khoang chứa không được giữ "
                + "trong lúc chờ. Khoang được xác nhận chính thức cho khách hàng hoàn tất thanh "
                + "toán cọc đầu tiên (cọc trước giữ trước).";
        emailSender.sendHtml(request.getNormalizedCustomerEmail(), title,
                NotificationEmailLayout.wrap(title, body));
    }

    /**
     * FM từ chối yêu cầu (B1 phần 4, spec draft.md §1.1) — trạng thái cuối Rejected, không sang B2.
     *
     * <p>Guard {@code rental_request.reject} đặt trong service (giống approve) vì cần
     * {@code facilityId} từ entity. Thứ tự: 404 nếu request không tồn tại, 403 chéo cơ sở,
     * 409 nếu request không còn Pending, 400 nếu {@code OTHER} thiếu ghi chú.
     * Chuyển trạng thái bằng conditional update {@code WHERE status = 'Pending'} (chống race
     * double-click / approve-reject song song) — 0 row thì 409.
     *
     * <p>Sau khi đổi trạng thái: gửi mail từ chối cho khách trong mọi trường hợp (after-commit,
     * lỗi mail chỉ log, không rollback); tạo thêm thông báo web chỉ khi email đã có account.
     */
    @Transactional
    public RejectRentalRequestResponse reject(Long requestId, RentalRequestRejectReason reason,
                                              String note) {
        RentalRequest request = repository.findById(requestId)
                .orElseThrow(RentalRequestNotFoundException::new);
        access.can("rental_request.reject", request.getFacilityId());
        if (request.getStatus() != RentalRequestStatus.Pending) {
            throw new RentalRequestAlreadyHandledException();
        }
        if (reason == null) {
            throw new RentalRequestValidationException("Lý do từ chối không được để trống");
        }
        String trimmedNote = note == null ? null : note.strip();
        if (reason == RentalRequestRejectReason.OTHER
                && (trimmedNote == null || trimmedNote.isEmpty())) {
            throw new RentalRequestValidationException("Ghi chú bắt buộc khi lý do là Khác");
        }
        Instant respondedAt = Instant.now();
        int updated = repository.rejectIfPending(requestId, reason, trimmedNote, respondedAt,
                RentalRequestStatus.Pending, RentalRequestStatus.Rejected);
        if (updated == 0) {
            throw new RentalRequestAlreadyHandledException();
        }
        Map<String, Object> newValue = new LinkedHashMap<>();
        newValue.put("status", RentalRequestStatus.Rejected.name());
        newValue.put("rejectReason", reason.name());
        if (trimmedNote != null) {
            newValue.put("rejectNote", trimmedNote);
        }
        auditRecorder.record(new AuditEntry(AuditAction.RENTAL_REQUEST_REJECTED,
                AuditEntityType.RENTAL_REQUEST, String.valueOf(requestId),
                Map.of("status", RentalRequestStatus.Pending.name()), newValue, null,
                currentAccountProvider.getCurrentAccountId()));
        sendRejection(request, reason, trimmedNote);
        return new RejectRentalRequestResponse(request.getId(), RentalRequestStatus.Rejected.name(),
                reason.name(), respondedAt);
    }

    private void sendRejection(RentalRequest request, RentalRequestRejectReason reason,
                               String note) {
        String reasonText = rejectReasonText(reason, note);
        Map<String, String> values = Map.of("reason", reasonText);
        Long accountId = accountQueryService.findIdByEmail(request.getNormalizedCustomerEmail())
                .orElse(null);
        if (accountId != null) {
            notificationService.notify(accountId, request.getNormalizedCustomerEmail(),
                    NotificationRecipient.CUSTOMER, NotificationType.RENTAL_REQUEST_REJECTED,
                    values, null);
            return;
        }
        NotificationTemplateCatalog.Template template = templateCatalog
                .resolve(NotificationType.RENTAL_REQUEST_REJECTED, NotificationRecipient.CUSTOMER);
        emailSender.sendHtml(request.getNormalizedCustomerEmail(),
                template.renderTitle(values),
                NotificationEmailLayout.wrap(template.renderTitle(values),
                        template.renderBody(values)));
    }

    private static String rejectReasonText(RentalRequestRejectReason reason, String note) {
        String label = switch (reason) {
            case UNIT_UNAVAILABLE -> "Khoang không còn trống";
            case UNIT_MISMATCH -> "Khoang không phù hợp với nhu cầu";
            case CUSTOMER_REQUEST -> "Theo yêu cầu của khách";
            case OTHER -> "Lý do khác";
        };
        return note == null || note.isEmpty() ? label : label + " - " + note;
    }

    private long pendingExpiryDays() {
        return policyDays(PENDING_EXPIRY_KEY);
    }

    private long claimExpiryDays() {
        return policyDays(CLAIM_EXPIRY_KEY);
    }

    private long policyDays(String key) {
        try {
            return pricingQuery.valueOf(key).map(Long::parseLong).orElse(
                    EXPIRY_FALLBACK_DAYS);
        } catch (NumberFormatException ex) {
            return EXPIRY_FALLBACK_DAYS;
        }
    }
}
