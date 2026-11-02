# Checklist bàn giao on-site (check-in → ký hợp đồng → bàn giao khóa)

- **Route:** `/staff/appointments/{id}/handover`
- **Actor:** Facility Staff
- **Flow tham chiếu:** 2.2 – 2.4 (branch `specs/flow-2-2.5`)

## Mục đích
Một trang duy nhất dẫn dắt FS qua toàn bộ tiến trình bàn giao khoang cho khách theo dạng checklist tuần tự, ghi nhận trên `HandoverRecord` (mỗi bước bật 1 cờ kèm timestamp).

## Navigation

- **Vào từ:** `/staff/schedule` từ appointment CHECKIN.
- **Đi tới:** `/staff/schedule` sau khi hoàn tất; incident/support surface nếu có vấn đề; customer theo dõi contract/appointment qua notification.

## Dữ liệu hiển thị
- Thông tin đơn: khách hàng, khoang, `RentalOrder`
- 4 bước checklist theo thứ tự bắt buộc (không cho nhảy bước):
  1. **Xác minh danh tính** (`is_identity_verified`) — đối chiếu giấy tờ với `RentalOrder.customer_id`, hiển thị lại ảnh giấy tờ khách upload online nếu có
  2. **Kiểm tra khoang chứa** (`is_unit_inspected`) — nhập `inspection_notes`, `inspection_photos`
  3. **Ký hợp đồng** (`is_contract_signed`) — sinh `RentalContract(Draft)`, FS upload ảnh/scan hợp đồng đã ký giấy
  4. **Thanh toán tháng đầu** (`is_payment_settled`) — theo dõi `Invoice(type=Rental)` qua VNPay
- Sau đủ 4 cờ: bước **Bàn giao khóa** — chọn `access_type` (MVP chỉ `PhysicalKey`), nhập `quantity`, khách ký nhận

## Actions
- [Xác minh đạt] / [Không đạt] (dừng tiến trình, sang nhánh Reject)
- [Xác nhận hiện trạng khoang] (khách đồng ý) / [Khách từ chối khoang] (kết thúc, `result = REJECTED`)
- [Sinh hợp đồng] → [Upload ảnh hợp đồng đã ký]
- [Xác nhận đã thanh toán] (theo dõi trạng thái `Invoice`, không tự nhập tay)
- [Hoàn tất bàn giao] — chỉ enable khi đủ 4 cờ
- [Xoá ảnh/file] — chỉ hiện trên file đã upload **trong phase hiện tại**; xoá file khỏi checklist.

## Upload media

- Ảnh hiện trạng và bản scan hợp đồng upload qua `POST /api/media/uploads` (field `file`, `batchId`
  optional); response trả `batchId`.
- Mỗi file có một `batchId`. Trong phase hiện tại, mỗi ảnh/file có nút X →
  `DELETE /api/media/uploads/batch/{batchId}`; file biến mất khỏi checklist.
- Hủy/rời trang **không xoá**; batch `Pending` được giữ để quay lại.
- Khi submit phase (“Khách đồng ý” / “Xác nhận đã ký”), gọi
  `POST /api/media/uploads/batch/{batchId}/attach`; sau đó file không sửa/xoá từ checklist.
- Order `Canceled` không xoá media (giữ evidence); order hard-delete/retention xử lý riêng.

## States / UI trạng thái
- Stepper hiển thị rõ bước hiện tại, các bước đã hoàn thành có dấu tick + timestamp
- Nhánh "Khách từ chối khoang" — dừng hẳn tiến trình, hiển thị thông báo đơn được chuyển về Flow 1 để FM chỉ định khoang khác (FS không cần làm gì thêm)
- Nhánh "Chưa thanh toán xong trong buổi hẹn" — giữ nguyên `IN_PROGRESS`, khoang vẫn `Reserved`, hóa đơn chờ khách tự thanh toán online sau; FS thấy trạng thái "Chờ thanh toán" và có thể rời trang, quay lại sau
- [Hoàn tất bàn giao] bị disable + tooltip giải thích rõ đang thiếu cờ nào nếu bấm sớm

## API liên quan
- `POST /api/media/uploads` (field `file`, `batchId` optional)
- `DELETE /api/media/uploads/batch/{batchId}` (X trên file trong phase hiện tại)
- `POST /api/media/uploads/batch/{batchId}/attach` (submit phase)
- `POST /api/staff/handover-records/{id}/verify-identity`
- `POST /api/staff/handover-records/{id}/inspection` (body: `inspection_notes`, `inspection_photos[]`)
- `POST /api/staff/handover-records/{id}/reject` (body: `reject_reason`)
- `POST /api/customer/rental-orders/{id}/contracts` (sinh Draft)
- `POST /api/staff/contracts/{id}/sign` (upload ảnh/scan)
- `POST /api/invoices/{id}/pay` (nếu FS hỗ trợ khởi tạo thanh toán tại quầy)
- `POST /api/staff/handover-records/{id}/complete` (body: `access_type`, `access_quantity`, `customer_signature`, `note?`)

## Edge case / Lưu ý UX
- Backend kiểm tra đủ 4 cờ trước khi cho `/complete` — nếu thiếu, trả lỗi rõ ràng, FE hiển thị đúng bước còn thiếu thay vì lỗi chung chung
- Quá `Policy.handover.payment_grace_hours` mà chưa thanh toán → hợp đồng tự `Canceled`, khoang trả về `Available` (cron, không phải thao tác FS) — nếu FS mở lại trang sau khi cron đã chạy, cần hiển thị trạng thái "Đơn đã bị hủy do quá hạn thanh toán" thay vì tiếp tục checklist
- MVP không sinh PDF tự động — chỉ upload ảnh/scan, giữ UI đơn giản (upload + preview), không cần trình ký điện tử
- MVP chỉ hỗ trợ khóa cơ (`PhysicalKey`) — không thiết kế UI chọn "khóa mã số" trong MVP dù schema có chừa chỗ
