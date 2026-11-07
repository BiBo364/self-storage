package vn.lemar.selfstorage.tenancy.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.lemar.selfstorage.audit.application.AuditEntry;
import vn.lemar.selfstorage.audit.application.AuditRecorder;
import vn.lemar.selfstorage.audit.domain.AuditAction;
import vn.lemar.selfstorage.audit.domain.AuditEntityType;
import vn.lemar.selfstorage.booking.application.ContractInvoiceService;
import vn.lemar.selfstorage.booking.application.ContractInvoiceService.ContractBill;
import vn.lemar.selfstorage.booking.application.HandoverOrderService;
import vn.lemar.selfstorage.booking.application.OrderNotificationService;
import vn.lemar.selfstorage.booking.application.ReturnAppointmentService;
import vn.lemar.selfstorage.booking.application.ReturnAppointmentService.AppointmentState;
import vn.lemar.selfstorage.booking.application.ReturnAppointmentService.ReturnAppointment;
import vn.lemar.selfstorage.handover.application.RentalContractService;
import vn.lemar.selfstorage.handover.application.RentalContractService.ContractSnapshot;
import vn.lemar.selfstorage.identity.application.AccountQueryService;
import vn.lemar.selfstorage.identity.application.Access;
import vn.lemar.selfstorage.identity.application.CurrentAccountProvider;
import vn.lemar.selfstorage.identity.application.FacilityStaffQuery;
import vn.lemar.selfstorage.notification.application.NotificationRecipient;
import vn.lemar.selfstorage.notification.application.NotificationService;
import vn.lemar.selfstorage.notification.application.NotificationType;
import vn.lemar.selfstorage.pricing.application.PricingQuery;
import vn.lemar.selfstorage.tenancy.domain.ReturnRequest;
import vn.lemar.selfstorage.tenancy.repository.ReturnRequestRepository;

/**
 * Flow 3 — kho đang thuê: xem, gia hạn, yêu cầu trả kho; FM phân công FS cho buổi trả kho.
 *
 * <p>Thứ tự khoá mọi thao tác ghi theo hợp đồng: đơn → hợp đồng → yêu cầu trả kho / hoá đơn (cùng chiều chốt thanh
 * toán, cron quá hạn và quyết toán trả kho).
 */
@Service
public class TenancyService {

    static final String POLICY_MAX_MONTHS = "extension.max_months";
    static final String POLICY_INVOICE_DUE_DAYS = "extension.invoice_due_days";
    static final String PERM_ASSIGN = "appointment.assign";
    static final String PERM_READ = "rental_order.read";
    static final int MAX_PREFERRED_DAYS = 30;
    static final String MSG_NOT_FOUND = "Không tìm thấy hợp đồng thuê";
    static final String MSG_RETURN_NOT_FOUND = "Không tìm thấy yêu cầu trả kho";

    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy").withZone(ZONE);

    private final RentalContractService contracts;
    private final ContractInvoiceService invoices;
    private final HandoverOrderService orders;
    private final ReturnAppointmentService returnAppointments;
    private final OrderNotificationService orderNotifications;
    private final NotificationService notificationService;
    private final AccountQueryService accountQuery;
    private final ReturnRequestRepository returnRequests;
    private final FacilityStaffQuery staffQuery;
    private final PricingQuery pricingQuery;
    private final Access access;
    private final CurrentAccountProvider currentAccount;
    private final AuditRecorder auditRecorder;

    TenancyService(RentalContractService contracts, ContractInvoiceService invoices, HandoverOrderService orders,
                   ReturnAppointmentService returnAppointments, OrderNotificationService orderNotifications,
                   NotificationService notificationService, AccountQueryService accountQuery,
                   ReturnRequestRepository returnRequests, FacilityStaffQuery staffQuery, PricingQuery pricingQuery,
                   Access access, CurrentAccountProvider currentAccount, AuditRecorder auditRecorder) {
        this.contracts = contracts;
        this.invoices = invoices;
        this.orders = orders;
        this.returnAppointments = returnAppointments;
        this.orderNotifications = orderNotifications;
        this.notificationService = notificationService;
        this.accountQuery = accountQuery;
        this.returnRequests = returnRequests;
        this.staffQuery = staffQuery;
        this.pricingQuery = pricingQuery;
        this.access = access;
        this.currentAccount = currentAccount;
        this.auditRecorder = auditRecorder;
    }

    /** Kho đang thuê của khách. */
    public record RentalView(Long contractId, String contractCode, Long orderId, Long unitId, String unitCode,
                             Long facilityId, String status, BigDecimal monthlyPrice, BigDecimal depositAmount,
                             int period, LocalDate startDate, LocalDate endDate, long daysLeft, boolean overdue,
                             List<ContractBill> invoices, List<RentalContractService.AccessKeySnapshot> accessKeys,
                             ReturnRequestView returnRequest, boolean canExtend, boolean canRequestReturn) {
    }

