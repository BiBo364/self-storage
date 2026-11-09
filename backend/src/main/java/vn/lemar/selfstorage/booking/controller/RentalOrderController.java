package vn.lemar.selfstorage.booking.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.lemar.selfstorage.ApiEnvelope;
import vn.lemar.selfstorage.booking.application.AppointmentBookingService;
import vn.lemar.selfstorage.booking.application.AppointmentBookingService.BookedAppointment;
import vn.lemar.selfstorage.booking.application.AppointmentBookingService.SlotWindow;
import vn.lemar.selfstorage.booking.application.OrderOverviewQueryService;
import vn.lemar.selfstorage.booking.application.OrderOverviewQueryService.OrderOverview;
import vn.lemar.selfstorage.booking.application.RentalOrderCancelService;
import vn.lemar.selfstorage.booking.application.RentalOrderCancelService.CancelResult;
import vn.lemar.selfstorage.identity.application.AccountQueryService;

/**
 * Đơn thuê phía khách: "Kho của tôi" (spec 1.3 NOTES), chọn lịch check-in (spec 1.5), huỷ đơn (spec 1.5 NOTES/2.5).
 * Route ownership trong {@code /api/**} ({@code ACCOUNT_ACTIVE}); đơn của người khác → 404.
 */
@RestController
@RequestMapping("/api/rental-orders")
public class RentalOrderController {

    static final String LIST_MESSAGE = "Lấy danh sách đơn thuê thành công";
    static final String DETAIL_MESSAGE = "Lấy chi tiết đơn thuê thành công";
    static final String SLOTS_MESSAGE = "Lấy khung giờ check-in thành công";
    static final String BOOK_MESSAGE = "Đã đặt lịch hẹn check-in";
    static final String CANCEL_MESSAGE = "Đã huỷ đơn thuê";

    private final OrderOverviewQueryService overviewQuery;
    private final AppointmentBookingService bookingService;
    private final RentalOrderCancelService cancelService;
    private final AccountQueryService accountQueryService;

    public RentalOrderController(OrderOverviewQueryService overviewQuery, AppointmentBookingService bookingService,
                                 RentalOrderCancelService cancelService, AccountQueryService accountQueryService) {
        this.overviewQuery = overviewQuery;
        this.bookingService = bookingService;
        this.cancelService = cancelService;
        this.accountQueryService = accountQueryService;
    }

