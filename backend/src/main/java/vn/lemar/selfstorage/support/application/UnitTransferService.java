package vn.lemar.selfstorage.support.application;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.lemar.selfstorage.audit.application.AuditEntry;
import vn.lemar.selfstorage.audit.application.AuditRecorder;
import vn.lemar.selfstorage.audit.domain.AuditAction;
import vn.lemar.selfstorage.audit.domain.AuditEntityType;
import vn.lemar.selfstorage.booking.application.HandoverOrderService;
import vn.lemar.selfstorage.facility.application.FacilityAccess;
import vn.lemar.selfstorage.facility.application.StorageUnitService;
import vn.lemar.selfstorage.handover.application.RentalContractService;
import vn.lemar.selfstorage.handover.application.RentalContractService.ContractSnapshot;
import vn.lemar.selfstorage.handover.application.RentalContractService.UnitMove;
import vn.lemar.selfstorage.identity.application.Access;
import vn.lemar.selfstorage.identity.application.CurrentAccountProvider;
import vn.lemar.selfstorage.notification.application.NotificationRecipient;
import vn.lemar.selfstorage.notification.application.NotificationType;
import vn.lemar.selfstorage.support.application.SupportService.SupportView;
import vn.lemar.selfstorage.support.domain.SupportRequest;
import vn.lemar.selfstorage.support.domain.UnitTransfer;
import vn.lemar.selfstorage.support.repository.SupportRequestRepository;
import vn.lemar.selfstorage.support.repository.UnitTransferRepository;
import vn.lemar.selfstorage.tenancy.application.TenancyException;

/**
 * Flow 7 — khoang hư không sửa ngay được: chuyển khách sang khoang khác giữa kỳ thuê, gắn yêu cầu hỗ trợ.
 * <ol>
 *   <li>FS được giao đề xuất (kèm lý do): yêu cầu {@code Assigned -> Transferring}, chuyển khoang {@code Requested};
 *       FM nhận thông báo.</li>
 *   <li>FM điều một khoang {@code Available} cùng cơ sở — còn khoang cùng loại thì bắt buộc cùng loại (cùng giá); hết
 *       khoang cùng loại mới được chọn loại diện tích ≥ khoang cũ. Khoang mới {@code Reserved} (giữ theo đơn của hợp
 *       đồng); điều lại thì nhả khoang trước.</li>
 *   <li>FS (hoặc FM) hoàn tất: khoang mới {@code Rented}, khoang cũ {@code Maintenance} (bảo trì thủ công — FM mở lại
 *       khi sửa xong), hợp đồng đổi khoang — giá / cọc / kỳ hạn giữ nguyên, chìa / mã cũ thu hồi và cấp lại trên khoang
 *       mới ({@link RentalContractService#moveToUnit}); yêu cầu {@code Resolved}.</li>
 *   <li>FM huỷ khi chưa hoàn tất: nhả khoang đang giữ, yêu cầu về {@code Assigned}.</li>
 * </ol>
 * Khoá theo thứ tự đơn → hợp đồng → yêu cầu → chuyển khoang (như {@link SupportService#resolve}); khoang khoá trong
 * {@link StorageUnitService}.
 */
@Service
public class UnitTransferService {

    private final SupportRequestRepository requests;
    private final UnitTransferRepository transfers;
    private final SupportService support;
    private final RentalContractService contracts;
    private final HandoverOrderService orders;
    private final StorageUnitService storageUnits;
    private final FacilityAccess facilityAccess;
    private final Access access;
    private final CurrentAccountProvider currentAccount;
    private final AuditRecorder auditRecorder;
    private final JdbcTemplate jdbc;

    UnitTransferService(SupportRequestRepository requests, UnitTransferRepository transfers, SupportService support,
                        RentalContractService contracts, HandoverOrderService orders,
                        StorageUnitService storageUnits, FacilityAccess facilityAccess, Access access,
                        CurrentAccountProvider currentAccount, AuditRecorder auditRecorder, JdbcTemplate jdbc) {
        this.requests = requests;
        this.transfers = transfers;
        this.support = support;
        this.contracts = contracts;
        this.orders = orders;
        this.storageUnits = storageUnits;
        this.facilityAccess = facilityAccess;
        this.access = access;
        this.currentAccount = currentAccount;
        this.auditRecorder = auditRecorder;
        this.jdbc = jdbc;
    }

