package vn.lemar.selfstorage.support.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.lemar.selfstorage.audit.application.AuditEntry;
import vn.lemar.selfstorage.audit.application.AuditRecorder;
import vn.lemar.selfstorage.audit.domain.AuditAction;
import vn.lemar.selfstorage.audit.domain.AuditEntityType;
import vn.lemar.selfstorage.booking.application.ContractInvoiceService.ContractBill;
import vn.lemar.selfstorage.booking.application.ContractInvoiceService;
import vn.lemar.selfstorage.booking.application.HandoverOrderService;
import vn.lemar.selfstorage.facility.application.FacilityAccess;
import vn.lemar.selfstorage.handover.application.RentalContractService.ContractSnapshot;
import vn.lemar.selfstorage.handover.application.RentalContractService;
import vn.lemar.selfstorage.identity.application.Access;
import vn.lemar.selfstorage.identity.application.AccountQueryService;
import vn.lemar.selfstorage.identity.application.CurrentAccountProvider;
import vn.lemar.selfstorage.identity.application.FacilityStaffQuery;
import vn.lemar.selfstorage.media.MediaAttachments;
import vn.lemar.selfstorage.notification.application.NotificationRecipient;
import vn.lemar.selfstorage.notification.application.NotificationService;
import vn.lemar.selfstorage.notification.application.NotificationType;
import vn.lemar.selfstorage.pricing.application.PricingQuery;
import vn.lemar.selfstorage.support.domain.SupportRequest;
import vn.lemar.selfstorage.support.repository.SupportRequestRepository;
import vn.lemar.selfstorage.tenancy.application.TenancyException;

/**
 * Flow 7 — yêu cầu hỗ trợ / sự cố.
 * <ul>
 *   <li>Khách gửi (gắn hợp đồng đang thuê, hoặc chỉ chọn cơ sở), xem, huỷ khi chưa xử lý xong.</li>
 *   <li>FM của cơ sở xem hàng chờ, giao FS (giao lại được khi chưa xử lý).</li>
 *   <li>FS được giao (hoặc FM) xử lý: ghi kết quả; mất chìa thì cấp bộ chìa mới (chìa cũ {@code Lost});
 *       có phí thì lập hoá đơn {@code Service} gắn hợp đồng. Yêu cầu {@code LOST_KEY} có thay chìa mà không nhập
 *       phí → phí mặc định {@code fee.lost_key × số chìa của bộ cũ}.</li>
 * </ul>
 */
@Service
public class SupportService {

    static final String PERM_READ = "rental_order.read";
    static final String PERM_ASSIGN = "appointment.assign";
    static final String PERM_RESOLVE = "unit_access_key.issue";
    static final String MSG_NOT_FOUND = "Không tìm thấy yêu cầu hỗ trợ";
    static final int FEE_DUE_DAYS = 7;
    static final String POLICY_LOST_KEY = "fee.lost_key";
    static final String CATEGORY_LOST_KEY = "LOST_KEY";

    private final SupportRequestRepository requests;
    private final RentalContractService contracts;
    private final ContractInvoiceService invoices;
    private final HandoverOrderService orders;
    private final FacilityAccess facilityAccess;
    private final FacilityStaffQuery staffQuery;
    private final AccountQueryService accountQuery;
    private final NotificationService notificationService;
    private final Access access;
    private final CurrentAccountProvider currentAccount;
    private final AuditRecorder auditRecorder;
    private final JdbcTemplate jdbc;
    private final PricingQuery pricingQuery;
    private final MediaAttachments mediaAttachments;

    SupportService(SupportRequestRepository requests, RentalContractService contracts, ContractInvoiceService invoices,
                   HandoverOrderService orders, FacilityAccess facilityAccess, FacilityStaffQuery staffQuery,
                   AccountQueryService accountQuery, NotificationService notificationService, Access access,
                   CurrentAccountProvider currentAccount, AuditRecorder auditRecorder, JdbcTemplate jdbc,
                   PricingQuery pricingQuery, MediaAttachments mediaAttachments) {
        this.requests = requests;
        this.contracts = contracts;
        this.invoices = invoices;
        this.orders = orders;
        this.facilityAccess = facilityAccess;
        this.staffQuery = staffQuery;
        this.accountQuery = accountQuery;
        this.notificationService = notificationService;
        this.access = access;
        this.currentAccount = currentAccount;
        this.auditRecorder = auditRecorder;
        this.jdbc = jdbc;
        this.pricingQuery = pricingQuery;
        this.mediaAttachments = mediaAttachments;
    }

