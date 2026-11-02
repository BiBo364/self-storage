# Theo dõi đơn thuê kho

- **Route:** `/fm/rental-orders`
- **Actor:** Facility Manager
- **Flow tham chiếu:** Flow 1 (vòng đời `RentalOrder`) / `specs/db-table-draft.md` › RentalOrder

## Mục đích

FM xem toàn bộ đơn thuê của cơ sở, lọc theo trạng thái, mở chi tiết và chuyển sang re-propose khi cần. Đây là page còn thiếu trong inventory FM trước đây — không thay thế hàng chờ `RentalRequest`.

## Navigation

- **Vào từ:** FM shell, notification, trang đề xuất lại.
- **Đi tới:** detail panel; `/fm/rental-orders/:id/re-propose`; `/fm/contracts`.

## Dữ liệu hiển thị

- Mã đơn, khách, email, khoang, loại khoang, cơ sở.
- `status`: `Pending` / `Deposited` / `Scheduled` / `InProgress` / `Done` / `Canceled`; `Canceled` kèm `cancel_reason`.
- `created_at`, `expires_at`, cờ `needs_reproposal`.
- Không hiển thị PII thừa ngoài nhu cầu vận hành.

## Actions

- Lọc theo trạng thái; tìm theo mã đơn / tên khách / email / mã khoang.
- Mở detail panel.
- `Đề xuất lại khoang` khi đơn chưa đóng và cần đề xuất lại.

## States / UI trạng thái

- Loading khi fetch danh sách.
- Empty khi filter không có kết quả.
- Error khi fetch lỗi; forbidden nếu đơn ngoài facility.
- Đơn `Done` / `Canceled`: không có action re-propose.
- Nút re-propose chỉ hiện khi đơn chưa đóng và cần đề xuất lại.

## API liên quan

- `GET /api/fm/rental-orders?facilityId=&status=&page=&size=` — API list chưa có; cần bổ sung contract + backend.
- `GET /api/fm/rental-orders/{id}` — đã có, dùng cho detail/re-propose.

## Edge case / Lưu ý UX

- `RentalOrder.status` không có `Expired`: quá hạn là `Canceled` + `cancel_reason`.
- Facility scope: FM chỉ thấy đơn thuộc cơ sở; BE trả 403 nếu sai.
- Không cho re-propose đơn `Done` / `Canceled`.
- Sort `expires_at` tăng dần để đơn gần hạn lên trước.
- Page hiện là prototype mock; khi nối API, bỏ mock và dùng `available_actions` nếu BE trả.
