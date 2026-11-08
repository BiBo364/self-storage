package vn.lemar.selfstorage.checkout.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.lemar.selfstorage.audit.application.AuditEntry;
import vn.lemar.selfstorage.audit.application.AuditRecorder;
import vn.lemar.selfstorage.audit.domain.AuditAction;
import vn.lemar.selfstorage.audit.domain.AuditEntityType;
import vn.lemar.selfstorage.booking.application.ContractInvoiceService.ContractBill;
import vn.lemar.selfstorage.booking.application.ContractInvoiceService;
import vn.lemar.selfstorage.booking.application.HandoverOrderService;
import vn.lemar.selfstorage.booking.application.OrderNotificationService;
import vn.lemar.selfstorage.booking.application.ReturnAppointmentService.AppointmentState;
import vn.lemar.selfstorage.booking.application.ReturnAppointmentService;
import vn.lemar.selfstorage.checkout.domain.CheckoutRecord;
import vn.lemar.selfstorage.checkout.repository.CheckoutRecordRepository;
import vn.lemar.selfstorage.facility.application.StorageUnitService;
import vn.lemar.selfstorage.handover.application.RentalContractService.ContractSnapshot;
import vn.lemar.selfstorage.handover.application.RentalContractService.RevokeResult;
import vn.lemar.selfstorage.handover.application.RentalContractService;
import vn.lemar.selfstorage.identity.application.Access;
import vn.lemar.selfstorage.identity.application.CurrentAccountProvider;
import vn.lemar.selfstorage.media.MediaAttachments;
import vn.lemar.selfstorage.notification.application.NotificationType;
import vn.lemar.selfstorage.pricing.application.PricingQuery;
import vn.lemar.selfstorage.tenancy.application.TenancyException;
import vn.lemar.selfstorage.tenancy.application.TenancyService.ReturnRequestView;
import vn.lemar.selfstorage.tenancy.application.TenancyService;

/**
 * Trả kho rút gọn (bản nháp Flow 2.a):
 * <ol>
 *   <li>FS phụ trách (lịch {@code RETURN} đã xác nhận khách đến) ghi kết quả kiểm tra — khoang còn đồ thì
 *       {@code PENDING_ITEMS}, kiểm lại trên cùng biên bản;</li>
 *   <li>chốt: thu hồi quyền truy cập (thiếu chìa → {@code Lost}), mỗi loại phí một hoá đơn {@code Penalty}
 *       ({@code INV-CLN} vệ sinh, {@code INV-DMG} hư hỏng, {@code INV-KEY} mất chìa, {@code INV-PEN} hoá đơn
 *       {@code Unpaid} của hợp đồng gộp vào), cọc đối trừ theo thứ tự đó — phần được cọc phủ {@code Paid} ngay. Dư →
 *       FM hoàn thủ công; thiếu → một hoá đơn {@code INV-PEN} phần còn thiếu, trả xong mới hoàn tất;</li>
 *   <li>hoàn tất: hợp đồng {@code Ended}, khoang {@code Maintenance} (mất chìa → bảo trì thủ công tới khi thay khoá),
 *       yêu cầu trả kho {@code Completed}.</li>
 * </ol>
 * Thứ tự khoá: đơn → hợp đồng → biên bản → hoá đơn.
 */
@Service
public class CheckoutService {

    static final String PERM_CHECKOUT = "unit_access_key.issue";
    static final String POLICY_CLEANING = "fee.cleaning";
    static final String POLICY_LOST_KEY = "fee.lost_key";
    static final String POLICY_DUE_DAYS = "checkout.invoice_due_days";
    static final String MSG_NOT_FOUND = "Không tìm thấy yêu cầu trả kho";
    static final String REASON_LOST_KEY_MAINTENANCE = "Bảo trì sau trả kho — thay khoá do mất chìa";
    private static final String PHYSICAL_KEY = "PhysicalKey";
    private static final String LOST = "Lost";

    private final CheckoutRecordRepository records;
    private final TenancyService tenancy;
    private final ReturnAppointmentService appointments;
    private final RentalContractService contracts;
    private final ContractInvoiceService invoices;
    private final HandoverOrderService orders;
    private final StorageUnitService storageUnits;
    private final OrderNotificationService notifications;
    private final PricingQuery pricingQuery;
    private final Access access;
    private final CurrentAccountProvider currentAccount;
    private final AuditRecorder auditRecorder;
    private final MediaAttachments mediaAttachments;

