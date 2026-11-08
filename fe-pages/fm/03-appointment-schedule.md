# Lịch hẹn & phân công FS

- **Route:** `/fm/appointments`
- **Actor:** Facility Manager
- **Flow tham chiếu:** 2.1 / 5.3

## Mục đích

FM xem lịch check-in đang `Pending` của cơ sở theo ngày và phân công FS phụ trách.

## Dữ liệu hiển thị

- Nguồn: `GET /api/fm/appointments?facilityId=&date=`.
- Chỉ trả `CHECKIN` + `Pending`; `Done`, `Canceled` và lịch `RETURN` không thuộc màn này.
- Mỗi dòng: khách, khoang, khung giờ (giờ VN), FS phụ trách, trạng thái đơn.
- `attempt` / `rebooked`: lần đặt check-in thứ mấy của đơn (tính cả lịch sử, không phụ thuộc ngày
  đang xem). `rebooked = attempt > 1`.
- `rebookReason`: lý do lần trước; ưu tiên `handover_records.reject_reason`, fallback
  `appointments.cancel_reason`. `previousCancelReason`: đúng lý do huỷ lịch của lần trước.
- UI hiển thị badge **Đặt lại lần N** khi `rebooked`; chỉ hiện một lý do chính là `rebookReason`
  (fallback `previousCancelReason`) để tránh trùng lặp.
- Filter "Chỉ chưa phân công" (`staffId == null`).
- Ô chọn ngày, mặc định ngày hiện tại của trình duyệt.

## Actions

- Chọn row → mở panel chi tiết.
- Chọn FS từ danh sách `GET /api/fm/facilities/{facilityId}/staff` → `POST
  /api/fm/appointments/{appointmentId}/staff` body `{staffId}`.
- Phân công thành công: đóng panel, tải lại ngày; lịch đã gán vẫn hiện với `orderStatus =
  InProgress`.
- 409 từ BE: hiện message và tải lại ngày.

## States / UI trạng thái

- Loading: "Đang tải lịch hẹn…".
- Empty: "Không có lịch check-in ngày dd/MM/yyyy phù hợp bộ lọc".
- Error: SurfaceState lỗi + "Thử lại".
- 403/404: forbidden — không có quyền xem cơ sở.

## API liên quan

- `GET /api/fm/facility` — cơ sở FM đang phụ trách (lấy `facilityId`).
- `GET /api/fm/facilities/{facilityId}/staff` — FS đang hoạt động của cơ sở.
- `GET /api/fm/appointments?facilityId=&date=` — lịch check-in trong ngày.
- `POST /api/fm/appointments/{appointmentId}/staff` — phân công FS.

## Edge case / Lưu ý UX

- Lịch `RETURN` và lịch đã `Done`/`Canceled` không hiện ở màn này; không phân công lại.
- Đổi FS đã gán không được ở MVP; BE trả 409.
- FS phải thuộc đúng cơ sở (`AccountFacilityAssignment`); sai cơ sở là lỗi quyền.
- Ngày mặc định theo local browser; giờ hiển thị theo `Asia/Ho_Chi_Minh`.
- `rebooked` chỉ nói "không phải lần đầu"; lý do cụ thể nằm ở `rebookReason` /
  `previousCancelReason`.
