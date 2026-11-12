package vn.lemar.selfstorage.handover.application;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.lemar.selfstorage.booking.application.HandoverOrderService;
import vn.lemar.selfstorage.facility.application.FacilityAccess;
import vn.lemar.selfstorage.facility.application.FacilityAccessOptions;
import vn.lemar.selfstorage.handover.application.dto.HandoverView;
import vn.lemar.selfstorage.handover.application.exception.HandoverNotFoundException;
import vn.lemar.selfstorage.handover.domain.HandoverRecord;
import vn.lemar.selfstorage.handover.domain.HandoverResult;
import vn.lemar.selfstorage.handover.domain.RentalContract;
import vn.lemar.selfstorage.handover.domain.RentalContractStatus;
import vn.lemar.selfstorage.handover.repository.HandoverReadRepository;
import vn.lemar.selfstorage.handover.repository.HandoverReadRepository.HandoverRow;
import vn.lemar.selfstorage.handover.repository.HandoverRecordRepository;
import vn.lemar.selfstorage.handover.repository.RentalContractRepository;
import vn.lemar.selfstorage.handover.repository.UnitAccessKeyRepository;
import vn.lemar.selfstorage.identity.application.Access;
import vn.lemar.selfstorage.identity.application.CurrentAccountProvider;
import vn.lemar.selfstorage.pricing.application.PricingQuery;

/** Đọc phiên bàn giao cho FS ("Đơn đang bàn giao") và khách ("Kho của tôi" › Bàn giao). */
@Service
public class HandoverQueryService {

    static final String PERMISSION_READ = "rental_order.read";
    static final String MSG_NOT_FOUND = "Không tìm thấy phiên bàn giao";

    private final HandoverReadRepository readRepository;
    private final HandoverRecordRepository records;
    private final RentalContractRepository contracts;
    private final UnitAccessKeyRepository accessKeys;
    private final HandoverOrderService orders;
    private final FacilityAccess facilityAccess;
    private final PricingQuery pricingQuery;
    private final Access access;
    private final CurrentAccountProvider currentAccount;

    HandoverQueryService(HandoverReadRepository readRepository, HandoverRecordRepository records,
                         RentalContractRepository contracts, UnitAccessKeyRepository accessKeys,
                         HandoverOrderService orders, FacilityAccess facilityAccess, PricingQuery pricingQuery,
                         Access access, CurrentAccountProvider currentAccount) {
        this.readRepository = readRepository;
        this.records = records;
        this.contracts = contracts;
        this.accessKeys = accessKeys;
        this.orders = orders;
        this.facilityAccess = facilityAccess;
        this.pricingQuery = pricingQuery;
        this.access = access;
        this.currentAccount = currentAccount;
    }

    /** "Đơn đang bàn giao" của FS đang đăng nhập: phiên mở, khách đã đến. */
    @Transactional(readOnly = true)
    public List<HandoverView> staffQueue() {
        return readRepository.findOpenArrivedByStaff(currentAccount.getCurrentAccountId()).stream()
                .map(this::view).toList();
    }

    /** Chi tiết cho FS/FM của cơ sở. */
    @Transactional(readOnly = true)
    public HandoverView forStaff(Long handoverId) {
        HandoverRow row = readRepository.findById(handoverId)
                .orElseThrow(() -> new HandoverNotFoundException(MSG_NOT_FOUND));
        access.can(PERMISSION_READ, row.facilityId());
        return view(row);
    }