    CheckoutService(CheckoutRecordRepository records, TenancyService tenancy, ReturnAppointmentService appointments,
                    RentalContractService contracts, ContractInvoiceService invoices, HandoverOrderService orders,
                    StorageUnitService storageUnits, OrderNotificationService notifications,
                    PricingQuery pricingQuery, Access access, CurrentAccountProvider currentAccount,
                    AuditRecorder auditRecorder, MediaAttachments mediaAttachments) {
        this.records = records;
        this.tenancy = tenancy;
        this.appointments = appointments;
        this.contracts = contracts;
        this.invoices = invoices;
        this.orders = orders;
        this.storageUnits = storageUnits;
        this.notifications = notifications;
        this.pricingQuery = pricingQuery;
        this.access = access;
        this.currentAccount = currentAccount;
        this.auditRecorder = auditRecorder;
        this.mediaAttachments = mediaAttachments;
    }

    /** Kết quả kiểm tra FS nhập. */
    public record Inspection(boolean unitEmptied, boolean cleaningRequired, BigDecimal damageAmount,
                             String damages, Integer returnedKeys, List<String> photos, String note) {
    }

    /** Buổi trả kho: yêu cầu + biên bản + phí dự kiến. */
    public record CheckoutView(ReturnRequestView returnRequest, Instant arrivedAt, String result,
                               boolean unitEmptied, boolean cleaningRequired, BigDecimal damageAmount, String damages,
                               Integer returnedKeys, List<String> photos, String note, Instant inspectedAt,
                               BigDecimal depositAmount, BigDecimal cleaningFee, BigDecimal lostKeyFee,
                               int keysIssued, BigDecimal unpaidContractInvoices, BigDecimal totalCharges,
                               BigDecimal refundAmount, ContractBill shortfallInvoice, Instant completedAt) {
    }

    // ------------------------------------------------------------------ đọc

    @Transactional(readOnly = true)
    public CheckoutView forStaff(Long returnRequestId) {
        ReturnRequestView request = request(returnRequestId);
        access.can(PERM_CHECKOUT, request.facilityId());
        return view(request);
    }

    @Transactional(readOnly = true)
    public CheckoutView forCustomer(Long returnRequestId, Long customerId) {
        ReturnRequestView request = request(returnRequestId);
        ContractSnapshot contract = contracts.find(request.contractId()).orElseThrow();
        if (!contract.customerId().equals(customerId)) {
            throw TenancyException.notFound(MSG_NOT_FOUND);
        }
        return view(request);
    }

    // ------------------------------------------------------------------ FS

    @Transactional
    public CheckoutView inspect(Long returnRequestId, Inspection input) {
        if (input.damageAmount() != null && input.damageAmount().signum() < 0) {
            throw TenancyException.invalid("Phí hư hỏng không được âm");
        }
        if (input.returnedKeys() != null && input.returnedKeys() < 0) {
            throw TenancyException.invalid("Số chìa thu lại không được âm");
        }
        if (input.photos() != null && input.photos().size() > 20) {
            throw TenancyException.invalid("Tối đa 20 ảnh hiện trạng");
        }
        Locked locked = lockForStaff(returnRequestId);
        CheckoutRecord record = records.findByReturnRequestIdForUpdate(returnRequestId)
                .orElseGet(() -> records.save(CheckoutRecord.open(returnRequestId, locked.contract().id(),
                        locked.contract().unitId(), locked.request().appointmentId())));
        String before = record.getResult();
        record.inspect(input.unitEmptied(), input.cleaningRequired(),
                input.damageAmount() == null ? BigDecimal.ZERO : input.damageAmount().setScale(2, RoundingMode.HALF_UP),
                trim(input.damages()), input.returnedKeys(), input.photos(), trim(input.note()), Instant.now());
        // Ảnh FS vừa upload gắn vào biên bản → attach để cron dọn upload bỏ ngang không xoá.
        mediaAttachments.attach(locked.actorId(), input.photos());
        records.flush();
        Map<String, Object> newValue = new HashMap<>();
        newValue.put("result", record.getResult());
        newValue.put("unitEmptied", record.isUnitEmptied());
        newValue.put("cleaningRequired", record.isCleaningRequired());
        newValue.put("damageAmount", record.getDamageAmount());
        audit(AuditAction.CHECKOUT_INSPECTED, record.getId(), Map.of("result", before), newValue, locked.actorId());
        return view(locked.request());
    }

