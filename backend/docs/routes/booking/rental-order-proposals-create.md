---
auth-model: permission-bound
purpose: FM đề xuất khoang khác cho đơn sau khi khách từ chối hoặc khoang hết khả dụng.
---
# POST /api/rental-orders/{orderId}/proposals

Body `{ "unitId": 3, "overrideReason": "..." }` — tạo `ProposalFeedback` `Pending` mới cho đơn.

- **Actor / quyền:** bearer token + `ACCOUNT_ACTIVE` (route trong `/api/**`) + permission
  `proposal_feedback.repropose` theo cơ sở của đơn; chọn lại khoang khách đã từ chối cần thêm
  `proposal_feedback.override`. Role không có permission (vd khách) → 403.
- **Contract chi tiết:** annotation tại `booking/controller/RentalOrderProposalController.java`; Swagger UI —
  `http://localhost:8080/swagger-ui/index.html`.

## Luồng / hành vi

- Thiếu `unitId` → 400.
- Đơn đã quá `expires_at` → 409.
- Điều kiện được đề xuất lại: đơn chưa đóng **và** không có proposal `Pending` **và** đơn đang
  `Pending` hoặc `needs_reproposal = true`. Sai một trong ba → 409 với câu tương ứng.
- Khoá đơn, khoá khoang; khoang khác cơ sở → 404, khoang không `Available` → 409.
- Khoang khách đã từ chối trong đơn: thiếu `overrideReason` → 400; có thì lý do ghi vào AuditLog
  `PROPOSAL_REPROPOSED`.
- Hoá đơn `Deposit`/`DepositSupplement` `Unpaid` còn treo của đơn (khách đã đồng ý khoang cũ nhưng chưa trả) →
  `Canceled` + AuditLog `INVOICE_CANCELED` lý do "FM đề xuất lại khoang khác". Sau commit, phiên payOS/VNPay còn mở
  của các hoá đơn đó được huỷ ở cổng (`PaymentSessionCloser.closeAfterCommit`, best-effort) để khách không trả được
  vào link cũ. Proposal cũ giữ nguyên làm lịch sử.
- Proposal mới `Pending`; khách nhận `PROPOSAL_REPROPOSED` kèm link `<FRONTEND_URL>/proposals`.

## Ghi chú nghiệp vụ

- `needs_reproposal` do Flow 2 bật (vd khách từ chối khoang tại check-in) hoặc Flow 1.3 bật lại khi khách từ
  chối/không nhận được khoang trên đơn đã cọc; cờ được **clear ngay khi proposal mới tạo thành công** — chỉ
  dùng cho gate đề xuất lại, không phải điều kiện để khách đồng ý.
- **Quyết định của chủ dự án:** FM không đề xuất lại khi đơn còn proposal `Pending` (khách chưa phản hồi) —
  luôn 409 "Đơn đang có đề xuất chờ khách phản hồi". FM liên hệ khách ngoài hệ thống; không được thì huỷ đơn qua
  `POST /api/fm/rental-orders/{id}/cancel` ([fm-rental-order-cancel.md](fm-rental-order-cancel.md)).

## Ví dụ

```bash
TOKEN='<token của fm1@lemar.vn>'

curl -s -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"unitId":2}' http://localhost:8080/api/rental-orders/3/proposals
# {"message":"Khách đã từ chối khoang A-02, cần nhập lý do để đề xuất lại","timestamp":"2026-10-15T15:04:26.454383700Z"}

curl -s -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"unitId":3}' http://localhost:8080/api/rental-orders/3/proposals
# {"message":"Đã gửi đề xuất khoang mới cho khách","data":{"id":4,"orderId":3,"customerId":6,"unitId":3,"status":"Pending","note":null,"createdAt":"2026-10-15T15:04:26.551841800Z"}}

# Gọi tiếp khi proposal 4 còn Pending
# {"message":"Đơn đang có đề xuất chờ khách phản hồi","timestamp":"2026-10-15T15:04:26.673177400Z"}

# customer1@lemar.vn gọi (không có permission proposal_feedback.repropose)
# {"message":"Bạn không có quyền thực hiện thao tác này","timestamp":"2026-10-15T15:04:26.829600200Z"}

# Đơn 1: khách đã đồng ý A-01, hoá đơn cọc INV-DEP-Q7-261015-FCGZ4F còn Unpaid → FM đề xuất A-04
curl -s -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"unitId":4}' http://localhost:8080/api/rental-orders/1/proposals
# {"message":"Đã gửi đề xuất khoang mới cho khách","data":{"id":5,"orderId":1,"customerId":6,"unitId":4,"status":"Pending","note":null,"createdAt":"2026-10-15T15:04:27.059277100Z"}}
# Sau đó: invoices.status của INV-DEP-Q7-261015-FCGZ4F = Canceled; audit_logs có INVOICE_CANCELED "FM đề xuất lại khoang khác".
```

## Liên quan

- [fm-rental-order-detail.md](fm-rental-order-detail.md) · [routes.md](../../routes.md) · [index.md](../../index.md)