    /** Phiên bàn giao của khách — phiên đang mở trước. */
    @Transactional(readOnly = true)
    public List<HandoverView> forCustomer(Long customerId) {
        return readRepository.findByCustomer(customerId).stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public HandoverView forCustomer(Long handoverId, Long customerId) {
        HandoverRow row = readRepository.findById(handoverId)
                .filter(r -> r.customerId().equals(customerId))
                .orElseThrow(() -> new HandoverNotFoundException(MSG_NOT_FOUND));
        return view(row);
    }

    @Transactional(readOnly = true)
    public HandoverView view(Long handoverId) {
        return readRepository.findById(handoverId).map(this::view)
                .orElseThrow(() -> new HandoverNotFoundException(MSG_NOT_FOUND));
    }

    private HandoverView view(HandoverRow row) {
        RentalContract contract = HandoverResult.IN_PROGRESS.name().equals(row.result())
                || HandoverResult.COMPLETED.name().equals(row.result())
                ? contracts.findCurrentByOrderId(row.orderId()).orElse(null) : null;
        HandoverView.Invoice invoice = contract == null ? null : orders.firstMonthInvoice(contract.getId())
                .map(i -> new HandoverView.Invoice(i.invoiceId(), i.code(), i.status(), i.amount(), i.dueDate()))
                .orElse(null);
        List<HandoverView.AccessKey> keys = contract == null ? List.of()
                : accessKeys.findByContractIdOrderByIdAsc(contract.getId()).stream()
                        .map(k -> new HandoverView.AccessKey(k.getId(), k.getAccessType(), k.getQuantity(),
                                k.getStatus(), k.getIssuedAt()))
                        .toList();
        FacilityAccessOptions options = facilityAccess.accessOptions(row.facilityId())
                .orElse(new FacilityAccessOptions(false, false));
        return new HandoverView(row.id(), row.orderId(), row.appointmentId(), row.result(),
                step(row, contract, invoice), row.unitCode(), row.unitType(), row.facilityId(), row.facilityName(),
                row.facilityAddress(), row.customerId(), row.customerName(), row.customerEmail(),
                row.customerPhone(), row.appointmentDate(), row.appointmentStart(), row.appointmentEnd(),
                row.arrivedAt(), row.identityStatus(), row.identityVerifiedAt(), row.identityPhotos(),
                row.inspectionStatus(), row.unitInspectedAt(), row.inspectionNotes(), row.inspectionPhotos(),
                row.rejectReason(),
                row.dueAt(), row.completedAt(), row.requestedAccess(), row.customerConfirmedAt(),
                contract == null ? null : contract(contract), invoice, keys, options.keyAccess(),
                options.codeAccess(), records.countByOrderIdAndResult(row.orderId(), HandoverResult.REJECTED),
                pricingQuery.intPolicy(HandoverWorkflowService.POLICY_MAX_REJECTION));
    }

    private static String step(HandoverRow row, RentalContract contract, HandoverView.Invoice invoice) {
        if (!HandoverResult.IN_PROGRESS.name().equals(row.result())) {
            return row.result();
        }
        if (row.arrivedAt() == null) {
            return "WAITING_ARRIVAL";
        }
        if (!HandoverRecord.VERIFIED.equals(row.identityStatus())) {
            return "VERIFY_IDENTITY";
        }
        if (!HandoverRecord.AGREED.equals(row.inspectionStatus()) || contract == null) {
            return "INSPECTION";
        }
        if (contract.getStatus() == RentalContractStatus.DRAFT) {
            return contract.hasPendingOverride() ? "OVERRIDE_PENDING" : "SIGN_CONTRACT";
        }
        if (invoice == null || !"Paid".equals(invoice.status())) {
            return "PAY_FIRST_MONTH";
        }
        return row.requestedAccess() == null ? "CUSTOMER_CONFIRM" : "HAND_OVER_KEYS";
    }

    private static HandoverView.Contract contract(RentalContract c) {
        return new HandoverView.Contract(c.getId(), c.getCode(), c.getStatus().dbValue(), c.getTermsVersion(),
                c.getMonthlyPrice(), c.getDepositAmount(), c.getPeriod(), c.getStartDate(), c.getEndDate(),
                c.getStartDateOverrideRequested(), c.getStartDateOverrideReason(), c.getStartDateOverrideStatus(),
                c.getSignedAt(), c.getDocumentUrl(), c.getPdfUrl());
    }
}