    /** Yêu cầu hỗ trợ. */
    public record SupportView(Long id, Long customerId, String customerName, String customerEmail, Long facilityId,
                              String facilityName, Long contractId, String contractCode, String unitCode,
                              String category, String title, String description, List<String> photos,
                              String status, Long assignedStaffId, String assignedStaffName, String resolution,
                              ContractBill feeInvoice, Instant createdAt, Instant assignedAt, Instant resolvedAt) {
    }

    /** Khách gửi yêu cầu. */
    public record CreateInput(Long contractId, Long facilityId, String category, String title, String description,
                              List<String> photos) {
    }

    /**
     * Kết quả xử lý: {@code newKeyQuantity} > 0 → thay chìa; {@code feeAmount} > 0 → hoá đơn dịch vụ. Yêu cầu
     * {@code LOST_KEY} có thay chìa và {@code feeAmount} bỏ trống → phí mặc định {@code fee.lost_key × số chìa mất};
     * nhập {@code feeAmount} (kể cả 0) thì dùng đúng số đó.
     */
    public record ResolveInput(String resolution, Integer newKeyQuantity, BigDecimal feeAmount) {
    }

    // ------------------------------------------------------------------ Customer

    @Transactional
    public SupportView create(Long customerId, CreateInput input) {
        if (input.title() == null || input.title().isBlank() || input.title().length() > 255) {
            throw TenancyException.invalid("Tiêu đề bắt buộc, tối đa 255 ký tự");
        }
        if (input.description() == null || input.description().isBlank() || input.description().length() > 4000) {
            throw TenancyException.invalid("Mô tả bắt buộc, tối đa 4000 ký tự");
        }
        if (input.category() == null || !SupportRequest.CATEGORIES.contains(input.category())) {
            throw TenancyException.invalid("Loại yêu cầu không hợp lệ");
        }
        if (input.photos() != null && input.photos().size() > 10) {
            throw TenancyException.invalid("Tối đa 10 ảnh");
        }
        Long facilityId = input.facilityId();
        ContractSnapshot contract = null;
        if (input.contractId() != null) {
            contract = contracts.find(input.contractId()).filter(c -> c.customerId().equals(customerId))
                    .orElseThrow(() -> TenancyException.notFound("Không tìm thấy hợp đồng thuê"));
            facilityId = contract.facilityId();
        }
        if (facilityId == null || !facilityAccess.exists(facilityId)) {
            throw TenancyException.invalid("Chọn hợp đồng hoặc cơ sở cần hỗ trợ");
        }
        if ("LOST_KEY".equals(input.category()) && contract == null) {
            throw TenancyException.invalid("Báo mất chìa cần chọn hợp đồng đang thuê");
        }
        SupportRequest request = requests.save(SupportRequest.open(customerId, facilityId,
                input.contractId(), input.category(), input.title().trim(), input.description().trim(),
                input.photos()));
        // Ảnh khách vừa upload gắn vào yêu cầu → attach để cron dọn upload bỏ ngang không xoá.
        mediaAttachments.attach(customerId, input.photos());
        audit(AuditAction.SUPPORT_REQUEST_CREATED, request.getId(), null,
                Map.of("status", request.getStatus(), "category", request.getCategory()), null, customerId);
        Long orderId = contract == null ? null : contract.orderId();
        facilityAccess.managerAccountId(facilityId).ifPresent(fmId -> notify(fmId, NotificationRecipient.FM,
                NotificationType.SUPPORT_REQUEST_CREATED, values(request), orderId));
        return view(request);
    }

    @Transactional(readOnly = true)
    public List<SupportView> mine(Long customerId) {
        return requests.findByCustomerIdOrderByIdDesc(customerId).stream().map(this::view).toList();
    }

    @Transactional
    public SupportView cancel(Long id, Long customerId) {
        SupportRequest request = requests.findByIdForUpdate(id).filter(r -> r.getCustomerId().equals(customerId))
                .orElseThrow(() -> TenancyException.notFound(MSG_NOT_FOUND));
        if (!request.isActive()) {
            throw TenancyException.conflict("Yêu cầu hỗ trợ đã đóng");
        }
        String before = request.getStatus();
        request.cancel();
        audit(AuditAction.SUPPORT_REQUEST_CANCELED, id, Map.of("status", before),
                Map.of("status", request.getStatus()), "Khách huỷ", customerId);
        return view(request);
    }

    // ------------------------------------------------------------------ FM / FS

