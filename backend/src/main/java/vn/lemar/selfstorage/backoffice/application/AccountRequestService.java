package vn.lemar.selfstorage.backoffice.application;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.lemar.selfstorage.audit.application.AuditEntry;
import vn.lemar.selfstorage.audit.application.AuditRecorder;
import vn.lemar.selfstorage.audit.domain.AuditAction;
import vn.lemar.selfstorage.audit.domain.AuditEntityType;
import vn.lemar.selfstorage.facility.application.FacilityAccess;
import vn.lemar.selfstorage.identity.application.AccountAdminService;
import vn.lemar.selfstorage.identity.application.AccountAdminService.AccountRow;
import vn.lemar.selfstorage.identity.application.CurrentAccountProvider;
import vn.lemar.selfstorage.identity.application.StaffWorkTransfer;
import vn.lemar.selfstorage.notification.application.NotificationRecipient;
import vn.lemar.selfstorage.notification.application.NotificationService;
import vn.lemar.selfstorage.notification.application.NotificationType;

/**
 * Yêu cầu tạo tài khoản nhân sự: BOM gửi yêu cầu tạo tài khoản FM / FS, ADMIN duyệt (đặt mật khẩu ban đầu — tài
 * khoản được tạo và gán cơ sở ngay) hoặc từ chối kèm lý do. BOM không tự tạo tài khoản được; tài khoản BOM do ADMIN
 * tự tạo (không qua yêu cầu).
 *
 * <p>Cơ sở theo role: FS ≥ 1 cơ sở, FM đúng 1 cơ sở đang trống FM (quan hệ 1–1). Duyệt chạy trong một transaction: tạo
 * tài khoản, gán cơ sở, chốt yêu cầu — bước nào lỗi (email đã có tài khoản, cơ sở vừa có FM khác, mật khẩu ngắn) thì
 * rollback cả, yêu cầu vẫn {@code Pending}.
 *
 * <p>Thay người nghỉ việc ({@code replacesAccountId}): người được thay phải cùng role và đang {@code ACTIVE}; cơ sở lấy
 * theo người cũ (FM: cơ sở đang phụ trách; FS: các cơ sở đang gán). Duyệt xong: FM mới thế chỗ FM cũ ở cơ sở; FS mới
 * nhận các cơ sở của FS cũ và toàn bộ việc còn mở ({@link StaffWorkTransfer}), FS cũ bị gỡ khỏi cơ sở; tài khoản cũ
 * chuyển {@code INACTIVE} (không đăng nhập được, lịch sử giữ nguyên).
 */
@Service
public class AccountRequestService {

    static final Set<String> ROLES = Set.of("FM", "FS");
    static final Set<String> STATUSES = Set.of("Pending", "Approved", "Rejected", "Canceled");
    private static final Map<String, String> ROLE_LABELS = Map.of(
            "FM", "Quản lý cơ sở (FM)", "FS", "Nhân viên cơ sở (FS)");

    private final JdbcTemplate jdbc;
    private final AccountAdminService accounts;
    private final BackofficeService backoffice;
    private final FacilityAccess facilityAccess;
    private final NotificationService notifications;
    private final AuditRecorder auditRecorder;
    private final CurrentAccountProvider currentAccount;
    private final List<StaffWorkTransfer> workTransfers;
    private final String frontendUrl;

