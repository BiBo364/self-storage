# Quản lý hóa đơn cơ sở

- **Route:** `/fm/invoices`
- **Actor:** Facility Manager
- **Flow tham chiếu:** Cross-flow — suy ra từ mô tả actor FM trong draft.md ("Quản lý hóa đơn"), không có mô tả UI chi tiết riêng ở flow nào

## Mục đích
FM xem toàn bộ hóa đơn phát sinh từ khách hàng thuộc cơ sở mình phụ trách, phục vụ theo dõi công nợ/thanh toán tổng quát (không phải trang tạo hóa đơn thủ công — hóa đơn được hệ thống tự tạo theo từng flow).

## Navigation

- **Vào từ:** FM shell, `/fm/contracts` hoặc notification.
- **Đi tới:** invoice detail surface; `/fm/contracts` để xem contract; `/notifications` sau khi có cập nhật trạng thái.

## Dữ liệu hiển thị
- Danh sách `Invoice` thuộc các hợp đồng/đơn có `StorageUnit.facility_id = FM.facility`: `code`, `type`, khách hàng, `amount`, `status`, `due_date`
- Filter theo `type` (Deposit/Rental/Extension/Penalty/Service), `status`

## Actions
- Xem chi tiết 1 hóa đơn (liên kết ngược tới hợp đồng/đơn/khách hàng)
- MVP không có action tạo/sửa hóa đơn thủ công ở đây — mọi hóa đơn sinh ra tự động từ flow tương ứng (đặt cọc, gia hạn, trả kho, sự cố)
- Tab **Hoàn tiền**: hàng đợi hoá đơn `Required`/`Processing` của cơ sở; chọn một khoản → xem lịch sử hoàn + số còn hoàn được → nhập số tiền + ghi chú (lý do, mã chuyển khoản) → "Ghi nhận đã hoàn". Hoàn đủ thì khoản rời hàng đợi.

## States / UI trạng thái
- Tổng hợp nhanh (summary card): tổng công nợ `Unpaid`, tổng đã thu trong kỳ

## API liên quan
- `GET /api/fm/invoices?facilityId=&type=&status=&paymentStatus=&page=&size=`
- `GET /api/fm/invoices/refunds?facilityId=&page=&size=` — hàng đợi hoàn tiền
- `GET` / `POST /api/fm/invoices/{invoiceId}/refunds` — lịch sử / ghi nhận hoàn tiền thủ công

## Edge case / Lưu ý UX
- **Lưu ý:** đây là trang được đề xuất dựa trên mô tả actor (chưa có flow nào mô tả chi tiết UI này) — cần xác nhận với team xem có thực sự cần trang riêng hay chỉ cần xem hóa đơn lồng trong từng hợp đồng ở `fm/11-customer-contract-overview.md`
- Nếu triển khai, đảm bảo facility scope giống các trang FM khác (chỉ xem hóa đơn thuộc facility mình)
