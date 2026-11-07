package vn.lemar.selfstorage.handover.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.lemar.selfstorage.audit.application.AuditEntry;
import vn.lemar.selfstorage.audit.application.AuditRecorder;
import vn.lemar.selfstorage.audit.domain.AuditAction;
import vn.lemar.selfstorage.audit.domain.AuditEntityType;
import vn.lemar.selfstorage.booking.application.HandoverOrderService;
import vn.lemar.selfstorage.booking.application.HandoverOrderService.ContractInvoice;
import vn.lemar.selfstorage.booking.application.HandoverOrderService.OrderSnapshot;
import vn.lemar.selfstorage.facility.application.FacilityAccess;
import vn.lemar.selfstorage.facility.application.FacilityAccessOptions;
import vn.lemar.selfstorage.handover.application.exception.HandoverConflictException;
import vn.lemar.selfstorage.handover.application.exception.HandoverForbiddenException;
import vn.lemar.selfstorage.handover.application.exception.HandoverNotFoundException;
import vn.lemar.selfstorage.handover.application.exception.HandoverValidationException;
import vn.lemar.selfstorage.handover.domain.AccessChoice;
import vn.lemar.selfstorage.handover.domain.HandoverRecord;
import vn.lemar.selfstorage.handover.domain.HandoverResult;
import vn.lemar.selfstorage.handover.domain.RentalContract;
import vn.lemar.selfstorage.handover.domain.RentalContractStatus;
import vn.lemar.selfstorage.handover.repository.HandoverReadRepository;
import vn.lemar.selfstorage.handover.repository.HandoverReadRepository.HandoverRow;
import vn.lemar.selfstorage.handover.repository.HandoverRecordRepository;
import vn.lemar.selfstorage.handover.repository.RentalContractRepository;
import vn.lemar.selfstorage.identity.application.AccountQueryService;
import vn.lemar.selfstorage.identity.application.Access;
import vn.lemar.selfstorage.identity.application.CurrentAccountProvider;
import vn.lemar.selfstorage.notification.application.NotificationRecipient;
import vn.lemar.selfstorage.notification.application.NotificationService;
import vn.lemar.selfstorage.notification.application.NotificationType;
import vn.lemar.selfstorage.pricing.application.PricingQuery;

/**
 * Thao tác Flow 2.2–2.5 trên phiên bàn giao.
 *
 * <ul>
 *   <li>FS (lịch được phân cho mình): xác minh danh tính, ghi hiện trạng, đề nghị đổi mốc tính tiền, ký hợp đồng
 *       (sinh hoá đơn tháng đầu), khách không ký, giao chìa.</li>
 *   <li>Khách: đồng ý hiện trạng (sinh hợp đồng {@code Draft}), từ chối khoang, xác nhận biên bản + chọn hình thức
 *       nhận quyền truy cập, lấy lại mã số.</li>
 *   <li>FM: duyệt/từ chối đổi mốc tính tiền.</li>
 * </ul>
 *
 * <p>Thứ tự khoá mọi thao tác ghi: đơn → biên bản → hợp đồng → khoang (cùng chiều Cancellation Cascade).
 */
@Service
public class HandoverWorkflowService {

    static final String PERM_VERIFY = "handover_record.verify_identity";
    static final String PERM_INSPECT = "handover_record.inspect";
    static final String PERM_SIGN = "rental_contract.sign";
    static final String PERM_OVERRIDE_REQUEST = "rental_contract.start_date_override.request";
    static final String PERM_OVERRIDE_REVIEW = "rental_contract.start_date_override.review";
    static final String PERM_KEY = "unit_access_key.issue";

    static final String POLICY_MAX_REJECTION = "handover.max_rejection_count";
    static final String POLICY_GRACE_HOURS = "handover.payment_grace_hours";
    static final String POLICY_PREPAID_MONTHS = "contract.prepaid_months";
    static final String POLICY_START_DATE_RULE = "contract.start_date_rule";
    static final String TERMS_VERSION = "builtin-v1";

    static final String REASON_NOT_SIGNED = "Khách không ký hợp đồng";
    static final String REASON_TOO_MANY_REJECTIONS = "Từ chối quá số khoang cho phép";
    static final String REASON_REJECTED_CONTRACT = "Khách từ chối khoang";

