package vn.lemar.selfstorage.booking.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import vn.lemar.selfstorage.booking.repository.AppointmentReadRepository.AppointmentRow;

/** Một lịch hẹn trên màn lịch theo ngày của FM. */
public record FmAppointmentResponse(
        @Schema(description = "Id lịch hẹn") Long id,
        @Schema(description = "Loại lịch", example = "CHECKIN") String type,
        @Schema(description = "Trạng thái lịch", example = "Pending") String status,
        @Schema(description = "Ngày hẹn") LocalDate date,
        @Schema(description = "Giờ bắt đầu khung hẹn (null nếu chưa có)") Instant startedAt,
        @Schema(description = "Giờ kết thúc khung hẹn (null nếu chưa có)") Instant endAt,
        @Schema(description = "Đơn thuê nối với lịch (null nếu lịch không thuộc đơn thuê)") Long orderId,
        @Schema(description = "Trạng thái đơn thuê", example = "Scheduled") String orderStatus,
        @Schema(description = "Mã khoang của đơn") String unitCode,
        @Schema(description = "Email khách") String customerEmail,
        @Schema(description = "Id FS phụ trách, null khi chưa phân công") Long staffId,
        @Schema(description = "Email FS phụ trách") String staffEmail
) {

    public static FmAppointmentResponse from(AppointmentRow row) {
        return new FmAppointmentResponse(row.id(), row.type(), row.status(), row.date(), row.startedAt(),
                row.endAt(), row.orderId(), row.orderStatus(), row.unitCode(), row.customerEmail(), row.staffId(),
                row.staffEmail());
    }
}
