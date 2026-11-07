package vn.lemar.selfstorage.handover.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.lemar.selfstorage.audit.application.AuditEntry;
import vn.lemar.selfstorage.audit.application.AuditRecorder;
import vn.lemar.selfstorage.audit.domain.AuditAction;
import vn.lemar.selfstorage.audit.domain.AuditEntityType;
import vn.lemar.selfstorage.handover.domain.RentalContract;
import vn.lemar.selfstorage.handover.domain.RentalContractStatus;
import vn.lemar.selfstorage.handover.domain.UnitAccessKey;
import vn.lemar.selfstorage.handover.repository.HandoverReadRepository;
import vn.lemar.selfstorage.handover.repository.RentalContractRepository;
import vn.lemar.selfstorage.handover.repository.UnitAccessKeyRepository;

/**
 * Public API hợp đồng thuê cho các module sau bàn giao: tenancy (kho đang thuê, gia hạn), checkout (trả kho), overdue
 * (quá hạn), support. Handover vẫn là module duy nhất ghi {@code rental_contracts} / {@code unit_access_keys}.
 */
@Service
public class RentalContractService {

    private final RentalContractRepository contracts;
    private final UnitAccessKeyRepository accessKeys;
    private final HandoverReadRepository readRepository;
    private final AuditRecorder auditRecorder;

    RentalContractService(RentalContractRepository contracts, UnitAccessKeyRepository accessKeys,
                          HandoverReadRepository readRepository, AuditRecorder auditRecorder) {
        this.contracts = contracts;
        this.accessKeys = accessKeys;
        this.readRepository = readRepository;
        this.auditRecorder = auditRecorder;
    }

    /** Ảnh chụp hợp đồng. */
    public record ContractSnapshot(Long id, String code, Long orderId, Long customerId, Long unitId, Long facilityId,
                                   String status, BigDecimal monthlyPrice, BigDecimal depositAmount, int period,
                                   LocalDate startDate, LocalDate endDate) {

        public boolean active() {
            return RentalContractStatus.ACTIVE.dbValue().equals(status);
        }
    }

    /** Quyền truy cập đã cấp. */
    public record AccessKeySnapshot(Long id, String accessType, Integer quantity, String status) {
    }

    /** Kết quả thu hồi quyền truy cập khi trả kho. */
    public record RevokeResult(int physicalKeysIssued, int lostKeys) {
    }

    @Transactional(readOnly = true)
    public Optional<ContractSnapshot> find(Long contractId) {
        return contracts.findById(contractId).map(this::snapshot);
    }

    /** Khoá row hợp đồng (caller đã khoá đơn nếu cần). */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<ContractSnapshot> lock(Long contractId) {
        return contracts.findByIdForUpdate(contractId).map(this::snapshot);
    }

    @Transactional(readOnly = true)
    public List<ContractSnapshot> activeOfCustomer(Long customerId) {
        return contracts.findByCustomerIdAndStatusOrderByEndDateAsc(customerId, RentalContractStatus.ACTIVE).stream()
                .map(this::snapshot).toList();
    }

    /** Hợp đồng đang thuê đã qua {@code end_date} (trước {@code date}). */
    @Transactional(readOnly = true)
    public List<Long> activeEndedBefore(LocalDate date) {
        return contracts.findByStatusAndEndDateBefore(RentalContractStatus.ACTIVE, date).stream()
                .map(RentalContract::getId).toList();
    }

    /** Hợp đồng đang thuê hết hạn trong {@code [from, to]}. */
    @Transactional(readOnly = true)
    public List<Long> activeEndingBetween(LocalDate from, LocalDate to) {
        return contracts.findByStatusAndEndDateBetween(RentalContractStatus.ACTIVE, from, to).stream()
                .map(RentalContract::getId).toList();
    }

    @Transactional(readOnly = true)
    public List<AccessKeySnapshot> accessKeys(Long contractId) {
        return accessKeys.findByContractIdOrderByIdAsc(contractId).stream()
                .map(k -> new AccessKeySnapshot(k.getId(), k.getAccessType(), k.getQuantity(), k.getStatus()))
                .toList();
    }

    /** Gia hạn {@code months} tháng (hoá đơn gia hạn đã {@code Paid}); audit {@code CONTRACT_EXTENDED}. */
    @Transactional(propagation = Propagation.MANDATORY)
    public ContractSnapshot extend(Long contractId, int months, Long actorAccountId) {
        RentalContract contract = contracts.findByIdForUpdate(contractId).orElseThrow();
        LocalDate before = contract.getEndDate();
        contract.extend(months);
        audit(AuditAction.CONTRACT_EXTENDED, AuditEntityType.RENTAL_CONTRACT, contractId,
                Map.of("endDate", before.toString()),
                Map.of("endDate", contract.getEndDate().toString(), "months", months, "period", contract.getPeriod()),
                actorAccountId);
        return snapshot(contract);
    }