    public AccountRequestService(JdbcTemplate jdbc, AccountAdminService accounts, BackofficeService backoffice,
                                 FacilityAccess facilityAccess, NotificationService notifications,
                                 AuditRecorder auditRecorder, CurrentAccountProvider currentAccount,
                                 List<StaffWorkTransfer> workTransfers,
                                 @Value("${app.frontend-url:http://localhost:5173}") String frontendUrl) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.backoffice = backoffice;
        this.facilityAccess = facilityAccess;
        this.notifications = notifications;
        this.auditRecorder = auditRecorder;
        this.currentAccount = currentAccount;
        this.workTransfers = workTransfers;
        this.frontendUrl = frontendUrl.replaceAll("/+$", "");
    }

    /** Một yêu cầu kèm người gửi / người duyệt, mã cơ sở và người nghỉ việc được thay (nếu có) để hiển thị. */
    public record AccountRequestRow(Long id, Long requestedById, String requestedByEmail, String requestedByName,
                                    String role, String email, String name, String phone, List<Long> facilityIds,
                                    List<String> facilityCodes, String note, String status, Long reviewedById,
                                    String reviewedByEmail, Instant reviewedAt, String reviewNote,
                                    Long createdAccountId, Instant createdAt, Long replacesAccountId,
                                    String replacesEmail, String replacesName) {
    }

    private static final String SELECT = """
            select r.id, r.requested_by, ra.email, ra.name, r.role, r.email, r.name, r.phone, r.facility_ids,
                   (select string_agg(f.code, ',' order by f.code) from facilities f where f.id = any(r.facility_ids)),
                   r.note, r.status, r.reviewed_by, va.email, r.reviewed_at, r.review_note, r.created_account_id,
                   r.created_at, r.replaces_account_id, oa.email, oa.name
            from account_requests r
            join accounts ra on ra.id = r.requested_by
            left join accounts va on va.id = r.reviewed_by
            left join accounts oa on oa.id = r.replaces_account_id
            """;

    /** Danh sách mới nhất trước; {@code status} null = mọi trạng thái. */
    @Transactional(readOnly = true)
    public List<AccountRequestRow> list(String status) {
        if (status != null && !STATUSES.contains(status)) {
            throw new IllegalArgumentException("Trạng thái không hợp lệ");
        }
        return status == null
                ? jdbc.query(SELECT + " order by r.created_at desc, r.id desc", this::mapRow)
                : jdbc.query(SELECT + " where r.status = ? order by r.created_at desc, r.id desc", this::mapRow,
                        status);
    }

    @Transactional(readOnly = true)
    public AccountRequestRow get(Long id) {
        return jdbc.query(SELECT + " where r.id = ?", this::mapRow, id).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy yêu cầu tạo tài khoản"));
    }

    /**
     * BOM gửi yêu cầu; ADMIN đang hoạt động nhận thông báo. {@code replacesAccountId} khác null = thay người nghỉ việc,
     * khi đó {@code facilityIds} bị bỏ qua — cơ sở lấy theo người cũ.
     */
    @Transactional
    public AccountRequestRow submit(String role, String email, String name, String phone, List<Long> facilityIds,
                                    String note, Long replacesAccountId) {
        if (role == null || !ROLES.contains(role)) {
            throw new IllegalArgumentException("Chỉ gửi yêu cầu tạo tài khoản FM hoặc FS (tài khoản BOM do Admin tạo)");
        }
        // Cùng cách chuẩn hoá với identity (Account.normalize) — backoffice chỉ được dùng identity::application.
        String normalized = email == null ? null : email.trim().toLowerCase();
        if (normalized == null || !normalized.contains("@")) {
            throw new IllegalArgumentException("Email không hợp lệ");
        }
        String fullName = trimToNull(name);
        if (fullName == null || fullName.length() > 255) {
            throw new IllegalArgumentException("Họ tên bắt buộc, tối đa 255 ký tự");
        }
        String phoneNumber = trimToNull(phone);
        if (phoneNumber != null && phoneNumber.length() > 20) {
            throw new IllegalArgumentException("Số điện thoại tối đa 20 ký tự");
        }
        String noteText = trimToNull(note);
        if (noteText != null && noteText.length() > 500) {
            throw new IllegalArgumentException("Ghi chú tối đa 500 ký tự");
        }
        List<Long> facilities = replacesAccountId == null ? validFacilities(role, facilityIds)
                : replacedFacilities(role, replacesAccountId);
        if (Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from accounts where email = ?)",
                Boolean.class, normalized))) {
            throw new IllegalStateException("Email " + normalized + " đã có tài khoản");
        }
        Long requester = currentAccount.getCurrentAccountId();
        Long id;
        try {
            id = jdbc.queryForObject("""
                    insert into account_requests (requested_by, role, email, name, phone, facility_ids, note,
                                                  replaces_account_id)
                    values (?, ?, ?, ?, ?, ?::bigint[], ?, ?) returning id""", Long.class, requester, role,
                    normalized, fullName, phoneNumber, toPgArray(facilities), noteText, replacesAccountId);
        } catch (DuplicateKeyException e) {
            if (String.valueOf(e.getMessage()).contains("uq_account_requests_pending_replacement")) {
                throw new IllegalStateException("Người này đã có yêu cầu thay thế đang chờ duyệt");
            }
            throw new IllegalStateException("Email " + normalized + " đang có yêu cầu chờ duyệt");
        }
        Map<String, Object> createdValue = new HashMap<>();
        createdValue.put("role", role);
        createdValue.put("email", normalized);
        createdValue.put("facilityIds", facilities);
        createdValue.put("replacesAccountId", replacesAccountId);
        audit(AuditAction.ACCOUNT_REQUEST_CREATED, id, null, createdValue);
        AccountRequestRow created = get(id);
        Map<String, Object> values = describe(created);
        values.put("requesterName", firstNonBlank(created.requestedByName(), created.requestedByEmail()));
        for (AccountRow admin : accounts.list("ADMIN", "ACTIVE", null, 0, 200)) {
            notifications.notify(admin.id(), admin.email(), NotificationRecipient.ADMIN,
                    NotificationType.ACCOUNT_REQUEST_SUBMITTED, values, null);
        }
        return created;
    }

    /** Người gửi huỷ yêu cầu còn chờ. */
    @Transactional
    public AccountRequestRow cancel(Long id) {
        AccountRequestRow request = lockPending(id);
        if (!request.requestedById().equals(currentAccount.getCurrentAccountId())) {
            throw new IllegalStateException("Chỉ người gửi mới huỷ được yêu cầu này");
        }
        jdbc.update("update account_requests set status = 'Canceled', updated_at = now() where id = ?", id);
        audit(AuditAction.ACCOUNT_REQUEST_CANCELED, id, Map.of("status", "Pending"), Map.of("status", "Canceled"));
        return get(id);
    }

    /**
     * ADMIN duyệt: tạo tài khoản với mật khẩu ban đầu, gán cơ sở (FS: các cơ sở; FM: cơ sở phải còn trống FM),
     * chốt yêu cầu, báo người gửi và gửi email cho người được tạo (không kèm mật khẩu). Yêu cầu thay người nghỉ việc:
     * FM mới thế chỗ FM cũ; FS mới nhận cơ sở và việc còn mở của FS cũ; tài khoản cũ chuyển {@code INACTIVE}.
     */
    @Transactional
    public AccountRequestRow approve(Long id, String password) {
        AccountRequestRow request = lockPending(id);
        Long actor = currentAccount.getCurrentAccountId();
        Long oldId = request.replacesAccountId();
        if (oldId != null) {
            requireReplaceable(request.role(), oldId);
        }
        List<Long> facilityIds = request.facilityIds();
        if (oldId != null && "FS".equals(request.role())) {
            // Cơ sở của FS cũ có thể đổi sau khi gửi: FS mới nhận cả cơ sở lúc gửi lẫn cơ sở hiện tại của FS cũ.
            Set<Long> union = new LinkedHashSet<>(facilityIds);
            union.addAll(staffFacilities(oldId));
            facilityIds = List.copyOf(union);
        }
        AccountRow created = accounts.create(request.email(), password, request.name(), request.phone(),
                request.role(), "FS".equals(request.role()) ? facilityIds : List.of(), actor);
        Map<String, Integer> moved = new LinkedHashMap<>();
        if ("FM".equals(request.role())) {
            Long facilityId = facilityIds.get(0);
            Long currentFm = jdbc.queryForObject("select fm_account_id from facilities where id = ? for update",
                    Long.class, facilityId);
            String code = String.join(", ", request.facilityCodes());
            if (oldId == null && currentFm != null) {
                throw new IllegalStateException("Cơ sở " + code + " đã có FM " + accounts.get(currentFm).email()
                        + ". Bỏ FM ở cơ sở đó trước hoặc từ chối yêu cầu.");
            }
            if (oldId != null && !oldId.equals(currentFm)) {
                throw new IllegalStateException("Cơ sở " + code + " không còn do " + request.replacesEmail()
                        + " phụ trách. Từ chối yêu cầu này và gửi lại nếu cần.");
            }
            backoffice.assignManager(facilityId, created.id(), true);
        } else if (oldId != null) {
            for (StaffWorkTransfer transfer : workTransfers) {
                transfer.transfer(oldId, created.id())
                        .forEach((label, count) -> moved.merge(label, count, Integer::sum));
            }
            accounts.setStaffFacilities(oldId, List.of(), actor);
        }
        if (oldId != null) {
            accounts.update(oldId, null, "INACTIVE", null, null, actor);
        }
        jdbc.update("""
                update account_requests set status = 'Approved', reviewed_by = ?, reviewed_at = now(),
                       created_account_id = ?, facility_ids = ?::bigint[], updated_at = now() where id = ?""",
                actor, created.id(), toPgArray(facilityIds), id);
        Map<String, Object> approvedValue = new HashMap<>();
        approvedValue.put("status", "Approved");
        approvedValue.put("accountId", created.id());
        if (oldId != null) {
            approvedValue.put("replacedAccountId", oldId);
            approvedValue.put("transferred", moved);
        }
        audit(AuditAction.ACCOUNT_REQUEST_APPROVED, id, Map.of("status", "Pending"), approvedValue);
        AccountRequestRow approved = get(id);
        notifyRequester(approved, "đã được duyệt", transferText(oldId, moved));
        Map<String, Object> ready = describe(approved);
        ready.put("loginLink", frontendUrl + "/login");
        notifications.notify(created.id(), created.email(), NotificationRecipient.valueOf(request.role()),
                NotificationType.ACCOUNT_READY, ready, null);
        return approved;
    }

    /** ADMIN từ chối kèm lý do; người gửi nhận thông báo. */
    @Transactional
    public AccountRequestRow reject(Long id, String reason) {
        String why = trimToNull(reason);
        if (why == null || why.length() > 500) {
            throw new IllegalArgumentException("Lý do từ chối bắt buộc, tối đa 500 ký tự");
        }
        lockPending(id);
        jdbc.update("""
                update account_requests set status = 'Rejected', reviewed_by = ?, reviewed_at = now(),
                       review_note = ?, updated_at = now() where id = ?""",
                currentAccount.getCurrentAccountId(), why, id);
        audit(AuditAction.ACCOUNT_REQUEST_REJECTED, id, Map.of("status", "Pending"),
                Map.of("status", "Rejected", "reason", why));
        AccountRequestRow rejected = get(id);
        notifyRequester(rejected, "bị từ chối", " Lý do: " + why);
        return rejected;
    }

    // ------------------------------------------------------------------ internals

    private List<Long> validFacilities(String role, List<Long> facilityIds) {
        List<Long> facilities = facilityIds == null ? List.of() : facilityIds.stream().distinct().toList();
        if ("FS".equals(role) && facilities.isEmpty()) {
            throw new IllegalArgumentException("Tài khoản FS cần ít nhất một cơ sở");
        }
        if ("FM".equals(role) && facilities.size() != 1) {
            throw new IllegalArgumentException("Tài khoản FM cần đúng một cơ sở phụ trách");
        }
        for (Long facilityId : facilities) {
            if (facilityId == null || !facilityAccess.exists(facilityId)) {
                throw new IllegalArgumentException("Cơ sở " + facilityId + " không tồn tại");
            }
        }
        if ("FM".equals(role)) {
            String currentFm = jdbc.queryForObject("""
                    select a.email from facilities f left join accounts a on a.id = f.fm_account_id
                    where f.id = ?""", String.class, facilities.get(0));
            if (currentFm != null) {
                throw new IllegalStateException("Cơ sở " + facilityAccess.code(facilities.get(0)).orElse("")
                        + " đã có FM " + currentFm + ". Mỗi cơ sở chỉ có một FM.");
            }
        }
        return facilities;
    }

    /** Cơ sở của người nghỉ việc: FM — cơ sở đang phụ trách; FS — các cơ sở đang gán. */
    private List<Long> replacedFacilities(String role, Long oldId) {
        AccountRow old = requireReplaceable(role, oldId);
        List<Long> facilities = "FM".equals(role)
                ? jdbc.queryForList("select id from facilities where fm_account_id = ? order by id", Long.class, oldId)
                : staffFacilities(oldId);
        if (facilities.isEmpty()) {
            throw new IllegalStateException(old.email() + " hiện không " + ("FM".equals(role) ? "phụ trách" : "thuộc")
                    + " cơ sở nào — gửi yêu cầu tuyển thêm thay vì thay người.");
        }
        return facilities;
    }

    private AccountRow requireReplaceable(String role, Long oldId) {
        AccountRow old = accounts.get(oldId);
        if (!role.equals(old.role())) {
            throw new IllegalArgumentException("Người được thay phải cùng vai trò " + role + " (" + old.email()
                    + " là " + old.role() + ")");
        }
        if (!"ACTIVE".equals(old.status())) {
            throw new IllegalStateException("Tài khoản " + old.email() + " không còn hoạt động (" + old.status() + ")");
        }
        return old;
    }

    private List<Long> staffFacilities(Long accountId) {
        return jdbc.queryForList("select facility_id from account_facility_assignments where account_id = ? "
                + "order by facility_id", Long.class, accountId);
    }

    private static String transferText(Long oldId, Map<String, Integer> moved) {
        if (oldId == null) {
            return "";
        }
        if (moved.isEmpty()) {
            return " Tài khoản cũ đã chuyển sang Đã nghỉ việc.";
        }
        return " Đã chuyển sang người mới: " + moved.entrySet().stream()
                .map(entry -> entry.getValue() + " " + entry.getKey()).collect(Collectors.joining(", "))
                + ". Tài khoản cũ đã chuyển sang Đã nghỉ việc.";
    }

    private AccountRequestRow lockPending(Long id) {
        List<String> status = jdbc.queryForList("select status from account_requests where id = ? for update",
                String.class, id);
        if (status.isEmpty()) {
            throw new IllegalArgumentException("Không tìm thấy yêu cầu tạo tài khoản");
        }
        if (!"Pending".equals(status.get(0))) {
            throw new IllegalStateException("Yêu cầu đã được xử lý (" + status.get(0) + ")");
        }
        return get(id);
    }

    private void notifyRequester(AccountRequestRow request, String result, String noteText) {
        Map<String, Object> values = describe(request);
        values.put("result", result);
        values.put("noteText", noteText);
        notifications.notify(request.requestedById(), request.requestedByEmail(), NotificationRecipient.BOM,
                NotificationType.ACCOUNT_REQUEST_REVIEWED, values, null);
    }

    private static Map<String, Object> describe(AccountRequestRow request) {
        Map<String, Object> values = new HashMap<>();
        values.put("roleLabel", ROLE_LABELS.get(request.role()));
        values.put("name", request.name());
        values.put("email", request.email());
        values.put("facilityText", request.facilityCodes().isEmpty() ? ""
                : " tại cơ sở " + String.join(", ", request.facilityCodes()));
        values.put("replaceText", request.replacesAccountId() == null ? ""
                : " thay " + firstNonBlank(request.replacesName(), request.replacesEmail()) + " ("
                        + request.replacesEmail() + ", nghỉ việc)");
        return values;
    }

    private AccountRequestRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        String codes = rs.getString(10);
        return new AccountRequestRow(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getString(6), rs.getString(7), rs.getString(8), toLongs(rs.getArray(9)),
                codes == null ? List.of() : Arrays.asList(codes.split(",")), rs.getString(11), rs.getString(12),
                (Long) rs.getObject(13), rs.getString(14), toInstant(rs.getTimestamp(15)), rs.getString(16),
                (Long) rs.getObject(17), toInstant(rs.getTimestamp(18)), (Long) rs.getObject(19), rs.getString(20),
                rs.getString(21));
    }

    private static List<Long> toLongs(Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        List<Long> values = new ArrayList<>();
        for (Object value : (Object[]) array.getArray()) {
            values.add(((Number) value).longValue());
        }
        return values;
    }

    private static String toPgArray(List<Long> values) {
        return values.stream().map(String::valueOf).collect(Collectors.joining(",", "{", "}"));
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String firstNonBlank(String first, String fallback) {
        return first == null || first.isBlank() ? fallback : first;
    }

    private void audit(AuditAction action, Long id, Object oldValue, Object newValue) {
        auditRecorder.record(new AuditEntry(action, AuditEntityType.ACCOUNT_REQUEST, String.valueOf(id), oldValue,
                newValue, null, currentAccount.getCurrentAccountId()));
    }
}
