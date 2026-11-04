---
auth-model: ownership
purpose: Khách đọc một hóa đơn của chính mình — FE dùng để lấy trạng thái thật sau khi cổng thanh toán redirect về.
---
# GET /api/invoices/{invoiceId}

- **Actor / quyền:** khách đã đăng nhập, phải là chủ hóa đơn.
- **Contract chi tiết:** annotation tại `InvoicePaymentController.java`; Swagger UI —
  `http://localhost:8080/swagger-ui/index.html`.

## Vì sao cần

Return URL của cổng thanh toán chỉ nói **giao dịch** thành công hay không. Trạng thái hóa đơn do
IPN chốt ở phía server, và IPN có thể về sau khi khách đã quay lại trang kết quả. FE đọc lại hóa
đơn ở đây để hiển thị trạng thái thật thay vì suy từ tham số trên URL.

Hóa đơn còn `Unpaid` ngay sau khi trả tiền là trạng thái hợp lệ, không phải lỗi: IPN chưa về.

## Hành vi

- Projection **dùng chung với danh sách hóa đơn** ([invoice-list.md](invoice-list.md)): `id`,
  `orderId`, `code`, `title`, `description`, `type`, `status`, `amount`, `createdAt`, `dueDate`,
  `unitId`, `unitCode`, `facilityId`, `facilityName`. Một hợp đồng thì một chỗ dựng — cùng đọc qua
  `BookingReadRepository`, không map entity thành dạng thứ hai.
- `unitId` / `unitCode` / `facilityId` / `facilityName` là `null` khi hóa đơn không gắn đơn thuê
  (hóa đơn theo hợp đồng). Giá trị `type`/`status` giữ nguyên dạng PascalCase như trong DB.
- Hóa đơn không tồn tại và hóa đơn của người khác trả **cùng một** 404 — khác nhau là tiết lộ id
  nào đang có thật. Quyền sở hữu nằm trong điều kiện truy vấn, không lọc sau khi đã đọc.
- Không kiểm xác minh email trong service: account chưa xác minh không được cấp `ACCOUNT_ACTIVE`
  nên đã bị chặn 403 ở tầng filter trước khi tới đây.
- Chỉ đọc, không đổi gì, không ghi audit.

## Liên quan

- [invoice-list.md](invoice-list.md) · [invoice-pay.md](invoice-pay.md) · [routes.md](../../routes.md)
