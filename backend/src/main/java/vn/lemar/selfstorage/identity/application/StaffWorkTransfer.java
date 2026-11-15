package vn.lemar.selfstorage.identity.application;

import java.util.Map;

/**
 * Chuyển toàn bộ việc còn mở của một FS nghỉ việc sang FS thay thế. Module nào giao việc đích danh cho FS cài interface
 * này (cùng phạm vi với {@link StaffOpenWork}); yêu cầu "thay người nghỉ việc" gọi mọi bản cài trong cùng transaction
 * lúc ADMIN duyệt, rồi mới gỡ FS cũ khỏi cơ sở — lúc đó {@link StaffOpenWork} không còn việc nào để chặn.
 *
 * <p>Đặt ở identity để chiều phụ thuộc giữ nguyên (module nghiệp vụ → identity).
 */
public interface StaffWorkTransfer {

    /**
     * Chuyển việc của {@code fromStaffId} sang {@code toStaffId}; chạy trong transaction của caller.
     *
     * @return số việc đã chuyển theo nhãn ngắn (vd {@code "lịch hẹn" → 2}); nhãn không có việc thì bỏ qua
     */
    Map<String, Integer> transfer(Long fromStaffId, Long toStaffId);
}
