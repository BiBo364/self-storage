# Lịch hẹn & phân công FS

- **Route:** `/fm/appointments`
- **Actor:** Facility Manager
- **Flow tham chiếu:** 2.1 / 5.3 (branch `specs/flow-2-2.5`, `specs/flow-5`)

## Mục đích
FM xem toàn bộ `Appointment` (check-in, trả kho) của cơ sở theo ngày và phân công FS phụ trách từng lịch hẹn.

## Navigation

- **Vào từ:** FM shell hoặc notification.
- **Đi tới:** `/staff/schedule` sau khi phân công; customer appointment context; `/fm/contracts` hoặc `/fm/return-requests` khi cần xem contract/request liên quan.

## Dữ liệu hiển thị
- `Appointment` của facility, theo ngày, gồm `type` (CHECKIN/RETURN), khách hàng, khung giờ, `staff_id` (null nếu chưa phân công)
- Filter `staff_id IS NULL` để xem nhanh lịch chưa phân công
- Danh sách FS khả dụng thuộc facility (`AccountFacilityAssignment`)

## Actions
- Chọn 1 `Appointment` chưa phân công → chọn FS → [Phân công]
- Xem lịch dạng calendar/list theo ngày

## States / UI trạng thái
- Badge "Chưa phân công" nổi bật (màu cảnh báo) để FM ưu tiên xử lý trước giờ hẹn
- Trạng thái `Appointment.status`: Pending/Done/Canceled (no-show)

## API liên quan
- `GET /api/fm/appointments?date=`
- `POST /api/fm/appointments/{id}/assign` (body: `staff_id`)

## Edge case / Lưu ý UX
- `Appointment(type=RETURN)` được **tự động tạo bởi hệ thống** khi FM phân công FS ở `fm/04-return-request-queue.md` (qua `ReturnRequest.assigned_staff_id`) — trang này chỉ cần **đọc** lịch RETURN đã có sẵn `staff_id`, không tự phân công lại ở đây, tránh gây nhầm lẫn 2 nơi phân công cùng 1 việc
- Validate FS được gán phải thuộc đúng `AccountFacilityAssignment` của facility này — nếu danh sách FS hiển thị sai facility là lỗi nghiêm trọng (rủi ro bảo mật/IDOR), cần kiểm tra kỹ khi tích hợp API