    /** Chốt biên bản và quyết toán tiền cọc. */
    @Transactional
    public CheckoutView confirm(Long returnRequestId) {
        Locked locked = lockForStaff(returnRequestId);
        CheckoutRecord record = records.findByReturnRequestIdForUpdate(returnRequestId)
                .orElseThrow(() -> TenancyException.conflict("Chưa có kết quả kiểm tra khoang"));
        if (!record.readyToConfirm()) {
            throw TenancyException.conflict(CheckoutRecord.PENDING_ITEMS.equals(record.getResult())
                    ? "Khoang còn tài sản — chưa chốt trả kho được" : "Biên bản trả kho đã chốt");
        }
        ContractSnapshot contract = locked.contract();
        Long actorId = locked.actorId();
        RevokeResult revoke = contracts.revokeAll(contract.id(), record.getReturnedKeyQuantity(), actorId);
        BigDecimal cleaning = record.isCleaningRequired() ? decimal(POLICY_CLEANING) : BigDecimal.ZERO;
        BigDecimal lostKeys = decimal(POLICY_LOST_KEY).multiply(BigDecimal.valueOf(revoke.lostKeys()));
        BigDecimal folded = invoices.foldUnpaidIntoSettlement(contract.id(), "Gộp vào quyết toán trả kho");
        // Mỗi loại phí một hoá đơn; cọc đối trừ lần lượt vệ sinh → hư hỏng → mất chìa → hoá đơn gộp.
        List<Charge> charges = List.of(
                new Charge(ContractInvoiceService.CLEANING_KIND, "Phí vệ sinh khi trả kho", cleaning),
                new Charge(ContractInvoiceService.DAMAGE_KIND, "Phí hư hỏng khi trả kho", record.getDamageAmount()),
                new Charge(ContractInvoiceService.LOST_KEY_KIND, "Phí mất chìa khi trả kho", lostKeys),
                new Charge(ContractInvoiceService.SETTLEMENT_KIND, "Hoá đơn chưa thanh toán gộp vào quyết toán",
                        folded));
        BigDecimal total = charges.stream().map(Charge::amount).reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal deposit = contract.depositAmount();
        BigDecimal remaining = deposit;
        for (Charge charge : charges) {
            BigDecimal covered = charge.amount().setScale(2, RoundingMode.HALF_UP).min(remaining);
            if (covered.signum() > 0) {
                invoices.settleByDeposit(contract.orderId(), contract.id(), contract.customerId(), charge.kind(),
                        covered, charge.title() + " (đối trừ cọc)", actorId);
                remaining = remaining.subtract(covered);
            }
        }
        String unitCode = contracts.unitCode(contract.id()).orElse("");
        if (total.compareTo(deposit) > 0) {
            BigDecimal shortfall = total.subtract(deposit);
            Instant due = Instant.now().plus(Duration.ofDays(pricingQuery.intPolicy(POLICY_DUE_DAYS)));
            ContractBill bill = invoices.issue(contract.orderId(), contract.id(), contract.customerId(), "Penalty",
                    ContractInvoiceService.SETTLEMENT_KIND, "Quyết toán trả kho — phần còn thiếu",
                    "Tổng phí trả kho vượt tiền cọc đã đóng.", shortfall, due, actorId);
            record.settle(deposit, total, BigDecimal.ZERO, bill.invoiceId(), Instant.now());
            notifications.notifyCustomer(contract.orderId(), NotificationType.CHECKOUT_PAYMENT_REQUIRED,
                    Map.of("unitCode", unitCode, "totalCharges", total.toPlainString(),
                            "depositAmount", deposit.toPlainString(), "shortfall", shortfall.toPlainString()));
        } else {
            record.settle(deposit, total, deposit.subtract(total), null, Instant.now());
            complete(record, contract, actorId);
        }
        return view(locked.request());
    }