    /**
     * Khoang trống FM có thể điều: {@code sameType} = cùng loại với khoang cũ (cùng giá); {@code allowed} = được chọn
     * theo luật ưu tiên cùng loại.
     */
    public record TransferCandidate(Long unitId, String code, Long unitTypeId, String unitTypeName, BigDecimal area,
                                    Integer floorNo, boolean sameType, boolean allowed) {
    }

    /** Hoàn tất chuyển khoang: yêu cầu đã xử lý + mã khóa số mới (rõ, chỉ trả một lần; null nếu không cấp lại). */
    public record TransferCompletion(SupportView request, String accessCode) {
    }

    // ------------------------------------------------------------------ FS đề xuất

    @Transactional
    public SupportView request(Long supportId, String reason) {
        String why = trimToNull(reason);
        if (why == null || why.length() > 1000) {
            throw TenancyException.invalid("Lý do chuyển khoang bắt buộc, tối đa 1000 ký tự");
        }
        SupportRequest peek = find(supportId);
        Long actorId = currentAccount.getCurrentAccountId();
        if (!actorId.equals(peek.getAssignedStaffId())) {
            throw TenancyException.conflict("Chỉ FS được giao yêu cầu này mới đề xuất chuyển khoang");
        }
        access.can(SupportService.PERM_RESOLVE, peek.getFacilityId());
        ContractSnapshot contract = lockContract(peek);
        if (!contract.active()) {
            throw TenancyException.conflict("Hợp đồng không còn hiệu lực — không chuyển khoang được");
        }
        SupportRequest request = requests.findByIdForUpdate(supportId).orElseThrow();
        if (!SupportRequest.ASSIGNED.equals(request.getStatus())) {
            throw TenancyException.conflict("Chỉ đề xuất chuyển khoang khi yêu cầu đang được xử lý (Assigned)");
        }
        if (Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from return_requests where contract_id = ? "
                + "and status in ('Pending', 'Assigned'))", Boolean.class, contract.id()))) {
            throw TenancyException.conflict("Hợp đồng đang có yêu cầu trả kho — không chuyển khoang");
        }
        UnitTransfer transfer;
        try {
            transfer = transfers.saveAndFlush(UnitTransfer.request(supportId, contract.id(), contract.unitId(), why,
                    actorId));
        } catch (DataIntegrityViolationException e) {
            throw TenancyException.conflict("Hợp đồng đang có một lần chuyển khoang chưa xong");
        }
        request.startTransfer();
        // Audit của chuyển khoang mang supportRequestId — trạng thái Transferring của yêu cầu đọc theo bản ghi này.
        audit(AuditAction.UNIT_TRANSFER_REQUESTED, transfer.getId(), null,
                Map.of("status", transfer.getStatus(), "supportRequestId", supportId, "contractId", contract.id(),
                        "fromUnitId", contract.unitId(), "reason", why), actorId);
        Map<String, String> values = transferValues(request, transfer);
        values.put("staffName", support.staffName(actorId));
        values.put("reason", why);
        facilityAccess.managerAccountId(request.getFacilityId()).ifPresent(fmId -> support.notify(fmId,
                NotificationRecipient.FM, NotificationType.UNIT_TRANSFER_REQUESTED, values, contract.orderId()));
        return support.view(request);
    }

    // ------------------------------------------------------------------ FM điều khoang / huỷ

    @Transactional(readOnly = true)
    public List<TransferCandidate> candidates(Long supportId) {
        SupportRequest request = find(supportId);
        access.can(SupportService.PERM_ASSIGN, request.getFacilityId());
        UnitTransfer transfer = transfers.findFirstBySupportRequestIdOrderByIdDesc(supportId)
                .filter(UnitTransfer::isOpen)
                .orElseThrow(() -> TenancyException.conflict("Yêu cầu không có đề xuất chuyển khoang đang mở"));
        return candidatesFor(request.getFacilityId(), transfer.getFromUnitId());
    }

    @Transactional
    public SupportView allocate(Long supportId, Long unitId) {
        if (unitId == null) {
            throw TenancyException.invalid("Chọn khoang để chuyển");
        }
        SupportRequest peek = find(supportId);
        access.can(SupportService.PERM_ASSIGN, peek.getFacilityId());
        ContractSnapshot contract = lockContract(peek);
        if (!contract.active()) {
            throw TenancyException.conflict("Hợp đồng không còn hiệu lực — huỷ chuyển khoang");
        }
        SupportRequest request = requireTransferring(supportId);
        UnitTransfer transfer = transfers.findOpenForUpdate(supportId)
                .orElseThrow(() -> TenancyException.conflict("Yêu cầu không có đề xuất chuyển khoang đang mở"));
        if (unitId.equals(transfer.getToUnitId())) {
            return support.view(request);
        }
        List<TransferCandidate> candidates = candidatesFor(request.getFacilityId(), transfer.getFromUnitId());
        TransferCandidate chosen = candidates.stream().filter(c -> c.unitId().equals(unitId)).findFirst()
                .orElseThrow(() -> TenancyException.invalid("Khoang không thuộc cơ sở hoặc không còn trống"));
        if (!chosen.allowed()) {
            boolean sameTypeLeft = candidates.stream().anyMatch(TransferCandidate::sameType);
            throw TenancyException.conflict(sameTypeLeft
                    ? "Còn khoang trống cùng loại với khoang cũ — chọn khoang cùng loại để giữ cùng giá"
                    : "Khoang " + chosen.code() + " nhỏ hơn khoang hiện tại — chọn khoang cùng loại hoặc rộng hơn");
        }
        Long actorId = currentAccount.getCurrentAccountId();
        Long previous = transfer.getToUnitId();
        if (previous != null && storageUnits.releaseIfReserved(previous, contract.orderId())) {
            auditUnit(AuditAction.STORAGE_UNIT_AVAILABLE, previous, "Reserved", "Available",
                    "FM điều khoang khác cho chuyển khoang #" + transfer.getId(), actorId);
        }
        if (!storageUnits.reserveIfAvailable(unitId, contract.orderId())) {
            throw TenancyException.conflict("Khoang " + chosen.code()
                    + " vừa được giữ cho khách khác — chọn khoang khác");
        }
        auditUnit(AuditAction.STORAGE_UNIT_RESERVED, unitId, "Available", "Reserved",
                "Giữ cho chuyển khoang #" + transfer.getId(), actorId);
        String before = transfer.getStatus();
        transfer.allocate(unitId, actorId);
        Map<String, Object> newValue = new HashMap<>();
        newValue.put("status", transfer.getStatus());
        newValue.put("toUnitId", unitId);
        newValue.put("sameType", chosen.sameType());
        Map<String, Object> oldValue = new HashMap<>();
        oldValue.put("status", before);
        oldValue.put("toUnitId", previous);
        audit(AuditAction.UNIT_TRANSFER_ALLOCATED, transfer.getId(), oldValue, newValue, actorId);
        transfers.flush();
        Map<String, String> values = transferValues(request, transfer);
        support.notify(request.getAssignedStaffId(), NotificationRecipient.FS, NotificationType.UNIT_TRANSFER_ALLOCATED,
                values, contract.orderId());
        support.notify(request.getCustomerId(), NotificationRecipient.CUSTOMER,
                NotificationType.UNIT_TRANSFER_ALLOCATED, values, contract.orderId());
        return support.view(request);
    }

    @Transactional
    public SupportView cancel(Long supportId, String reason) {
        String why = trimToNull(reason);
        if (why == null || why.length() > 500) {
            throw TenancyException.invalid("Lý do huỷ bắt buộc, tối đa 500 ký tự");
        }
        SupportRequest peek = find(supportId);
        access.can(SupportService.PERM_ASSIGN, peek.getFacilityId());
        ContractSnapshot contract = lockContract(peek);
        SupportRequest request = requireTransferring(supportId);
        UnitTransfer transfer = transfers.findOpenForUpdate(supportId)
                .orElseThrow(() -> TenancyException.conflict("Yêu cầu không có đề xuất chuyển khoang đang mở"));
        Long actorId = currentAccount.getCurrentAccountId();
        if (transfer.getToUnitId() != null && storageUnits.releaseIfReserved(transfer.getToUnitId(),
                contract.orderId())) {
            auditUnit(AuditAction.STORAGE_UNIT_AVAILABLE, transfer.getToUnitId(), "Reserved", "Available",
                    "Huỷ chuyển khoang #" + transfer.getId(), actorId);
        }
        String before = transfer.getStatus();
        transfer.cancel(actorId, why);
        request.cancelTransfer();
        audit(AuditAction.UNIT_TRANSFER_CANCELED, transfer.getId(), Map.of("status", before),
                Map.of("status", transfer.getStatus(), "reason", why), actorId);
        transfers.flush();
        Map<String, String> values = transferValues(request, transfer);
        values.put("reason", why);
        support.notify(request.getAssignedStaffId(), NotificationRecipient.FS, NotificationType.UNIT_TRANSFER_CANCELED,
                values, contract.orderId());
        return support.view(request);
    }

    // ------------------------------------------------------------------ FS hoàn tất

    @Transactional
    public TransferCompletion complete(Long supportId, Integer keyQuantity) {
        if (keyQuantity != null && (keyQuantity < 1 || keyQuantity > 10)) {
            throw TenancyException.invalid("Số chìa giao cho khoang mới từ 1 đến 10");
        }
        SupportRequest peek = find(supportId);
        Long actorId = currentAccount.getCurrentAccountId();
        access.can(actorId.equals(peek.getAssignedStaffId()) ? SupportService.PERM_RESOLVE
                : SupportService.PERM_ASSIGN, peek.getFacilityId());
        ContractSnapshot contract = lockContract(peek);
        if (!contract.active()) {
            throw TenancyException.conflict("Hợp đồng không còn hiệu lực — FM huỷ chuyển khoang");
        }
        SupportRequest request = requireTransferring(supportId);
        UnitTransfer transfer = transfers.findOpenForUpdate(supportId)
                .orElseThrow(() -> TenancyException.conflict("Yêu cầu không có đề xuất chuyển khoang đang mở"));
        if (!UnitTransfer.ALLOCATED.equals(transfer.getStatus())) {
            throw TenancyException.conflict("FM chưa điều khoang mới");
        }
        boolean hasKey = contracts.hasActivePhysicalKeys(contract.id());
        if (hasKey && keyQuantity == null) {
            throw TenancyException.invalid("Nhập số chìa giao cho khách ở khoang mới");
        }
        Long fromUnitId = transfer.getFromUnitId();
        Long toUnitId = transfer.getToUnitId();
        if (!storageUnits.rentIfReserved(toUnitId)) {
            throw TenancyException.conflict("Khoang mới không còn được giữ — liên hệ FM điều lại");
        }
        auditUnit(AuditAction.STORAGE_UNIT_RENTED, toUnitId, "Reserved", "Rented",
                "Chuyển khoang #" + transfer.getId(), actorId);
        Map<String, String> values = transferValues(request, transfer);
        if (storageUnits.startManualMaintenanceAfterReturn(fromUnitId)) {
            auditUnit(AuditAction.STORAGE_UNIT_MAINTENANCE, fromUnitId, "Rented", "Maintenance",
                    "Khoang hư — khách chuyển sang khoang " + values.get("toUnitCode"), actorId);
        }
        UnitMove move = contracts.moveToUnit(contract.id(), toUnitId, hasKey ? keyQuantity : null, actorId);
        transfer.complete(actorId, move.keyQuantity());
        Map<String, Object> newValue = new HashMap<>();
        newValue.put("status", transfer.getStatus());
        newValue.put("fromUnitId", fromUnitId);
        newValue.put("toUnitId", toUnitId);
        newValue.put("keyQuantity", move.keyQuantity());
        newValue.put("codeReissued", move.accessCode() != null);
        audit(AuditAction.UNIT_TRANSFER_COMPLETED, transfer.getId(), Map.of("status", UnitTransfer.ALLOCATED),
                newValue, actorId);
        String resolution = "Đã chuyển sang khoang " + values.get("toUnitCode") + " (thay khoang "
                + values.get("fromUnitCode") + " bị hư); hợp đồng và giá thuê giữ nguyên.";
        request.completeTransfer(resolution);
        Map<String, Object> resolved = new HashMap<>();
        resolved.put("status", request.getStatus());
        resolved.put("unitTransferId", transfer.getId());
        auditRecorder.record(new AuditEntry(AuditAction.SUPPORT_REQUEST_RESOLVED, AuditEntityType.SUPPORT_REQUEST,
                String.valueOf(supportId), Map.of("status", SupportRequest.TRANSFERRING), resolved, null, actorId));
        transfers.flush();
        values.put("contractCode", contract.code());
        values.put("accessNote", accessNote(move));
        support.notify(request.getCustomerId(), NotificationRecipient.CUSTOMER,
                NotificationType.UNIT_TRANSFER_COMPLETED, values, contract.orderId());
        return new TransferCompletion(support.view(request), move.accessCode());
    }

    // ------------------------------------------------------------------ internals

    private List<TransferCandidate> candidatesFor(Long facilityId, Long fromUnitId) {
        Map<String, Object> from = jdbc.queryForMap("""
                select u.unit_type_id, t.area from storage_units u join unit_types t on t.id = u.unit_type_id
                where u.id = ?""", fromUnitId);
        Long fromType = ((Number) from.get("unit_type_id")).longValue();
        BigDecimal fromArea = (BigDecimal) from.get("area");
        List<TransferCandidate> raw = jdbc.query("""
                select u.id, u.code, u.unit_type_id, t.name, t.area, u.floor_no
                from storage_units u join unit_types t on t.id = u.unit_type_id
                where u.facility_id = ? and u.status = 'Available' and u.id <> ?
                order by (u.unit_type_id = ?) desc, t.area nulls last, u.code""",
                (rs, i) -> new TransferCandidate(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4),
                        rs.getBigDecimal(5), (Integer) rs.getObject(6), rs.getLong(3) == fromType, false),
                facilityId, fromUnitId, fromType);
        boolean sameTypeLeft = raw.stream().anyMatch(TransferCandidate::sameType);
        return raw.stream().map(c -> new TransferCandidate(c.unitId(), c.code(), c.unitTypeId(), c.unitTypeName(),
                c.area(), c.floorNo(), c.sameType(), c.sameType() || (!sameTypeLeft && fromArea != null
                        && c.area() != null && c.area().compareTo(fromArea) >= 0))).toList();
    }

    private SupportRequest find(Long supportId) {
        return requests.findById(supportId).orElseThrow(() -> TenancyException.notFound(SupportService.MSG_NOT_FOUND));
    }

    /** Khoá đơn → hợp đồng của yêu cầu (yêu cầu phải gắn hợp đồng). */
    private ContractSnapshot lockContract(SupportRequest request) {
        if (request.getContractId() == null) {
            throw TenancyException.conflict("Yêu cầu không gắn hợp đồng — không chuyển khoang được");
        }
        ContractSnapshot snapshot = contracts.find(request.getContractId()).orElseThrow();
        orders.lock(snapshot.orderId());
        return contracts.lock(request.getContractId()).orElseThrow();
    }

    private SupportRequest requireTransferring(Long supportId) {
        SupportRequest request = requests.findByIdForUpdate(supportId).orElseThrow();
        if (!request.isTransferring()) {
            throw TenancyException.conflict("Yêu cầu không ở bước chuyển khoang");
        }
        return request;
    }

    private Map<String, String> transferValues(SupportRequest request, UnitTransfer transfer) {
        Map<String, String> values = support.values(request);
        values.put("fromUnitCode", unitCode(transfer.getFromUnitId()));
        values.put("toUnitCode", transfer.getToUnitId() == null ? "" : unitCode(transfer.getToUnitId()));
        return values;
    }

    private String unitCode(Long unitId) {
        return jdbc.queryForObject("select code from storage_units where id = ?", String.class, unitId);
    }

    private static String accessNote(UnitMove move) {
        StringBuilder note = new StringBuilder();
        if (move.keyQuantity() != null) {
            note.append(" Bạn đã nhận ").append(move.keyQuantity()).append(" chìa của khoang mới; chìa cũ đã thu hồi.");
        }
        if (move.accessCode() != null) {
            note.append(" Mã khoá số cũ đã huỷ — nhân viên đưa mã mới, hoặc lấy lại mã trong mục Kho của tôi.");
        }
        return note.toString();
    }

    private void audit(AuditAction action, Long transferId, Object oldValue, Object newValue, Long actorId) {
        auditRecorder.record(new AuditEntry(action, AuditEntityType.UNIT_TRANSFER, String.valueOf(transferId),
                oldValue, newValue, null, actorId));
    }

    private void auditUnit(AuditAction action, Long unitId, String before, String after, String reason,
                           Long actorId) {
        auditRecorder.record(new AuditEntry(action, AuditEntityType.STORAGE_UNIT, String.valueOf(unitId),
                Map.of("status", before), Map.of("status", after, "reason", reason), null, actorId));
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
