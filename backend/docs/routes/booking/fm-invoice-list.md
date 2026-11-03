---
auth-model: permission-bound
purpose: Danh sách hoá đơn của cơ sở FM, kèm trạng thái giao dịch gần nhất để highlight Pending cần đối soát.
---
# GET /api/fm/invoices?facilityId=&status=&paymentStatus=&olderThanMinutes=

Trả hoá đơn thuộc cơ sở FM đang phụ trách, mới nhất trước. Route read-only: không đổi invoice,
không đổi payment, không gọi cổng.

- **Actor / quyền:** role `FM`; `invoice.read` scope `FACILITY` (403 nếu ngoài cơ sở).
- **Contract chi tiết:** annotation tại `FmInvoiceController.java`; Swagger UI —
  `http://localhost:8080/swagger-ui/index.html`.

## Luồng / hành vi

- `facilityId` bắt buộc.
- `status` optional: `Unpaid` / `Paid` / `Canceled` / `Expired`; sai → `400`.
- `paymentStatus` optional: `Pending` / `Success` / `Failed` / `Duplicate`; lọc theo giao dịch
  `PAY` gần nhất; sai → `400`.
- `olderThanMinutes` optional: chỉ giữ giao dịch `Pending` đã quá số phút này; sai → `400`.
- Mỗi dòng gồm thông tin hoá đơn, đơn/khoang/khách và:
  - `paymentStatus`, `vnpTxnRef`, `paymentCreatedAt`, `paidAt`;
  - `pendingAgeMinutes` — 0 khi giao dịch gần nhất không phải `Pending`;
  - `needsReconciliation` — `Pending` quá 15 phút;
  - `duplicateRefundRequired` — giao dịch gần nhất là `Duplicate`.
- `needsReconciliation=true` là tín hiệu để FE highlight; quyết định đối soát vẫn là thao tác
  ADMIN qua `POST /api/admin/payments/{txnRef}/reconcile`.
- Không có hoá đơn → `200` với `data: []`.

## Ghi chú nghiệp vụ

- Quyền `invoice.read` có hai cách dùng: khách đọc hoá đơn của mình bằng ownership; FM đọc hoá đơn
  cơ sở bằng scope `FACILITY`. Route này dùng cách thứ hai.
- Payment state lấy qua public query của module `payment`, không import repository chéo module.
- Route này không thay đổi trạng thái và không thay thế đối soát thủ công.
- MVP chỉ trả hoá đơn gắn đơn thuê/khoang; hoá đơn hợp đồng chưa có facility projection sẽ bổ sung
  khi Flow 2 có màn hình tương ứng.

## Ví dụ

```bash
curl -s -H "Authorization: Bearer $FM_TOKEN" \
  "http://localhost:8080/api/fm/invoices?facilityId=1&paymentStatus=Pending&olderThanMinutes=15"
# {"message":"Lấy danh sách hoá đơn cơ sở thành công","data":[{"id":1,"orderId":1,"code":"INV-FM-DEMO-001","title":"Đặt cọc khoang A-01","description":null,"type":"Deposit","status":"Unpaid","amount":500000.00,"createdAt":"2026-11-03T14:53:03.466638Z","dueDate":"2026-11-05T15:23:03.466638Z","unitId":1,"unitCode":"A-01","facilityId":1,"facilityName":"Self Storage Quận 7","customerId":6,"customerName":"Khách demo","customerEmail":"customer1@lemar.vn","paymentStatus":"Pending","vnpTxnRef":"TXN-FM-DEMO-001","paymentCreatedAt":"2026-11-03T15:03:03.530740Z","paidAt":null,"pendingAgeMinutes":20,"needsReconciliation":true,"duplicateRefundRequired":false}]}
```

## Liên quan

- Đối soát: [manual-reconcile.md](../payment/manual-reconcile.md)
- Xem thêm: [routes.md](../../routes.md) · [index.md](../../index.md)
