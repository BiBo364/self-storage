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
```

## Liên quan

- Reconcile: [manual-reconcile.md](manual-reconcile.md)
- Xem thêm: [routes.md](../../routes.md) · [index.md](../../index.md)