    @Operation(summary = "Đơn thuê của tôi",
            description = "Mỗi đơn kèm nhu cầu, khoang, proposal / hoá đơn / lịch hẹn / biên bản / hợp đồng mới nhất "
                    + "và `availableActions` (RESPOND_PROPOSAL, PAY_INVOICE, BOOK_CHECKIN, HANDOVER, VIEW_CONTRACT, "
                    + "CANCEL) — FE chỉ hiển thị nút theo danh sách này.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Lấy danh sách thành công"),
            @ApiResponse(responseCode = "401", description = "Thiếu / sai / hết hạn token")
    })
    @GetMapping
    public ResponseEntity<ApiEnvelope<List<OrderOverview>>> list(Authentication authentication) {
        return ResponseEntity.ok(ApiEnvelope.ok(LIST_MESSAGE, overviewQuery.listForCustomer(me(authentication))));
    }

    @Operation(summary = "Chi tiết đơn thuê của tôi")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Lấy chi tiết thành công"),
            @ApiResponse(responseCode = "404", description = "Đơn không tồn tại hoặc không thuộc khách")
    })
    @GetMapping("/{orderId}")
    public ResponseEntity<ApiEnvelope<OrderOverview>> detail(Authentication authentication,
                                                             @PathVariable Long orderId) {
        return ResponseEntity.ok(ApiEnvelope.ok(DETAIL_MESSAGE,
                overviewQuery.detailForCustomer(orderId, me(authentication))));
    }

    @Operation(summary = "Khung giờ check-in của đơn",
            description = "Đơn phải `Deposited`. Cửa sổ: từ hôm nay tới `min(lúc cọc + "
                    + "appointment.max_days_after_deposit, expires_at)`; 3 khung cố định 08:00–10:00, 13:00–15:00, "
                    + "17:00–19:00 (giờ Việt Nam), `available = false` khi khung đã qua, đã có người đặt hoặc kết "
                    + "thúc sau hạn đơn.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Cửa sổ ngày + khung giờ"),
            @ApiResponse(responseCode = "404", description = "Đơn không tồn tại hoặc không thuộc khách"),
            @ApiResponse(responseCode = "409", description = "Đơn không ở `Deposited`, đã có lịch, chưa xác định mốc "
                    + "cọc, hoặc đã quá `lúc cọc + appointment.booking_window_days`")
    })
    @GetMapping("/{orderId}/appointment-slots")
    public ResponseEntity<ApiEnvelope<SlotWindow>> slots(Authentication authentication, @PathVariable Long orderId) {
        return ResponseEntity.ok(ApiEnvelope.ok(SLOTS_MESSAGE, bookingService.slots(orderId, me(authentication))));
    }

    @Operation(summary = "Đặt lịch hẹn check-in",
            description = "Body `{date, startAt}` — `startAt` phải đúng giờ bắt đầu của một khung trong ngày "
                    + "(ISO-8601). Tạo `Appointment(CHECKIN, Pending)` + `RentalAppointment` + "
                    + "`HandoverRecord(IN_PROGRESS)`, đơn `Deposited → Scheduled`; khách + FM nhận "
                    + "`APPOINTMENT_CREATED`, FM nhận thêm `FS_ASSIGNMENT_REQUIRED` nếu cơ sở chưa có FS.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Đặt lịch thành công"),
            @ApiResponse(responseCode = "400", description = "Thiếu dữ liệu, ngày ngoài cửa sổ, khung không hợp lệ / "
                    + "đã qua"),
            @ApiResponse(responseCode = "404", description = "Đơn không tồn tại hoặc không thuộc khách"),
            @ApiResponse(responseCode = "409", description = "Khung đã có người đặt; đơn không ở `Deposited`; đã có "
                    + "lịch; quá hạn đặt lịch; không còn khung trước hạn giữ kho")
    })
    @PostMapping("/{orderId}/appointments")
    public ResponseEntity<ApiEnvelope<BookedAppointment>> book(Authentication authentication,
                                                               @PathVariable Long orderId,
                                                               @RequestBody BookRequest request) {
        BookedAppointment booked = bookingService.book(orderId, me(authentication),
                request == null ? null : request.date(), request == null ? null : request.startAt());
        return ResponseEntity.ok(ApiEnvelope.ok(BOOK_MESSAGE, booked));
    }

    @Operation(summary = "Huỷ đơn thuê",
            description = "Chạy Cancellation Cascade với lý do \"Khách hủy đơn\". Huỷ sau khi đã cọc thì mất cọc theo "
                    + "chính sách; FE phải hiển thị cảnh báo trước khi gọi.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Đã huỷ"),
            @ApiResponse(responseCode = "404", description = "Đơn không tồn tại hoặc không thuộc khách"),
            @ApiResponse(responseCode = "409", description = "Đơn đã đóng")
    })
    @PostMapping("/{orderId}/cancel")
    public ResponseEntity<ApiEnvelope<CancelResult>> cancel(Authentication authentication,
                                                            @PathVariable Long orderId) {
        return ResponseEntity.ok(ApiEnvelope.ok(CANCEL_MESSAGE,
                cancelService.cancelByCustomer(orderId, me(authentication))));
    }

    private Long me(Authentication authentication) {
        return accountQueryService.requireIdByEmail(authentication.getName());
    }

    @Schema(description = "Ngày + giờ bắt đầu khung hẹn")
    public record BookRequest(
            @Schema(example = "2026-11-10") LocalDate date,
            @Schema(example = "2026-11-10T01:00:00Z", description = "Giờ bắt đầu khung (08:00 giờ VN = 01:00Z)")
            Instant startAt) {
    }
}
