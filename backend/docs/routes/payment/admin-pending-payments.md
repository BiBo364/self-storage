---
auth-model: permission-bound
purpose: ADMIN xem case Pending quá hạn và mở chi tiết để đối soát portal VNPay.
---
# GET /api/admin/payments/pending?olderThanMinutes=&page=&size=

Trả các case `Open` cho giao dịch `PAY/Pending` quá ngưỡng. Route read-only; reconcile vẫn qua
`POST /api/admin/payments/{txnRef}/reconcile`.

- **Actor / quyền:** role `ADMIN`.
- **Contract chi tiết:** annotation tại `AdminPendingPaymentController.java`; Swagger UI —
  `http://localhost:8080/swagger-ui/index.html`.

## Luồng / hành vi

- `olderThanMinutes` mặc định 15; sai → `400`.
- `page` bắt đầu từ 0; `size` mặc định 20, tối đa 100; sai → `400`.
- Chỉ trả case `Open` gắn transaction còn `Pending`; transaction đã Success/Failed/Duplicate tự rời list.
- Mỗi dòng: `caseId`, `transactionId`, `invoiceId`, `vnpTxnRef`, `amount`, `transactionCreatedAt`,
  `detectedAt`, `notifiedAt`, `pendingAgeMinutes`, `status`, `resolution`, `gatewayTransactionNo`,
  `paidAt`, `note`, `evidenceUrl`, `resolvedAt`, `resolvedByEmail`.
- Case Open thường chưa có `resolution`/`evidenceUrl`; Admin nhập khi reconcile.
- Khoản cần hoàn tiền không nằm trong list này; xem `GET /api/admin/payments/refunds`.

## Evidence

- `evidenceUrl` optional, lấy từ `POST /api/media/uploads`.
- Là ảnh/PDF Admin chụp portal VNPay, hoặc màn hình tra cứu không tìm thấy giao dịch.
- Không phải nguồn sự thật và không thay thế `note`/`gatewayTransactionNo`.
- FE hiển thị “Bằng chứng đối soát (không bắt buộc)”; null → “Không có bằng chứng đính kèm”.
- Chỉ ADMIN xem; không đưa evidence cho FM/customer.

## Ghi chú nghiệp vụ

- `PendingPaymentWatchdogJob` tạo case cho Pending quá 15 phút, mỗi transaction tối đa một case.
- Job chỉ alert; không đổi trạng thái, không gọi cổng.
- Case được đóng khi ADMIN reconcile hoặc IPN chốt giao dịch.

## Ví dụ

```bash
curl -s -H "Authorization: Bearer $ADMIN_TOKEN" \
  "http://localhost:8080/api/admin/payments/pending?olderThanMinutes=15&page=0&size=20"
# {"message":"Lấy danh sách giao dịch Pending thành công","data":{"items":[{"caseId":1,"transactionId":1,"invoiceId":1,"vnpTxnRef":"TXN-E2E-216","amount":500000.00,"transactionCreatedAt":"2026-11-04T13:51:44.947742Z","detectedAt":"2026-11-04T14:22:06.253465Z","notifiedAt":"2026-11-04T14:22:06.312388Z","pendingAgeMinutes":30,"status":"Open","resolution":null,"gatewayTransactionNo":null,"paidAt":null,"note":null,"evidenceUrl":null,"resolvedAt":null,"resolvedByEmail":null}],"page":0,"size":20,"totalElements":1,"totalPages":1}}
```

## Liên quan

- Reconcile: [manual-reconcile.md](manual-reconcile.md)
- Khoản chờ hoàn tiền: [admin-refund-required.md](admin-refund-required.md)
- Xem thêm: [routes.md](../../routes.md) · [index.md](../../index.md)