    /** Hoá đơn phần còn thiếu {@code Paid} → hoàn tất trả kho (listener, cùng transaction chốt thanh toán). */
    @Transactional
    public void onShortfallPaid(Long invoiceId) {
        CheckoutRecord record = records.findByShortfallInvoiceIdForUpdate(invoiceId).orElse(null);
        if (record == null || !CheckoutRecord.AWAITING_PAYMENT.equals(record.getResult())) {
            return;
        }
        ContractSnapshot contract = contracts.lock(record.getContractId()).orElseThrow();
        complete(record, contract, null);
    }

    /**
     * Hoá đơn phần còn thiếu quá hạn mà khách không trả: khách đã dọn đồ và trả chìa, nên vẫn đóng trả kho (kết thúc
     * hợp đồng, khoang sang bảo trì) để khoang không bị kẹt. Hoá đơn giữ {@code Expired} làm chứng từ công nợ; FM được
     * báo để thu thủ công.
     */
    @Transactional
    public void onShortfallExpired(Long invoiceId) {
        CheckoutRecord record = records.findByShortfallInvoiceIdForUpdate(invoiceId).orElse(null);
        if (record == null || !CheckoutRecord.AWAITING_PAYMENT.equals(record.getResult())) {
            return;
        }
        ContractSnapshot contract = contracts.lock(record.getContractId()).orElseThrow();
        BigDecimal shortfall = record.getTotalCharges().subtract(record.getDepositAmount());
        complete(record, contract, null);
        notifications.notifyFacilityManager(contract.orderId(), NotificationType.CHECKOUT_SHORTFALL_UNPAID,
                Map.of("unitCode", contracts.unitCode(contract.id()).orElse(""), "contractCode", contract.code(),
                        "shortfall", shortfall.toPlainString()));
    }

    // ------------------------------------------------------------------ internals

    /** Một loại phí trả kho: tiền tố mã hoá đơn, tiêu đề, số tiền. */
    private record Charge(String kind, String title, BigDecimal amount) {
    }

    private void complete(CheckoutRecord record, ContractSnapshot contract, Long actorId) {
        Instant now = Instant.now();
        contracts.end(contract.id(), actorId);
        // Mất chìa: khoang bảo trì thủ công (không có maintenance_started_at) tới khi thay khoá — cron không tự mở lại,
        // FM mở lại tay.
        boolean lostKey = lostKeysAtCheckout(contract.id());
        boolean maintenance = lostKey ? storageUnits.startManualMaintenanceAfterReturn(contract.unitId())
                : storageUnits.startMaintenanceAfterReturn(contract.unitId());
        if (maintenance) {
            auditRecorder.record(new AuditEntry(AuditAction.STORAGE_UNIT_MAINTENANCE, AuditEntityType.STORAGE_UNIT,
                    String.valueOf(contract.unitId()), Map.of("status", "Rented"),
                    Map.of("status", "Maintenance", "reason", lostKey ? REASON_LOST_KEY_MAINTENANCE
                            : "Bảo trì sau trả kho"), null, actorId));
        }
        tenancy.completeReturn(record.getReturnRequestId());
        String before = record.getResult();
        record.complete(now);
        audit(AuditAction.CHECKOUT_COMPLETED, record.getId(), Map.of("result", before),
                Map.of("result", record.getResult(), "totalCharges", record.getTotalCharges(),
                        "refundAmount", record.getRefundAmount()), actorId);
        String refund = record.getRefundAmount() == null ? "0" : record.getRefundAmount().toPlainString();
        String unitCode = contracts.unitCode(contract.id()).orElse("");
        notifications.notifyCustomer(contract.orderId(), NotificationType.CHECKOUT_COMPLETED,
                Map.of("contractCode", contract.code(), "refundAmount", refund));
        if (record.getRefundAmount() != null && record.getRefundAmount().signum() > 0) {
            notifications.notifyFacilityManager(contract.orderId(), NotificationType.CHECKOUT_REFUND_REQUIRED,
                    Map.of("unitCode", unitCode, "contractCode", contract.code(), "refundAmount", refund));
        }
    }

    /**
     * Lúc chốt có thiếu chìa không — tính lại từ chìa của hợp đồng vì {@code complete} có thể chạy sau (phần thiếu
     * được trả / quá hạn). Chốt trả kho thu hồi bộ chìa đang {@code Active} (mỗi hợp đồng tối đa một bộ, thay chìa ở
     * hỗ trợ cấp bộ mới với id lớn hơn): bộ chìa mới nhất {@code Lost} ⇔ {@code revokeAll} báo thiếu chìa.
     */
    private boolean lostKeysAtCheckout(Long contractId) {
        return contracts.accessKeys(contractId).stream()
                .filter(k -> PHYSICAL_KEY.equals(k.accessType()))
                .reduce((first, second) -> second)
                .map(k -> LOST.equals(k.status()))
                .orElse(false);
    }

