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

- Trả `Invoice` theo id: `code`, `title`, `description`, `type`, `status`, `amount`, `dueDate`,
  `orderId`. Giá trị `type`/`status` giữ nguyên dạng PascalCase như trong DB.
- Hóa đơn không tồn tại và hóa đơn của người khác trả **cùng một** 404 — khác nhau là tiết lộ id
  nào đang có thật.
- **Không** kiểm xác minh email như đường mở phiên thanh toán: đây là phép đọc dữ liệu của chính
  người gọi, và khách phải xác minh xong mới đi tới được bước có hóa đơn.
- Chỉ đọc, không đổi gì, không ghi audit.

## Liên quan

- [invoice-pay.md](invoice-pay.md) · [routes.md](../../routes.md)
