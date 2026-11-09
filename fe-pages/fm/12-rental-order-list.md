# Theo dõi đơn thuê kho

- **Route:** `/fm/rental-orders`
- **Actor:** Facility Manager
- **Flow tham chiếu:** Flow 1 (vòng đời `RentalOrder`)

## Mục đích

FM xem toàn bộ đơn thuê của cơ sở, lọc theo trạng thái, mở chi tiết và chuyển sang re-propose khi
cần. Đây là nơi theo dõi đơn sau khi `RentalRequest` đã convert — không thay thế hàng chờ
`RentalRequest` (`/fm/rental-requests`).

## Navigation

- **Vào từ:** FM shell, notification, hàng chờ yêu cầu (nút **Mở Đơn thuê kho** với
  `?orderId={id}`), trang đề xuất lại.
- **Đi tới:** detail panel; `/fm/rental-orders/{id}/re-propose`; `/fm/contracts`.

## Dữ liệu hiển thị

- Nguồn: `GET /api/fm/rental-orders?facilityId=&page=0&size=100`; cơ sở lấy từ `GET /api/fm/facility`.
- Mỗi dòng: `id`, `status`, `cancelReason`, `expiresAt`, `unitCode`, khách (`customerName`,
  `customerEmail`, `customerPhone`), `lastProposal`, `rejectionCount`/`maxRejectionCount`.
- `status`: `Pending` / `Deposited` / `Scheduled` / `InProgress` / `Done` / `Canceled`; `Canceled`
  kèm `cancelReason`.
- Badge **Cần đề xuất lại** khi `needsRepropose = true` (flag BE tính, không phải raw
  `needsReproposal`).
- Không hiển thị PII thừa ngoài nhu cầu vận hành.

## Actions

- Lọc theo trạng thái ở client; tìm theo mã đơn / tên khách / email / mã khoang.
- Mở detail panel (thông tin khách, khoang, hạn đơn, lý do huỷ, đề xuất gần nhất, số lần từ chối).
- `Đề xuất lại khoang` khi `needsRepropose = true` → `/fm/rental-orders/{id}/re-propose`.
- `Huỷ đơn` khi `availableActions` có `CANCEL` → ô lý do (trống = "Khách không còn nhu cầu") → xác nhận gọi
  `POST /api/fm/rental-orders/{id}/cancel`; thành công thì tải lại danh sách và hiện thông báo.
- Deep link `?orderId={id}` mở đúng detail panel của đơn đó.

## States / UI trạng thái

- Loading: "Đang tải đơn thuê…".
- Empty: "Không có đơn phù hợp bộ lọc".
- Error: SurfaceState lỗi + "Thử lại"; 403/404 → forbidden "Bạn không có quyền xem đơn thuê cơ sở".
- Đơn `Done` / `Canceled`: không có nút re-propose.

## API liên quan

- `GET /api/fm/facility` — cơ sở FM đang phụ trách (`facilityId`).
- `GET /api/fm/rental-orders?facilityId=&status=&page=&size=` — danh sách đơn của cơ sở.
- `GET /api/fm/rental-orders/{id}` — chi tiết đơn, dùng cho trang re-propose.
- `POST /api/fm/rental-orders/{id}/cancel` — FM huỷ đơn (Cancellation Cascade).

## Edge case / Lưu ý UX

- `RentalOrder.status` không có `Expired`: quá hạn là `Canceled` + `cancelReason`.
- Facility scope kiểm ở BE (`rental_order.read`); FM chỉ thấy đơn cơ sở mình, sai cơ sở → 403.
- Sort `expires_at` tăng dần (null cuối) để đơn gần hạn lên trước.
- FE tải tối đa 100 đơn đầu tiên rồi lọc/tìm ở client; chưa phân trang UI.
