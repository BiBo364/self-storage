package vn.lemar.selfstorage.handover.application;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import vn.lemar.selfstorage.audit.application.AuditEntry;
import vn.lemar.selfstorage.audit.application.AuditRecorder;
import vn.lemar.selfstorage.audit.domain.AuditAction;
import vn.lemar.selfstorage.audit.domain.AuditEntityType;
import vn.lemar.selfstorage.booking.application.HandoverOrderService;
import vn.lemar.selfstorage.facility.application.StorageUnitService;
import vn.lemar.selfstorage.handover.application.exception.HandoverConflictException;
import vn.lemar.selfstorage.handover.domain.AccessChoice;
import vn.lemar.selfstorage.handover.domain.HandoverRecord;
import vn.lemar.selfstorage.handover.domain.RentalContract;
import vn.lemar.selfstorage.handover.domain.RentalContractStatus;
import vn.lemar.selfstorage.handover.domain.UnitAccessKey;
import vn.lemar.selfstorage.handover.repository.UnitAccessKeyRepository;

/**
 * Spec 2.4 — chốt bàn giao trong một transaction (của caller, đã khoá đơn → biên bản → hợp đồng): tạo
 * {@code UnitAccessKey}, khoang {@code Reserved -> Rented}, hợp đồng {@code Signed -> Active}, biên bản
 * {@code COMPLETED}, đơn {@code InProgress -> Done}. Thông báo gửi sau commit ({@link HandoverCompleted}).
 *
 * <p>Mã khóa số chỉ lưu hash; mã rõ trả về đúng một lần cho caller (khách thấy ngay trên màn hình xác nhận).
 */
@Component
class HandoverCompletion {

    static final int CODE_LENGTH = 6;

    private final SecureRandom random = new SecureRandom();
    private final UnitAccessKeyRepository accessKeys;
    private final StorageUnitService storageUnitService;
    private final HandoverOrderService orders;
    private final PasswordEncoder passwordEncoder;
    private final AuditRecorder auditRecorder;
    private final ApplicationEventPublisher events;

    HandoverCompletion(UnitAccessKeyRepository accessKeys, StorageUnitService storageUnitService,
                       HandoverOrderService orders, PasswordEncoder passwordEncoder, AuditRecorder auditRecorder,
                       ApplicationEventPublisher events) {
        this.accessKeys = accessKeys;
        this.storageUnitService = storageUnitService;
        this.orders = orders;
        this.passwordEncoder = passwordEncoder;
        this.auditRecorder = auditRecorder;
        this.events = events;
    }

    /**
     * @param keyQuantity số chìa FS giao (null khi khách chọn chỉ mã số)
     * @return mã khóa số rõ nếu vừa cấp, ngược lại {@code null}
     */
    String complete(HandoverRecord record, RentalContract contract, Integer keyQuantity, String unitCode,
                    Long actorAccountId) {
        if (!record.isOpen()) {
            throw new HandoverConflictException("Phiên bàn giao đã đóng");
        }
        if (contract.getStatus() != RentalContractStatus.SIGNED) {
            throw new HandoverConflictException("Hợp đồng chưa ở trạng thái đã ký");
        }
        boolean paid = orders.firstMonthInvoice(contract.getId())
                .map(HandoverOrderService.ContractInvoice::paid).orElse(false);
        if (!paid) {
            throw new HandoverConflictException("Hoá đơn tháng đầu chưa được thanh toán");
        }
        AccessChoice choice = record.accessChoice();
        if (choice == null) {
            throw new HandoverConflictException("Khách chưa xác nhận biên bản bàn giao");
        }
        Instant now = Instant.now();

        String plainCode = null;
        if (choice.needsPhysicalKey()) {
            if (keyQuantity == null) {
                throw new HandoverConflictException("Thiếu số chìa khóa giao cho khách");
            }
            issue(UnitAccessKey.physicalKey(contract.getUnitId(), contract.getId(), keyQuantity, now),
                    actorAccountId);
        }
        if (choice.needsCode()) {
            plainCode = newCode();
            issue(UnitAccessKey.accessCode(contract.getUnitId(), contract.getId(), passwordEncoder.encode(plainCode),
                    now), actorAccountId);
        }

        if (!storageUnitService.rentIfReserved(contract.getUnitId())) {
            throw new HandoverConflictException("Khoang chứa không còn ở trạng thái giữ chỗ — liên hệ FM");
        }
        record(AuditAction.STORAGE_UNIT_RENTED, AuditEntityType.STORAGE_UNIT, contract.getUnitId(),
                Map.of("status", "Reserved"), Map.of("status", "Rented", "contractId", contract.getId()),
                actorAccountId);

        contract.activate();
        record(AuditAction.CONTRACT_ACTIVATED, AuditEntityType.RENTAL_CONTRACT, contract.getId(),
                Map.of("status", RentalContractStatus.SIGNED.dbValue()),
                Map.of("status", contract.getStatus().dbValue()), actorAccountId);

        record.complete(now);
        record(AuditAction.HANDOVER_COMPLETED, AuditEntityType.HANDOVER_RECORD, record.getId(),
                Map.of("result", "IN_PROGRESS"),
                Map.of("result", record.getResult().name(), "requestedAccess", choice.dbValue()), actorAccountId);

        orders.complete(record.getOrderId(), actorAccountId);
        events.publishEvent(new HandoverCompleted(record.getId(), record.getOrderId(), unitCode));
        return plainCode;
    }

    /** Khách lấy lại mã: thu hồi mã đang Active (nếu có) rồi cấp mã mới — chỉ mã mới hiện một lần. */
    String rotateCode(RentalContract contract, Long actorAccountId) {
        Instant now = Instant.now();
        accessKeys.findActiveForUpdate(contract.getId(), UnitAccessKey.ACCESS_CODE).ifPresent(old -> {
            old.revoke(now);
            accessKeys.flush();
            record(AuditAction.ACCESS_KEY_REVOKED, AuditEntityType.UNIT_ACCESS_KEY, old.getId(),
                    Map.of("status", UnitAccessKey.ACTIVE), Map.of("status", old.getStatus()), actorAccountId);
        });
        String plainCode = newCode();
        issue(UnitAccessKey.accessCode(contract.getUnitId(), contract.getId(), passwordEncoder.encode(plainCode),
                now), actorAccountId);
        return plainCode;
    }

    private void issue(UnitAccessKey key, Long actorAccountId) {
        accessKeys.save(key);
        Map<String, Object> newValue = new HashMap<>();
        newValue.put("accessType", key.getAccessType());
        newValue.put("contractId", key.getContractId());
        if (key.getQuantity() != null) {
            newValue.put("quantity", key.getQuantity());
        }
        record(AuditAction.ACCESS_KEY_ISSUED, AuditEntityType.UNIT_ACCESS_KEY, key.getId(), null, newValue,
                actorAccountId);
    }

    private String newCode() {
        StringBuilder code = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            code.append(random.nextInt(10));
        }
        return code.toString();
    }

    private void record(AuditAction action, AuditEntityType type, Long entityId, Object oldValue, Object newValue,
                        Long actorAccountId) {
        auditRecorder.record(new AuditEntry(action, type, String.valueOf(entityId), oldValue, newValue, null,
                actorAccountId));
    }
}