    /** Trả kho hoàn tất: {@code Active -> Ended}; audit {@code CONTRACT_ENDED}. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void end(Long contractId, Long actorAccountId) {
        RentalContract contract = contracts.findByIdForUpdate(contractId).orElseThrow();
        contract.end();
        audit(AuditAction.CONTRACT_ENDED, AuditEntityType.RENTAL_CONTRACT, contractId,
                Map.of("status", RentalContractStatus.ACTIVE.dbValue()),
                Map.of("status", contract.getStatus().dbValue()), actorAccountId);
    }

    /**
     * Thu hồi mọi quyền truy cập đang {@code Active} khi trả kho: mã số → {@code Revoked}; khóa cơ thu đủ chìa →
     * {@code Revoked}, thiếu chìa → {@code Lost}.
     *
     * @param returnedKeys số chìa FS thu lại (null = không có khóa cơ)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public RevokeResult revokeAll(Long contractId, Integer returnedKeys, Long actorAccountId) {
        Instant now = Instant.now();
        int issued = 0;
        int lost = 0;
        for (UnitAccessKey key : accessKeys.findActiveByContractForUpdate(contractId)) {
            if (UnitAccessKey.PHYSICAL_KEY.equals(key.getAccessType())) {
                issued += key.getQuantity() == null ? 0 : key.getQuantity();
                int returned = returnedKeys == null ? 0 : returnedKeys;
                lost = Math.max(0, issued - returned);
                if (lost > 0) {
                    key.markLost(now);
                } else {
                    key.revoke(now);
                }
            } else {
                key.revoke(now);
            }
            audit(AuditAction.ACCESS_KEY_REVOKED, AuditEntityType.UNIT_ACCESS_KEY, key.getId(),
                    Map.of("status", UnitAccessKey.ACTIVE),
                    Map.of("status", key.getStatus(), "reason", "Trả kho"), actorAccountId);
        }
        return new RevokeResult(issued, lost);
    }

    /**
     * Hỗ trợ mất chìa: chìa đang {@code Active} → {@code Lost}, cấp bộ chìa mới {@code quantity} (hợp đồng
     * {@code Active}). Audit {@code ACCESS_KEY_REVOKED} + {@code ACCESS_KEY_ISSUED}.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void replacePhysicalKey(Long contractId, int quantity, Long actorAccountId) {
        RentalContract contract = contracts.findByIdForUpdate(contractId).orElseThrow();
        if (contract.getStatus() != RentalContractStatus.ACTIVE) {
            throw new IllegalStateException("Hợp đồng " + contractId + " không còn hiệu lực");
        }
        Instant now = Instant.now();
        accessKeys.findActiveForUpdate(contractId, UnitAccessKey.PHYSICAL_KEY).ifPresent(old -> {
            old.markLost(now);
            accessKeys.flush();
            audit(AuditAction.ACCESS_KEY_REVOKED, AuditEntityType.UNIT_ACCESS_KEY, old.getId(),
                    Map.of("status", UnitAccessKey.ACTIVE),
                    Map.of("status", old.getStatus(), "reason", "Khách báo mất chìa"), actorAccountId);
        });
        UnitAccessKey key = accessKeys.save(UnitAccessKey.physicalKey(contract.getUnitId(), contractId, quantity, now));
        audit(AuditAction.ACCESS_KEY_ISSUED, AuditEntityType.UNIT_ACCESS_KEY, key.getId(), null,
                Map.of("accessType", key.getAccessType(), "quantity", quantity, "contractId", contractId),
                actorAccountId);
    }

    /** Mã khoang để hiển thị. */
    @Transactional(readOnly = true)
    public Optional<String> unitCode(Long contractId) {
        return readRepository.findContractDocument(contractId)
                .map(HandoverReadRepository.ContractDocumentRow::unitCode);
    }

    private ContractSnapshot snapshot(RentalContract c) {
        Long facilityId = readRepository.findContractFacilityId(c.getId()).orElse(null);
        return new ContractSnapshot(c.getId(), c.getCode(), c.getOrderId(), c.getCustomerId(), c.getUnitId(),
                facilityId, c.getStatus().dbValue(), c.getMonthlyPrice(), c.getDepositAmount(), c.getPeriod(),
                c.getStartDate(), c.getEndDate());
    }

    private void audit(AuditAction action, AuditEntityType type, Long entityId, Object oldValue, Object newValue,
                       Long actorAccountId) {
        auditRecorder.record(new AuditEntry(action, type, String.valueOf(entityId), oldValue, newValue, null,
                actorAccountId));
    }
}