    @Transactional(readOnly = true)
    public List<SupportView> facilityRequests(Long facilityId, boolean includeClosed) {
        access.can(PERM_READ, facilityId);
        List<String> statuses = includeClosed
                ? List.of(SupportRequest.OPEN, SupportRequest.ASSIGNED, SupportRequest.RESOLVED,
                        SupportRequest.CANCELED)
                : List.of(SupportRequest.OPEN, SupportRequest.ASSIGNED);
        return requests.findByFacilityIdAndStatusInOrderByIdDesc(facilityId, statuses).stream().map(this::view)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<SupportView> staffRequests() {
        return requests.findByAssignedStaffIdAndStatusOrderByIdAsc(currentAccount.getCurrentAccountId(),
                SupportRequest.ASSIGNED).stream().map(this::view).toList();
    }

    @Transactional
    public SupportView assign(Long id, Long staffId) {
        SupportRequest peek = requests.findById(id).orElseThrow(() -> TenancyException.notFound(MSG_NOT_FOUND));
        access.can(PERM_ASSIGN, peek.getFacilityId());
        if (staffId == null || !staffQuery.isActiveStaffOf(staffId, peek.getFacilityId())) {
            throw TenancyException.invalid("FS không thuộc cơ sở hoặc không còn hoạt động");
        }
        SupportRequest request = requests.findByIdForUpdate(id).orElseThrow();
        if (!request.isActive()) {
            throw TenancyException.conflict("Yêu cầu hỗ trợ đã đóng");
        }
        String before = request.getStatus();
        request.assign(staffId);
        Long actorId = currentAccount.getCurrentAccountId();
        audit(AuditAction.SUPPORT_REQUEST_ASSIGNED, id, Map.of("status", before),
                Map.of("status", request.getStatus(), "staffId", staffId), null, actorId);
        Long orderId = orderIdOf(request);
        Map<String, String> values = values(request);
        notify(staffId, NotificationRecipient.FS, NotificationType.SUPPORT_ASSIGNED, values, orderId);
        Map<String, String> customerValues = new HashMap<>(values);
        customerValues.put("staffName", staffName(staffId));
        notify(request.getCustomerId(), NotificationRecipient.CUSTOMER, NotificationType.SUPPORT_ASSIGNED,
                customerValues, orderId);
        return view(request);
    }

    /** FS được giao hoặc FM của cơ sở ghi kết quả xử lý. */
    @Transactional
    public SupportView resolve(Long id, ResolveInput input) {
        if (input.resolution() == null || input.resolution().isBlank() || input.resolution().length() > 2000) {
            throw TenancyException.invalid("Kết quả xử lý bắt buộc, tối đa 2000 ký tự");
        }
        if (input.newKeyQuantity() != null && (input.newKeyQuantity() < 0 || input.newKeyQuantity() > 10)) {
            throw TenancyException.invalid("Số chìa mới từ 0 đến 10");
        }
        if (input.feeAmount() != null && input.feeAmount().signum() < 0) {
            throw TenancyException.invalid("Phí không được âm");
        }
        // Cổng (payOS) chỉ thu số nguyên VND — phần lẻ làm số tiền thu ≠ hoá đơn, giao dịch không chốt được.
        if (input.feeAmount() != null && input.feeAmount().stripTrailingZeros().scale() > 0) {
            throw TenancyException.invalid("Phí phải là số nguyên (VND)");
        }
        SupportRequest peek = requests.findById(id).orElseThrow(() -> TenancyException.notFound(MSG_NOT_FOUND));
        Long actorId = currentAccount.getCurrentAccountId();
        if (actorId.equals(peek.getAssignedStaffId())) {
            access.can(PERM_RESOLVE, peek.getFacilityId());
        } else {
            access.can(PERM_ASSIGN, peek.getFacilityId());
        }
        boolean replaceKey = input.newKeyQuantity() != null && input.newKeyQuantity() > 0;
        boolean lostKeyReplacement = replaceKey && CATEGORY_LOST_KEY.equals(peek.getCategory());
        ContractSnapshot contract = null;
        if (replaceKey || (input.feeAmount() != null && input.feeAmount().signum() > 0)) {
            if (peek.getContractId() == null) {
                throw TenancyException.conflict("Yêu cầu không gắn hợp đồng — không thay chìa / thu phí được");
            }
            ContractSnapshot snapshot = contracts.find(peek.getContractId()).orElseThrow();
            orders.lock(snapshot.orderId());
            contract = contracts.lock(peek.getContractId()).orElseThrow();
            if (!contract.active()) {
                throw TenancyException.conflict("Hợp đồng không còn hiệu lực");
            }
        }
        SupportRequest request = requests.findByIdForUpdate(id).orElseThrow();
        if (!request.isActive()) {
            throw TenancyException.conflict("Yêu cầu hỗ trợ đã đóng");
        }
        // Phí mặc định mất chìa tính trên bộ chìa đang Active — đọc trước khi thay (bộ cũ chuyển Lost).
        BigDecimal fee = input.feeAmount() != null ? input.feeAmount()
                : lostKeyReplacement ? lostKeyFee(contract.id()) : null;
        boolean charge = fee != null && fee.signum() > 0;
        if (replaceKey) {
            contracts.replacePhysicalKey(contract.id(), input.newKeyQuantity(), actorId);
        }
        Long feeInvoiceId = null;
        if (charge) {
            ContractBill bill = invoices.issue(contract.orderId(), contract.id(), contract.customerId(), "Service",
                    "SVC", "Phí hỗ trợ #" + id + " — " + request.getTitle(), input.resolution().trim(),
                    fee.setScale(2, RoundingMode.HALF_UP),
                    Instant.now().plus(Duration.ofDays(FEE_DUE_DAYS)), actorId);
            feeInvoiceId = bill.invoiceId();
        }
        String before = request.getStatus();
        request.resolve(input.resolution().trim(), feeInvoiceId);
        Map<String, Object> newValue = new HashMap<>();
        newValue.put("status", request.getStatus());
        newValue.put("newKeyQuantity", replaceKey ? input.newKeyQuantity() : 0);
        newValue.put("feeInvoiceId", feeInvoiceId);
        audit(AuditAction.SUPPORT_REQUEST_RESOLVED, id, Map.of("status", before), newValue, null, actorId);
        Map<String, String> values = values(request);
        values.put("resolution", request.getResolution());
        values.put("feeNote", charge ? ". Phí dịch vụ " + fee.toPlainString()
                + "đ — thanh toán trong mục Hoá đơn." : "");
        notify(request.getCustomerId(), NotificationRecipient.CUSTOMER, NotificationType.SUPPORT_RESOLVED, values,
                orderIdOf(request));
        return view(request);
    }

    // ------------------------------------------------------------------ internals

    /**
     * {@code fee.lost_key × số chìa mất}: thay chìa chuyển cả bộ chìa đang {@code Active} sang {@code Lost}, nên số
     * chìa mất = {@code quantity} của bộ đó. Không có bộ chìa đang dùng → 0 (không thu).
     */
    private BigDecimal lostKeyFee(Long contractId) {
        int lost = contracts.accessKeys(contractId).stream()
                .filter(k -> "PhysicalKey".equals(k.accessType()) && "Active".equals(k.status()))
                .mapToInt(k -> k.quantity() == null ? 0 : k.quantity())
                .sum();
        return pricingQuery.decimalPolicy(POLICY_LOST_KEY).multiply(BigDecimal.valueOf(lost));
    }

    private Long orderIdOf(SupportRequest request) {
        return request.getContractId() == null ? null
                : contracts.find(request.getContractId()).map(ContractSnapshot::orderId).orElse(null);
    }

    private Map<String, String> values(SupportRequest request) {
        Map<String, String> values = new HashMap<>();
        values.put("requestId", String.valueOf(request.getId()));
        values.put("category", request.getCategory());
        values.put("title", request.getTitle());
        return values;
    }

    private void notify(Long accountId, NotificationRecipient recipient, NotificationType type,
                        Map<String, String> values, Long orderId) {
        notificationService.notify(accountId, accountQuery.requireEmailById(accountId), recipient, type, values,
                orderId);
    }

    private String staffName(Long staffId) {
        return jdbc.query("select coalesce(name, email) from accounts where id = ?", (rs, i) -> rs.getString(1),
                staffId).stream().findFirst().orElse("");
    }

    private SupportView view(SupportRequest r) {
        requests.flush();
        Map<String, Object> row = jdbc.queryForMap("""
                select c.name as customer_name, c.email as customer_email, f.name as facility_name,
                       s.name as staff_name, k.code as contract_code, u.code as unit_code
                from support_requests r
                join accounts c on c.id = r.customer_id
                join facilities f on f.id = r.facility_id
                left join accounts s on s.id = r.assigned_staff_id
                left join rental_contracts k on k.id = r.contract_id
                left join storage_units u on u.id = k.unit_id
                where r.id = ?""", r.getId());
        ContractBill fee = r.getFeeInvoiceId() == null ? null : invoices.bill(r.getFeeInvoiceId()).orElse(null);
        return new SupportView(r.getId(), r.getCustomerId(), (String) row.get("customer_name"),
                (String) row.get("customer_email"), r.getFacilityId(), (String) row.get("facility_name"),
                r.getContractId(), (String) row.get("contract_code"), (String) row.get("unit_code"), r.getCategory(),
                r.getTitle(), r.getDescription(), r.getPhotos() == null ? List.of() : r.getPhotos(), r.getStatus(),
                r.getAssignedStaffId(), (String) row.get("staff_name"), r.getResolution(), fee, r.getCreatedAt(),
                r.getAssignedAt(), r.getResolvedAt());
    }

    private void audit(AuditAction action, Long id, Object oldValue, Object newValue, String reason, Long actorId) {
        auditRecorder.record(new AuditEntry(action, AuditEntityType.SUPPORT_REQUEST, String.valueOf(id), oldValue,
                newValue, reason, actorId));
    }
}