    static final String MSG_NOT_FOUND = HandoverQueryService.MSG_NOT_FOUND;
    static final String MSG_CLOSED = "Phiên bàn giao đã đóng";
    static final String MSG_NOT_ASSIGNED = "Bạn không phải FS phụ trách lịch này";
    static final String MSG_NOT_ARRIVED = "Khách chưa được xác nhận đã đến cơ sở";
    static final String MSG_CONTRACT_NOT_FOUND = "Không tìm thấy hợp đồng";

    private static final Logger LOG = LoggerFactory.getLogger(HandoverWorkflowService.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter CODE_DATE = DateTimeFormatter.ofPattern("yyMMdd");
    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final SecureRandom random = new SecureRandom();
    private final HandoverRecordRepository records;
    private final RentalContractRepository contracts;
    private final HandoverReadRepository readRepository;
    private final HandoverOrderService orders;
    private final HandoverCompletion completion;
    private final HandoverService handoverService;
    private final FacilityAccess facilityAccess;
    private final PricingQuery pricingQuery;
    private final Access access;
    private final CurrentAccountProvider currentAccount;
    private final AccountQueryService accountQuery;
    private final NotificationService notificationService;
    private final AuditRecorder auditRecorder;

    HandoverWorkflowService(HandoverRecordRepository records, RentalContractRepository contracts,
                            HandoverReadRepository readRepository, HandoverOrderService orders,
                            HandoverCompletion completion, HandoverService handoverService,
                            FacilityAccess facilityAccess, PricingQuery pricingQuery, Access access,
                            CurrentAccountProvider currentAccount, AccountQueryService accountQuery,
                            NotificationService notificationService, AuditRecorder auditRecorder) {
        this.records = records;
        this.contracts = contracts;
        this.readRepository = readRepository;
        this.orders = orders;
        this.completion = completion;
        this.handoverService = handoverService;
        this.facilityAccess = facilityAccess;
        this.pricingQuery = pricingQuery;
        this.access = access;
        this.currentAccount = currentAccount;
        this.accountQuery = accountQuery;
        this.notificationService = notificationService;
        this.auditRecorder = auditRecorder;
    }

    /** Khách từ chối khoang: chạm ngưỡng mà chưa xác nhận huỷ đơn → {@code requiresConfirmation}. */
    public record RejectOutcome(boolean requiresConfirmation, boolean orderCanceled, String message) {
    }

    /** Kết quả xác nhận biên bản: {@code accessCode} chỉ có khi vừa cấp mã số (hiện đúng một lần). */
    public record ConfirmOutcome(boolean completed, String accessCode, String message) {
    }

    // ---------------------------------------------------------------- FS

    /** Spec 2.2: FS đối chiếu giấy tờ với tài khoản của đơn. */
    @Transactional
    public void verifyIdentity(Long handoverId, boolean passed, String note) {
        Locked locked = lockForStaff(handoverId, PERM_VERIFY);
        HandoverRecord record = locked.record();
        if (record.identityVerified()) {
            throw new HandoverConflictException("Danh tính đã được xác minh");
        }
        String before = record.getIdentityStatus();
        record.verifyIdentity(passed, Instant.now());
        Map<String, Object> newValue = new HashMap<>();
        newValue.put("identityStatus", record.getIdentityStatus());
        if (note != null && !note.isBlank()) {
            newValue.put("note", note.trim());
        }
        audit(AuditAction.IDENTITY_VERIFIED, AuditEntityType.HANDOVER_RECORD, record.getId(),
                Map.of("identityStatus", before), newValue, passed ? null : note, locked.actorId());
    }

    /** Spec 2.2: FS ghi hiện trạng khoang (ghi chú + ảnh đã upload qua media). */
    @Transactional
    public void recordInspection(Long handoverId, String notes, List<String> photos) {
        Locked locked = lockForStaff(handoverId, PERM_INSPECT);
        HandoverRecord record = locked.record();
        if (!record.identityVerified()) {
            throw new HandoverConflictException("Cần xác minh danh tính trước khi kiểm tra khoang");
        }
        if (!HandoverRecord.PENDING.equals(record.getInspectionStatus())) {
            throw new HandoverConflictException("Hiện trạng khoang đã được khách chốt, không sửa được");
        }
        if (photos != null && photos.size() > 20) {
            throw new HandoverValidationException("Tối đa 20 ảnh hiện trạng");
        }
        record.recordInspection(trimToNull(notes), photos, Instant.now());
    }

    /** Spec 2.3: FS đề nghị đổi mốc tính tiền trên hợp đồng {@code Draft}; FM duyệt. */
    @Transactional
    public void requestStartDateOverride(Long contractId, LocalDate requested, String reason) {
        if (requested == null || reason == null || reason.isBlank()) {
            throw new HandoverValidationException("Cần ngày đề nghị và lý do");
        }
        Locked locked = lockForStaff(handoverIdOfContract(contractId), PERM_OVERRIDE_REQUEST);
        RentalContract contract = lockContract(contractId, locked);
        if (contract.getStatus() != RentalContractStatus.DRAFT) {
            throw new HandoverConflictException("Chỉ đổi mốc tính tiền khi hợp đồng còn bản nháp");
        }
        if (contract.hasPendingOverride()) {
            throw new HandoverConflictException("Đang có đề nghị đổi mốc chờ FM duyệt");
        }
        if (requested.isBefore(LocalDate.now(ZONE))) {
            throw new HandoverValidationException("Ngày bắt đầu tính tiền không được ở quá khứ");
        }
        LocalDate before = contract.getStartDate();
        contract.requestStartDateOverride(requested, reason.trim());
        audit(AuditAction.START_DATE_OVERRIDE_REQUESTED, AuditEntityType.RENTAL_CONTRACT, contractId,
                Map.of("startDate", before.toString()),
                Map.of("requestedStartDate", requested.toString(), "status", RentalContract.OVERRIDE_PENDING),
                reason.trim(), locked.actorId());
    }

    /**
     * Spec 2.3: FS upload bản scan đã ký → hợp đồng {@code Signed}; cùng transaction tạo hoá đơn tháng đầu
     * {@code due = min(now + handover.payment_grace_hours, expires_at)}.
     */
    @Transactional
    public ContractInvoice signContract(Long contractId, String pdfUrl) {
        if (pdfUrl == null || pdfUrl.isBlank()) {
            throw new HandoverValidationException("Cần bản scan hợp đồng đã ký");
        }
        Locked locked = lockForStaff(handoverIdOfContract(contractId), PERM_SIGN);
        HandoverRecord record = locked.record();
        if (!record.identityVerified() || !record.inspectionAgreed()) {
            throw new HandoverConflictException("Cần xác minh danh tính và khách đồng ý hiện trạng trước khi ký");
        }
        RentalContract contract = lockContract(contractId, locked);
        if (contract.getStatus() != RentalContractStatus.DRAFT) {
            throw new HandoverConflictException("Hợp đồng không còn ở bản nháp");
        }
        if (contract.hasPendingOverride()) {
            throw new HandoverConflictException("Đề nghị đổi mốc tính tiền đang chờ FM duyệt");
        }
        Instant now = Instant.now();
        contract.sign(pdfUrl.trim(), now);
        audit(AuditAction.CONTRACT_SIGNED, AuditEntityType.RENTAL_CONTRACT, contractId,
                Map.of("status", RentalContractStatus.DRAFT.dbValue()),
                Map.of("status", contract.getStatus().dbValue(), "pdfUrl", contract.getPdfUrl()), null,
                locked.actorId());

        BigDecimal amount = contract.getMonthlyPrice()
                .multiply(BigDecimal.valueOf(pricingQuery.intPolicy(POLICY_PREPAID_MONTHS)))
                .setScale(2, RoundingMode.HALF_UP);
        Instant dueDate = now.plus(Duration.ofHours(pricingQuery.intPolicy(POLICY_GRACE_HOURS)));
        if (locked.order().expiresAt() != null && locked.order().expiresAt().isBefore(dueDate)) {
            dueDate = locked.order().expiresAt();
        }
        return orders.issueFirstMonthInvoice(record.getOrderId(), contractId, locked.row().unitCode(), amount,
                dueDate, locked.actorId());
    }

    /** Spec 2.5: có hợp đồng {@code Draft} nhưng khách không ký → Cascade "Khách không ký hợp đồng". */
    @Transactional
    public void customerDidNotSign(Long handoverId) {
        Locked locked = lockForStaff(handoverId, PERM_SIGN);
        RentalContract contract = contracts.findCurrentByOrderId(locked.record().getOrderId())
                .orElseThrow(() -> new HandoverConflictException("Chưa có hợp đồng để ghi nhận khách không ký"));
        if (contract.getStatus() != RentalContractStatus.DRAFT) {
            throw new HandoverConflictException("Hợp đồng đã ký — khách chỉ còn lựa chọn huỷ đơn");
        }
        orders.cancel(locked.record().getOrderId(), REASON_NOT_SIGNED, locked.actorId());
    }

    /** Spec 2.4: FS giao chìa (khách đã chọn khóa cơ / cả hai) → chốt bàn giao. */
    @Transactional
    public void handOverKeys(Long handoverId, int quantity) {
        if (quantity <= 0 || quantity > 10) {
            throw new HandoverValidationException("Số chìa giao phải từ 1 đến 10");
        }
        Locked locked = lockForStaff(handoverId, PERM_KEY);
        HandoverRecord record = locked.record();
        AccessChoice choice = record.accessChoice();
        if (choice == null) {
            throw new HandoverConflictException("Khách chưa xác nhận biên bản bàn giao trong tài khoản");
        }
        if (!choice.needsPhysicalKey()) {
            throw new HandoverConflictException("Khách chọn nhận mã khóa số, không cần giao chìa");
        }
        RentalContract contract = currentContract(record, locked);
        completion.complete(record, contract, quantity, locked.row().unitCode(), locked.actorId());
    }

    // ---------------------------------------------------------------- Customer

    /** Spec 2.2 — khách đồng ý hiện trạng → sinh hợp đồng {@code Draft} (spec 2.3 giai đoạn 1). */
    @Transactional
    public void agreeInspection(Long handoverId, Long customerId, String note) {
        Locked locked = lockForCustomer(handoverId, customerId);
        HandoverRecord record = locked.record();
        if (!record.identityVerified()) {
            throw new HandoverConflictException("FS chưa xác minh danh tính của bạn");
        }
        if (!HandoverRecord.PENDING.equals(record.getInspectionStatus())) {
            throw new HandoverConflictException("Hiện trạng khoang đã được xác nhận");
        }
        if (note != null && note.length() > 1000) {
            throw new HandoverValidationException("Ghi chú tối đa 1000 ký tự");
        }
        record.agreeInspection(note, Instant.now());
        audit(AuditAction.UNIT_INSPECTED, AuditEntityType.HANDOVER_RECORD, record.getId(),
                Map.of("inspectionStatus", HandoverRecord.PENDING),
                Map.of("inspectionStatus", record.getInspectionStatus()), null, customerId);
        if (contracts.findCurrentByOrderId(record.getOrderId()).isEmpty()) {
            createDraftContract(record, locked);
        }
    }

    /**
     * Spec 2.5 — khách từ chối khoang (trước khi ký). Đếm phiên {@code REJECTED} của đơn trong cùng transaction;
     * {@code count + 1 >= handover.max_rejection_count} thì phải xác nhận huỷ đơn (mất cọc).
     */
    @Transactional
    public RejectOutcome reject(Long handoverId, Long customerId, String reason, boolean confirmCancel) {
        if (reason == null || reason.isBlank()) {
            throw new HandoverValidationException("Vui lòng nhập lý do từ chối khoang");
        }
        if (reason.length() > 500) {
            throw new HandoverValidationException("Lý do tối đa 500 ký tự");
        }
        Locked locked = lockForCustomer(handoverId, customerId);
        HandoverRecord record = locked.record();
        RentalContract contract = contracts.findCurrentByOrderId(record.getOrderId()).orElse(null);
        if (contract != null && contract.getStatus() != RentalContractStatus.DRAFT) {
            throw new HandoverConflictException("Hợp đồng đã ký — bạn chỉ còn lựa chọn huỷ đơn");
        }
        long rejected = records.countByOrderIdAndResult(record.getOrderId(), HandoverResult.REJECTED);
        int max = pricingQuery.intPolicy(POLICY_MAX_REJECTION);
        boolean reachesLimit = rejected + 1 >= max;
        if (reachesLimit && !confirmCancel) {
            return new RejectOutcome(true, false, "Đơn sẽ bị huỷ vì đã từ chối tối đa " + max
                    + " khoang và tiền cọc không được hoàn. Xác nhận nếu bạn vẫn muốn từ chối.");
        }
        String before = record.getInspectionStatus();
        record.reject(reason.trim(), Instant.now());
        records.flush();
        audit(AuditAction.HANDOVER_REJECTED, AuditEntityType.HANDOVER_RECORD, record.getId(),
                Map.of("result", HandoverResult.IN_PROGRESS.name(), "inspectionStatus", before),
                Map.of("result", record.getResult().name(), "inspectionStatus", record.getInspectionStatus()),
                reason.trim(), customerId);
        if (reachesLimit) {
            orders.cancel(record.getOrderId(), REASON_TOO_MANY_REJECTIONS, customerId);
            return new RejectOutcome(false, true, "Đơn đã bị huỷ do từ chối quá số khoang cho phép");
        }
        handoverService.cancelByOrderId(record.getOrderId(), REASON_REJECTED_CONTRACT);
        orders.rejectUnit(record.getOrderId(), locked.row().unitCode(), reason.trim());
        return new RejectOutcome(false, false, "Đã ghi nhận từ chối. Cơ sở sẽ đề xuất khoang khác cho bạn.");
    }

    /**
     * Spec 2.4 — khách xác nhận biên bản sau khi hoá đơn tháng đầu {@code Paid} và chọn hình thức nhận quyền truy
     * cập. Mã khóa số → chốt bàn giao ngay; khóa cơ / cả hai → chờ FS giao chìa.
     */
    @Transactional
    public ConfirmOutcome confirm(Long handoverId, Long customerId, AccessChoice choice) {
        if (choice == null) {
            throw new HandoverValidationException("Chọn hình thức nhận quyền truy cập");
        }
        Locked locked = lockForCustomer(handoverId, customerId);
        HandoverRecord record = locked.record();
        if (record.accessChoice() != null) {
            throw new HandoverConflictException("Bạn đã xác nhận biên bản — đang chờ FS giao chìa khóa");
        }
        FacilityAccessOptions options = facilityAccess.accessOptions(locked.row().facilityId())
                .orElse(new FacilityAccessOptions(false, false));
        if ((choice.needsCode() && !options.codeAccess()) || (choice.needsPhysicalKey() && !options.keyAccess())) {
            throw new HandoverValidationException("Cơ sở không hỗ trợ hình thức truy cập đã chọn");
        }
        RentalContract contract = currentContract(record, locked);
        if (contract.getStatus() != RentalContractStatus.SIGNED) {
            throw new HandoverConflictException("Hợp đồng chưa được ký");
        }
        boolean paid = orders.firstMonthInvoice(contract.getId()).map(ContractInvoice::paid).orElse(false);
        if (!paid) {
            throw new HandoverConflictException("Vui lòng thanh toán hoá đơn tháng đầu trước khi xác nhận bàn giao");
        }
        if (!record.identityVerified() || !record.inspectionAgreed()) {
            throw new HandoverConflictException("Biên bản chưa đủ xác minh danh tính và hiện trạng khoang");
        }
        record.confirmByCustomer(choice, Instant.now());
        if (choice == AccessChoice.ACCESS_CODE) {
            String code = completion.complete(record, contract, null, locked.row().unitCode(), customerId);
            return new ConfirmOutcome(true, code, "Bàn giao hoàn tất. Mã truy cập chỉ hiển thị một lần — hãy lưu lại.");
        }
        notifyStaffKeyRequested(locked);
        return new ConfirmOutcome(false, null, "Đã xác nhận. Vui lòng nhận chìa khóa từ nhân viên cơ sở.");
    }

    /** Khách lấy mã khóa số mới cho hợp đồng đang thuê (mã cũ bị thu hồi). */
    @Transactional
    public String rotateAccessCode(Long contractId, Long customerId) {
        RentalContract contract = contracts.findByIdForUpdate(contractId)
                .filter(c -> c.getCustomerId().equals(customerId))
                .orElseThrow(() -> new HandoverNotFoundException(MSG_CONTRACT_NOT_FOUND));
        if (contract.getStatus() != RentalContractStatus.ACTIVE) {
            throw new HandoverConflictException("Hợp đồng chưa có hiệu lực");
        }
        Long facilityId = readRepository.findContractFacilityId(contractId)
                .orElseThrow(() -> new HandoverNotFoundException(MSG_CONTRACT_NOT_FOUND));
        boolean codeEnabled = facilityAccess.accessOptions(facilityId).map(FacilityAccessOptions::codeAccess)
                .orElse(false);
        if (!codeEnabled) {
            throw new HandoverConflictException("Cơ sở không dùng mã khóa số");
        }
        return completion.rotateCode(contract, customerId);
    }

    // ---------------------------------------------------------------- FM

    /** Spec 2.3: FM duyệt/từ chối đổi mốc tính tiền; cả hai đều mở lại bước ký. */
    @Transactional
    public void reviewStartDateOverride(Long contractId, boolean approve, String reason) {
        Long facilityId = readRepository.findContractFacilityId(contractId)
                .orElseThrow(() -> new HandoverNotFoundException(MSG_CONTRACT_NOT_FOUND));
        access.can(PERM_OVERRIDE_REVIEW, facilityId);
        RentalContract peek = contracts.findById(contractId)
                .orElseThrow(() -> new HandoverNotFoundException(MSG_CONTRACT_NOT_FOUND));
        orders.lock(peek.getOrderId());
        RentalContract contract = contracts.findByIdForUpdate(contractId)
                .orElseThrow(() -> new HandoverNotFoundException(MSG_CONTRACT_NOT_FOUND));
        if (contract.getStatus() != RentalContractStatus.DRAFT || !contract.hasPendingOverride()) {
            throw new HandoverConflictException("Không có đề nghị đổi mốc tính tiền đang chờ");
        }
        LocalDate before = contract.getStartDate();
        Map<String, Object> newValue = new HashMap<>();
        if (approve) {
            contract.approveStartDateOverride();
        } else {
            contract.rejectStartDateOverride();
        }
        newValue.put("startDate", contract.getStartDate().toString());
        newValue.put("status", contract.getStartDateOverrideStatus());
        audit(approve ? AuditAction.START_DATE_OVERRIDE_APPROVED : AuditAction.START_DATE_OVERRIDE_REJECTED,
                AuditEntityType.RENTAL_CONTRACT, contractId,
                Map.of("startDate", before.toString(), "status", RentalContract.OVERRIDE_PENDING), newValue,
                trimToNull(reason), currentAccount.getCurrentAccountId());
    }

    // ---------------------------------------------------------------- internals

    private record Locked(HandoverRow row, OrderSnapshot order, HandoverRecord record, Long actorId) {
    }

    /** FS thao tác: quyền theo cơ sở → đúng FS được phân → khoá đơn (InProgress) → khoá biên bản (đang mở). */
    private Locked lockForStaff(Long handoverId, String permission) {
        HandoverRow row = readRepository.findById(handoverId)
                .orElseThrow(() -> new HandoverNotFoundException(MSG_NOT_FOUND));
        access.can(permission, row.facilityId());
        Long actorId = currentAccount.getCurrentAccountId();
        if (!actorId.equals(row.staffId())) {
            throw new HandoverForbiddenException(MSG_NOT_ASSIGNED);
        }
        return lock(row, actorId);
    }

    private Locked lockForCustomer(Long handoverId, Long customerId) {
        HandoverRow row = readRepository.findById(handoverId)
                .filter(r -> r.customerId().equals(customerId))
                .orElseThrow(() -> new HandoverNotFoundException(MSG_NOT_FOUND));
        return lock(row, customerId);
    }

    private Locked lock(HandoverRow row, Long actorId) {
        OrderSnapshot order = orders.lock(row.orderId())
                .orElseThrow(() -> new HandoverNotFoundException(MSG_NOT_FOUND));
        HandoverRecord record = records.findByIdForUpdate(row.id())
                .orElseThrow(() -> new HandoverNotFoundException(MSG_NOT_FOUND));
        if (!record.isOpen()) {
            throw new HandoverConflictException(MSG_CLOSED);
        }
        if (!order.inProgress()) {
            throw new HandoverConflictException("Đơn thuê không ở trạng thái đang bàn giao");
        }
        if (row.arrivedAt() == null) {
            throw new HandoverConflictException(MSG_NOT_ARRIVED);
        }
        return new Locked(row, order, record, actorId);
    }

    private Long handoverIdOfContract(Long contractId) {
        RentalContract contract = contracts.findById(contractId)
                .orElseThrow(() -> new HandoverNotFoundException(MSG_CONTRACT_NOT_FOUND));
        return records.findOpenByOrderId(contract.getOrderId()).map(HandoverRecord::getId)
                .orElseThrow(() -> new HandoverConflictException(MSG_CLOSED));
    }

    private RentalContract lockContract(Long contractId, Locked locked) {
        RentalContract contract = contracts.findByIdForUpdate(contractId)
                .orElseThrow(() -> new HandoverNotFoundException(MSG_CONTRACT_NOT_FOUND));
        if (!contract.getOrderId().equals(locked.record().getOrderId())) {
            throw new HandoverNotFoundException(MSG_CONTRACT_NOT_FOUND);
        }
        return contract;
    }

    private RentalContract currentContract(HandoverRecord record, Locked locked) {
        RentalContract contract = contracts.findCurrentByOrderId(record.getOrderId())
                .orElseThrow(() -> new HandoverConflictException("Chưa có hợp đồng cho phiên bàn giao"));
        return lockContract(contract.getId(), locked);
    }

    /** Spec 2.3 giai đoạn 1: hợp đồng {@code Draft} từ mẫu dựng sẵn, mốc tính tiền theo chính sách. */
    private void createDraftContract(HandoverRecord record, Locked locked) {
        OrderSnapshot order = locked.order();
        int period = readRepository.findPeriod(order.orderId())
                .orElseThrow(() -> new IllegalStateException("Đơn " + order.orderId() + " không có kỳ thuê"));
        BigDecimal monthlyPrice = pricingQuery.effectiveMonthlyPrice(record.getUnitId())
                .setScale(2, RoundingMode.HALF_UP);
        LocalDate startDate = LocalDate.now(ZONE);
        String rule = pricingQuery.valueOf(POLICY_START_DATE_RULE).orElse("handover_date");
        if (!"handover_date".equals(rule)) {
            LOG.warn("contract.start_date_rule = {} chưa hỗ trợ — dùng ngày sinh hợp đồng", rule);
        }
        RentalContract contract = RentalContract.draft(order.orderId(), order.customerId(), record.getUnitId(),
                contractCode(locked.row().facilityId()), TERMS_VERSION, monthlyPrice, order.depositPaid(), period,
                startDate);
        contracts.save(contract);
        contract.attachDocument("/api/contracts/" + contract.getId() + "/document");
    }

    /** {@code HD-{facility_code}-{YYMMDD}-{rand}}. */
    private String contractCode(Long facilityId) {
        String facilityCode = facilityAccess.code(facilityId).orElse("NA");
        StringBuilder suffix = new StringBuilder(6);
        for (int i = 0; i < 6; i++) {
            suffix.append(CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length())));
        }
        return "HD-" + facilityCode + "-" + CODE_DATE.format(LocalDate.now(ZONE)) + "-" + suffix;
    }

    private void notifyStaffKeyRequested(Locked locked) {
        Long staffId = locked.row().staffId();
        if (staffId == null) {
            return;
        }
        notificationService.notify(staffId, accountQuery.requireEmailById(staffId), NotificationRecipient.FS,
                NotificationType.HANDOVER_KEY_REQUESTED,
                Map.of("orderCode", String.valueOf(locked.record().getOrderId()), "unitCode",
                        locked.row().unitCode()), locked.record().getOrderId());
    }

    private void audit(AuditAction action, AuditEntityType type, Long entityId, Object oldValue, Object newValue,
                       String reason, Long actorId) {
        auditRecorder.record(new AuditEntry(action, type, String.valueOf(entityId), oldValue, newValue, reason,
                actorId));
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
