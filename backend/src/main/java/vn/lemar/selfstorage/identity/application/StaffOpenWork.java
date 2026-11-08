package vn.lemar.selfstorage.identity.application;

import java.util.List;

/**
 * Việc còn mở được giao đích danh cho một FS tại một cơ sở. Module nào giao việc cho FS (lịch hẹn, phiên bàn giao,
 * yêu cầu hỗ trợ) cài interface này; {@link AccountAdminService#setStaffFacilities} gọi mọi bản cài trước khi gỡ FS
 * khỏi cơ sở — còn việc thì 409, vì FS mất quyền ở cơ sở đó và việc sẽ bị treo.
 *
 * <p>Đặt ở identity để chiều phụ thuộc giữ nguyên (module nghiệp vụ → identity).
 */
public interface StaffOpenWork {

    /** Nhãn ngắn của từng việc còn mở (vd {@code "lịch CHECKIN #12"}); rỗng nếu không còn việc. */
    List<String> openItems(Long staffId, Long facilityId);
}
