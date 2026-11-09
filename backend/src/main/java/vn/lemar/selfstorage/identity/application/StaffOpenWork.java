package vn.lemar.selfstorage.identity.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Việc còn mở được giao đích danh cho một FS. Module nào giao việc cho FS (lịch hẹn, phiên bàn giao, yêu cầu hỗ trợ)
 * cài interface này; {@link AccountAdminService#setStaffFacilities} gọi mọi bản cài trước khi gỡ FS khỏi cơ sở — còn
 * việc thì 409, vì FS mất quyền ở cơ sở đó và việc sẽ bị treo. Module nhân sự dùng các biến thể theo ngày để chặn
 * duyệt nghỉ phép, chặn xoá / thu hẹp ca và đếm việc trên màn phân công.
 *
 * <p>Đặt ở identity để chiều phụ thuộc giữ nguyên (module nghiệp vụ → identity).
 */
public interface StaffOpenWork {

    /**
     * Nhãn ngắn của từng việc còn mở tại cơ sở kèm cách gỡ chặn (vd {@code "lịch check-in #12 (FM đổi FS)"}); rỗng nếu
     * không còn việc.
     */
    List<String> openItems(Long staffId, Long facilityId);

    /**
     * Việc còn mở của FS ở mọi cơ sở rơi vào khoảng ngày {@code from..to} (tính cả hai đầu); việc không gắn ngày (yêu
     * cầu hỗ trợ đang giao) luôn tính. Nhãn như {@link #openItems}.
     */
    default List<String> openItemsBetween(Long staffId, LocalDate from, LocalDate to) {
        return List.of();
    }

    /** Số việc còn mở của FS tại cơ sở cho ngày {@code date}; việc không gắn ngày đếm vào {@code support}. */
    default OpenCounts openCounts(Long staffId, Long facilityId, LocalDate date) {
        return OpenCounts.NONE;
    }

    /** Việc có khung giờ (lịch hẹn chờ) đã giao cho FS tại cơ sở trong ngày {@code date}. */
    default List<TimedItem> timedItems(Long staffId, Long facilityId, LocalDate date) {
        return List.of();
    }

    /** Đếm việc còn mở: lịch check-in, lịch trả kho trong ngày; yêu cầu hỗ trợ đang giao. */
    record OpenCounts(int checkins, int returns, int support) {

        public static final OpenCounts NONE = new OpenCounts(0, 0, 0);

        public OpenCounts plus(OpenCounts other) {
            return new OpenCounts(checkins + other.checkins, returns + other.returns, support + other.support);
        }
    }

    /** Một việc có khung giờ: nhãn như {@link #openItems} và giờ bắt đầu / kết thúc. */
    record TimedItem(String label, Instant start, Instant end) {
    }
}