    private record Locked(ReturnRequestView request, ContractSnapshot contract, Long actorId) {
    }

    /** FS phụ trách buổi trả kho; khách đã đến; khoá đơn → hợp đồng. */
    private Locked lockForStaff(Long returnRequestId) {
        ReturnRequestView request = request(returnRequestId);
        access.can(PERM_CHECKOUT, request.facilityId());
        Long actorId = currentAccount.getCurrentAccountId();
        if (!actorId.equals(request.assignedStaffId())) {
            throw new TenancyException(TenancyException.Kind.FORBIDDEN, "Bạn không phải FS phụ trách buổi trả kho");
        }
        if (!"Assigned".equals(request.status())) {
            throw TenancyException.conflict("Yêu cầu trả kho không còn ở trạng thái đã phân công");
        }
        AppointmentState state = appointments.state(request.appointmentId()).orElse(null);
        if (state == null || state.arrivedAt() == null) {
            throw TenancyException.conflict("Chưa xác nhận khách đến buổi trả kho");
        }
        ContractSnapshot peek = contracts.find(request.contractId()).orElseThrow();
        orders.lock(peek.orderId());
        ContractSnapshot contract = contracts.lock(request.contractId()).orElseThrow();
        if (!contract.active()) {
            throw TenancyException.conflict("Hợp đồng không còn hiệu lực");
        }
        return new Locked(request, contract, actorId);
    }

    private ReturnRequestView request(Long returnRequestId) {
        return tenancy.returnRequest(returnRequestId).orElseThrow(() -> TenancyException.notFound(MSG_NOT_FOUND));
    }

    private CheckoutView view(ReturnRequestView request) {
        CheckoutRecord record = records.findByReturnRequestId(request.id()).orElse(null);
        ContractSnapshot contract = contracts.find(request.contractId()).orElseThrow();
        Instant arrivedAt = request.appointmentId() == null ? null
                : appointments.state(request.appointmentId()).map(AppointmentState::arrivedAt).orElse(null);
        int keysIssued = contracts.accessKeys(contract.id()).stream()
                .filter(k -> "PhysicalKey".equals(k.accessType()) && k.quantity() != null)
                .mapToInt(RentalContractService.AccessKeySnapshot::quantity).max().orElse(0);
        BigDecimal unpaid = invoices.bills(contract.id()).stream().filter(ContractBill::unpaid)
                .filter(b -> record == null || !b.invoiceId().equals(record.getShortfallInvoiceId()))
                .map(ContractBill::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        ContractBill shortfall = record == null || record.getShortfallInvoiceId() == null ? null
                : invoices.bill(record.getShortfallInvoiceId()).orElse(null);
        return new CheckoutView(request, arrivedAt, record == null ? null : record.getResult(),
                record != null && record.isUnitEmptied(), record != null && record.isCleaningRequired(),
                record == null ? BigDecimal.ZERO : record.getDamageAmount(),
                record == null ? null : record.getDamages(),
                record == null ? null : record.getReturnedKeyQuantity(),
                record == null || record.getPhotos() == null ? List.of() : record.getPhotos(),
                record == null ? null : record.getNote(), record == null ? null : record.getInspectedAt(),
                contract.depositAmount(), decimal(POLICY_CLEANING), decimal(POLICY_LOST_KEY), keysIssued, unpaid,
                record == null ? null : record.getTotalCharges(), record == null ? null : record.getRefundAmount(),
                shortfall, record == null ? null : record.getCompletedAt());
    }

    private BigDecimal decimal(String policy) {
        return pricingQuery.decimalPolicy(policy).setScale(2, RoundingMode.HALF_UP);
    }

    private void audit(AuditAction action, Long entityId, Object oldValue, Object newValue, Long actorId) {
        auditRecorder.record(new AuditEntry(action, AuditEntityType.CHECKOUT_RECORD, String.valueOf(entityId),
                oldValue, newValue, null, actorId));
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
