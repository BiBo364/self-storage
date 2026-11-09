package vn.lemar.selfstorage.booking.application;

/**
 * Đơn thuê vừa chạy Cancellation Cascade. Phát đồng bộ trong transaction huỷ — module handover nghe để đóng
 * {@code HandoverRecord} đang mở và huỷ {@code RentalContract} {@code Draft}/{@code Signed} của đơn (phần cascade
 * booking không được tự làm vì ranh giới module). Lỗi ở listener rollback cả việc huỷ đơn.
 */
public record RentalOrderCanceled(Long orderId, Long unitId, String reason) {
}