    /**
     * Yêu cầu trả kho.
     *
     * @param appointmentArrivedAt mốc FS xác nhận khách đến buổi trả kho; {@code null} = chưa đến / chưa có lịch
     */
    public record ReturnRequestView(Long id, Long contractId, String unitCode, Long facilityId, LocalDate preferredDate,
                                    String note, String status, Long assignedStaffId, Long appointmentId,
                                    Instant appointmentStart, String cancelReason, Instant createdAt,
                                    Instant appointmentArrivedAt) {
    }

    // ------------------------------------------------------------------ Customer

    @Transactional(readOnly = true)
    public List<RentalView> rentals(Long customerId) {
        return contracts.activeOfCustomer(customerId).stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public RentalView rental(Long contractId, Long customerId) {
        return view(owned(contractId, customerId));
    }

    /** Gia hạn {@code months} tháng → hoá đơn {@code Extension}; thanh toán xong mới cộng kỳ thuê. */
    @Transactional
    public ContractBill requestExtension(Long contractId, Long customerId, int months) {
        int max = pricingQuery.intPolicy(POLICY_MAX_MONTHS);
        if (months < 1 || months > max) {
            throw TenancyException.invalid("Số tháng gia hạn phải từ 1 đến " + max);
        }
        ContractSnapshot contract = lockOwned(contractId, customerId);
        if (returnRequests.findOpenByContractId(contractId).isPresent()) {
            throw TenancyException.conflict("Hợp đồng đang có yêu cầu trả kho — không gia hạn được");
        }
        if (invoices.hasUnpaid(contractId, "Extension")) {
            throw TenancyException.conflict("Đã có hoá đơn gia hạn chờ thanh toán");
        }
        BigDecimal amount = contract.monthlyPrice().multiply(BigDecimal.valueOf(months))
                .setScale(2, RoundingMode.HALF_UP);
        Instant due = Instant.now().plus(Duration.ofDays(pricingQuery.intPolicy(POLICY_INVOICE_DUE_DAYS)));
        return invoices.issue(contract.orderId(), contractId, customerId, "Extension", "EXT",
                "Gia hạn " + months + " tháng — hợp đồng " + contract.code(),
                "Gia hạn thuê thêm " + months + " tháng từ ngày " + DATE.format(contract.endDate().plusDays(1)) + ".",
                amount, due, customerId);
    }

    /** Hoá đơn gia hạn {@code Paid} → cộng kỳ thuê (gọi từ listener, cùng transaction chốt thanh toán). */
    @Transactional
    public void onExtensionPaid(Long contractId, Long invoiceId) {
        ContractSnapshot contract = contracts.lock(contractId).orElse(null);
        ContractBill bill = invoices.bill(invoiceId).orElse(null);
        if (contract == null || bill == null || !contract.active()) {
            return;
        }
        int months = bill.amount().divide(contract.monthlyPrice(), 0, RoundingMode.HALF_UP).intValue();
        if (months < 1) {
            return;
        }
        ContractSnapshot extended = contracts.extend(contractId, months, null);
        orderNotifications.notifyCustomer(contract.orderId(), NotificationType.CONTRACT_EXTENDED,
                Map.of("contractCode", contract.code(), "months", String.valueOf(months),
                        "endDate", DATE.format(extended.endDate())));
    }

    @Transactional
    public ReturnRequestView requestReturn(Long contractId, Long customerId, LocalDate preferredDate, String note) {
        LocalDate today = LocalDate.now(ZONE);
        if (preferredDate == null || preferredDate.isBefore(today)
                || preferredDate.isAfter(today.plusDays(MAX_PREFERRED_DAYS))) {
            throw TenancyException.invalid("Ngày trả kho phải trong " + MAX_PREFERRED_DAYS + " ngày tới");
        }
        if (note != null && note.length() > 1000) {
            throw TenancyException.invalid("Ghi chú tối đa 1000 ký tự");
        }
        ContractSnapshot contract = lockOwned(contractId, customerId);
        if (returnRequests.findOpenByContractId(contractId).isPresent()) {
            throw TenancyException.conflict("Hợp đồng đã có yêu cầu trả kho đang xử lý");
        }
        ReturnRequest request = returnRequests.save(ReturnRequest.open(contractId, customerId, contract.facilityId(),
                preferredDate, note == null || note.isBlank() ? null : note.trim()));
        audit(AuditAction.RETURN_REQUESTED, request.getId(), null,
                Map.of("status", request.getStatus(), "contractId", contractId, "preferredDate",
                        preferredDate.toString()), null, customerId);
        orderNotifications.notifyFacilityManager(contract.orderId(), NotificationType.RETURN_REQUESTED,
                Map.of("unitCode", unitCode(contract), "contractCode", contract.code(),
                        "preferredDate", DATE.format(preferredDate)));
        return returnView(request);
    }

    /** Khách huỷ yêu cầu trả kho khi buổi trả kho chưa bắt đầu (FS chưa xác nhận khách đến). */
    @Transactional
    public ReturnRequestView cancelReturn(Long returnRequestId, Long customerId) {
        ReturnRequest peek = returnRequests.findById(returnRequestId)
                .filter(r -> r.getCustomerId().equals(customerId))
                .orElseThrow(() -> TenancyException.notFound(MSG_RETURN_NOT_FOUND));
        ContractSnapshot contract = lockContractWithOrder(peek.getContractId());
        ReturnRequest request = returnRequests.findByIdForUpdate(returnRequestId).orElseThrow();
        if (!request.isOpen()) {
            throw TenancyException.conflict("Yêu cầu trả kho đã đóng");
        }
        if (request.getAppointmentId() != null) {
            AppointmentState state = returnAppointments.state(request.getAppointmentId()).orElse(null);
            if (state != null && state.arrivedAt() != null) {
                throw TenancyException.conflict("Buổi trả kho đã bắt đầu — không huỷ được");
            }
            returnAppointments.cancel(request.getAppointmentId(), "Khách huỷ yêu cầu trả kho", customerId);
        }
        String before = request.getStatus();
        request.cancel("Khách huỷ yêu cầu trả kho");
        audit(AuditAction.RETURN_CANCELED, request.getId(), Map.of("status", before),
                Map.of("status", request.getStatus()), request.getCancelReason(), customerId);
        return returnView(request, contract);
    }

    @Transactional(readOnly = true)
    public List<ReturnRequestView> myReturns(Long customerId) {
        return returnRequests.findByCustomerIdOrderByIdDesc(customerId).stream().map(this::returnView).toList();
    }

    // ------------------------------------------------------------------ FM / FS

    /** FM: yêu cầu trả kho đang mở của cơ sở. */
    @Transactional(readOnly = true)
    public List<ReturnRequestView> facilityReturns(Long facilityId) {
        access.can(PERM_READ, facilityId);
        return returnRequests.findByFacilityIdAndStatusInOrderByIdAsc(facilityId,
                List.of(ReturnRequest.PENDING, ReturnRequest.ASSIGNED)).stream().map(this::returnView).toList();
    }

    /** FS: buổi trả kho được phân cho mình. */
    @Transactional(readOnly = true)
    public List<ReturnRequestView> staffReturns() {
        return returnRequests.findByAssignedStaffIdAndStatusOrderByIdAsc(currentAccount.getCurrentAccountId(),
                ReturnRequest.ASSIGNED).stream().map(this::returnView).toList();
    }

    /**
     * FM phân công FS → lịch {@code RETURN} trong khung trống đầu tiên từ ngày khách chọn. Phân công lại được khi lịch
     * cũ chưa bắt đầu (lịch cũ bị huỷ).
     */
    @Transactional
    public ReturnRequestView assign(Long returnRequestId, Long staffId) {
        ReturnRequest peek = returnRequests.findById(returnRequestId)
                .orElseThrow(() -> TenancyException.notFound(MSG_RETURN_NOT_FOUND));
        access.can(PERM_ASSIGN, peek.getFacilityId());
        if (staffId == null || !staffQuery.isActiveStaffOf(staffId, peek.getFacilityId())) {
            throw TenancyException.invalid("FS không thuộc cơ sở hoặc không còn hoạt động");
        }
        Long actorId = currentAccount.getCurrentAccountId();
        ContractSnapshot contract = lockContractWithOrder(peek.getContractId());
        ReturnRequest request = returnRequests.findByIdForUpdate(returnRequestId).orElseThrow();
        if (!request.isOpen()) {
            throw TenancyException.conflict("Yêu cầu trả kho đã đóng");
        }
        if (request.getAppointmentId() != null) {
            AppointmentState state = returnAppointments.state(request.getAppointmentId()).orElse(null);
            if (state != null && state.arrivedAt() != null) {
                throw TenancyException.conflict("Buổi trả kho đã bắt đầu — không phân công lại được");
            }
            returnAppointments.cancel(request.getAppointmentId(), "FM phân công lại buổi trả kho", actorId);
        }
        ReturnAppointment appointment = returnAppointments.book(contract.orderId(), contract.customerId(),
                request.getFacilityId(), staffId, request.getPreferredDate(), actorId);
        String before = request.getStatus();
        request.assign(staffId, appointment.appointmentId());
        audit(AuditAction.RETURN_ASSIGNED, request.getId(), Map.of("status", before),
                Map.of("status", request.getStatus(), "staffId", staffId, "appointmentId",
                        appointment.appointmentId()), null, actorId);

        Map<String, Object> values = new HashMap<>();
        values.put("unitCode", unitCode(contract));
        values.put("appointmentTime", TIME.format(appointment.startAt()));
        orderNotifications.notifyCustomer(contract.orderId(), NotificationType.RETURN_SCHEDULED, values);
        notificationService.notify(staffId, accountQuery.requireEmailById(staffId), NotificationRecipient.FS,
                NotificationType.RETURN_SCHEDULED, values, contract.orderId());
        return returnView(request, contract);
    }

    // ------------------------------------------------------------------ public API cho checkout / overdue

    /** Yêu cầu trả kho đang mở của hợp đồng (overdue: ngừng tính phí ở ngày buổi trả kho khách đã đến). */
    @Transactional(readOnly = true)
    public java.util.Optional<ReturnRequestView> openReturnOf(Long contractId) {
        return returnRequests.findOpenByContractId(contractId).map(this::returnView);
    }

    @Transactional(readOnly = true)
    public java.util.Optional<ReturnRequestView> returnRequest(Long returnRequestId) {
        return returnRequests.findById(returnRequestId).map(this::returnView);
    }

    /** Checkout: trả kho hoàn tất → {@code Completed} (caller đã khoá đơn → hợp đồng). */
    @Transactional
    public void completeReturn(Long returnRequestId) {
        ReturnRequest request = returnRequests.findByIdForUpdate(returnRequestId).orElseThrow();
        String before = request.getStatus();
        request.complete(Instant.now());
        audit(AuditAction.RETURN_COMPLETED, request.getId(), Map.of("status", before),
                Map.of("status", request.getStatus()), null, null);
    }

    // ------------------------------------------------------------------ internals

    private ContractSnapshot owned(Long contractId, Long customerId) {
        return contracts.find(contractId)
                .filter(c -> c.customerId().equals(customerId))
                .orElseThrow(() -> TenancyException.notFound(MSG_NOT_FOUND));
    }

    private ContractSnapshot lockOwned(Long contractId, Long customerId) {
        owned(contractId, customerId);
        ContractSnapshot contract = lockContractWithOrder(contractId);
        if (!contract.active()) {
            throw TenancyException.conflict("Hợp đồng không còn hiệu lực");
        }
        return contract;
    }

    /** Khoá đơn rồi hợp đồng. */
    private ContractSnapshot lockContractWithOrder(Long contractId) {
        ContractSnapshot peek = contracts.find(contractId)
                .orElseThrow(() -> TenancyException.notFound(MSG_NOT_FOUND));
        orders.lock(peek.orderId());
        return contracts.lock(contractId).orElseThrow();
    }

    private RentalView view(ContractSnapshot c) {
        LocalDate today = LocalDate.now(ZONE);
        long daysLeft = ChronoUnit.DAYS.between(today, c.endDate());
        ReturnRequestView open = returnRequests.findOpenByContractId(c.id()).map(r -> returnView(r, c)).orElse(null);
        List<ContractBill> bills = invoices.bills(c.id());
        boolean extensionPending = bills.stream().anyMatch(b -> b.unpaid() && "Extension".equals(b.type()));
        return new RentalView(c.id(), c.code(), c.orderId(), c.unitId(), unitCode(c), c.facilityId(), c.status(),
                c.monthlyPrice(), c.depositAmount(), c.period(), c.startDate(), c.endDate(), daysLeft, daysLeft < 0,
                bills, contracts.accessKeys(c.id()), open, c.active() && open == null && !extensionPending,
                c.active() && open == null);
    }

    private ReturnRequestView returnView(ReturnRequest r) {
        return returnView(r, contracts.find(r.getContractId()).orElse(null));
    }

    private ReturnRequestView returnView(ReturnRequest r, ContractSnapshot contract) {
        AppointmentState state = r.getAppointmentId() == null ? null
                : returnAppointments.state(r.getAppointmentId()).orElse(null);
        return new ReturnRequestView(r.getId(), r.getContractId(), contract == null ? null : unitCode(contract),
                r.getFacilityId(), r.getPreferredDate(), r.getNote(), r.getStatus(), r.getAssignedStaffId(),
                r.getAppointmentId(), state == null ? null : state.startAt(), r.getCancelReason(), r.getCreatedAt(),
                state == null ? null : state.arrivedAt());
    }

    private String unitCode(ContractSnapshot contract) {
        return contracts.unitCode(contract.id()).orElse("");
    }

    private void audit(AuditAction action, Long entityId, Object oldValue, Object newValue, String reason,
                       Long actorId) {
        auditRecorder.record(new AuditEntry(action, AuditEntityType.RETURN_REQUEST, String.valueOf(entityId),
                oldValue, newValue, reason, actorId));
    }
}
