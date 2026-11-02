package vn.lemar.selfstorage.booking.domain;

/**
 * Hành động tiếp theo của FM trên một dòng hàng đợi {@code RentalRequest}.
 *
 * <p>{@link #VIEW} là trạng thái "không cần làm gì" — order đã đóng, đang chờ thanh toán/lịch,
 * hoặc request không còn xử lý được. FE chỉ mở detail, không hiện action nghiệp vụ.
 */
public enum RentalRequestNextAction {
    APPROVE_REQUEST,
    WAIT_CUSTOMER,
    REPROPOSE_UNIT,
    VIEW
}
