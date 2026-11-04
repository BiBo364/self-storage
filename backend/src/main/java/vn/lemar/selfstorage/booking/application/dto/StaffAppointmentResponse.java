package vn.lemar.selfstorage.booking.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import vn.lemar.selfstorage.booking.domain.AppointmentNoShowPolicy;
import vn.lemar.selfstorage.booking.repository.AppointmentReadRepository.AppointmentRow;

/** Một lịch làm việc của FS trong ngày. */
public record StaffAppointmentResponse(
        @Schema(description = "Id lịch hẹn") Long id,
        @Schema(description = "Loại lịch", example = "CHECKIN") String type,
        @Schema(description = "Trạng thái lịch", example = "Pending") String status,
        @Schema(description = "Ngày hẹn") LocalDate date,
        @Schema(description = "Giờ bắt đầu khung hẹn") Instant startedAt,
        @Schema(description = "Giờ kết thúc khung hẹn") Instant endAt,
        @Schema(description = "Thời điểm khách đến, null nếu chưa đến") Instant arrivedAt,
        @Schema(description = "Đơn thuê nối với lịch") Long orderId,
        @Schema(description = "Mã khoang") String unitCode,
        @Schema(description = "Cơ sở") Facility facility,
        @Schema(description = "Khách") Customer customer,
        @Schema(description = "Đã quá end_at mà chưa đến — cron no-show sẽ xử lý") boolean noShowDue,
        @Schema(description = "Lý do huỷ; null nếu chưa huỷ; NoShow là cron no-show") String cancelReason
) {

    @Schema(name = "StaffAppointmentFacility")
    public record Facility(Long id, String name) {
    }

    @Schema(name = "StaffAppointmentCustomer")
    public record Customer(String name, String email, String phone) {
    }

    public static StaffAppointmentResponse from(AppointmentRow row, Instant now) {
        return new StaffAppointmentResponse(row.id(), row.type(), row.status(), row.date(), row.startedAt(),
                row.endAt(), row.arrivedAt(), row.orderId(), row.unitCode(),
                new Facility(row.facilityId(), row.facilityName()),
                new Customer(row.customerName(), row.customerEmail(), row.customerPhone()),
                AppointmentNoShowPolicy.isDue(row.type(), row.status(), row.endAt(), now), row.cancelReason());
    }
}
