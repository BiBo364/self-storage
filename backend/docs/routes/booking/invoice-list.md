---
auth-model: ownership
purpose: Khách xem toàn bộ hoá đơn của mình — danh sách cho trang hoá đơn.
---
# GET /api/invoices

Trả toàn bộ hoá đơn của account đang đăng nhập, mới nhất trước.

- **Actor / quyền:** khách đã đăng nhập; chỉ hoá đơn của chính account gọi.
- **Contract chi tiết:** annotation tại `InvoiceReadController.java`; Swagger UI —
  `http://localhost:8080/swagger-ui/index.html`.

## Luồng / hành vi

- Sắp xếp `created_at desc, id desc`; MVP không lọc, không phân trang.
- Mỗi dòng: `id`, `orderId`, `code`, `title`, `description`, `type`, `status`, `amount`,
  `createdAt`, `dueDate`, `unitId`, `unitCode`, `facilityId`, `facilityName`.
- `type`/`status` giữ PascalCase như DB (`Deposit`, `Unpaid`).
- `unitCode`/`facility` lấy qua `invoices.order_id → rental_orders.unit_id → storage_units → facilities`;
  null khi hoá đơn chưa gắn khoang.
- Không trả `customerId`, dữ liệu thanh toán hay `discountAmount` (DB không có cột).
- Không có hoá đơn → `200` với `data: []`.
- Không có invoice của account khác trong danh sách; ownership lọc theo `invoices.customer_id`.

## Ghi chú nghiệp vụ

- Route nằm trong `/api/**` chung, không dùng prefix role-bound `/api/customer/**`.
- Đọc dữ liệu của chính mình nên không kiểm RBAC permission; `invoice.read` trong catalog là `own`.
- Không có route đọc chi tiết một hoá đơn theo id; FE dùng dòng trong danh sách này. Trên `/api/invoices/{id}` chỉ
  có `POST …/pay` và `POST …/payment-check`.

## Ví dụ

```bash
curl -s -H "Authorization: Bearer $CUSTOMER_TOKEN" \
  http://localhost:8080/api/invoices
# {"message":"Lấy danh sách hoá đơn thành công","data":[{"id":1,"orderId":1,"code":"INV-CURL-001","title":"Đặt cọc khoang A-01","description":null,"type":"Deposit","status":"Unpaid","amount":500000.00,"createdAt":"2026-11-01T08:16:53.907110Z","dueDate":"2026-11-03T08:16:53.907110Z","unitId":1,"unitCode":"A-01","facilityId":1,"facilityName":"Self Storage Quận 7"}]}
```

## Liên quan

- Xem thêm: [routes.md](../../routes.md) · [index.md](../../index.md)
