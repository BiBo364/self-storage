---
auth-model: role-bound
purpose: FM duyệt yêu cầu đặt kho — gán khoang Available; email đã xác minh thì convert ngay sang đơn + proposal, response vẫn trả status Approved.
---

# POST /api/fm/rental-requests/{id}/approve

FM duyệt `RentalRequest` đang `Pending` bằng cách gán một khoang cụ thể.

- **Actor / quyền:** role-bound `FM` (`/api/fm/**` trong `SecurityConfig`) + permission
  `rental_request.approve` theo facility qua `Access.can(permission, facilityId)`.
  Guard đặt trong service (thay vì controller) vì cần `facilityId` từ entity mới guard được
  theo cơ sở.
- **Contract chi tiết:** annotation tại
  `booking/controller/FmRentalRequestApproveController.java`;
  Swagger UI — `http://localhost:8080/swagger-ui/index.html`.

## Luồng / hành vi (đúng spec `specs/draft.md` §1.1)

1. Request không tồn tại → 404, không đổi gì.
2. Chéo cơ sở / thiếu permission → 403 (permission kiểm trước, facility 404 sau — theo `Access`).
3. Request không còn `Pending` (2 FM / double-click) → 409 "Yêu cầu đã được xử lý bởi FM khác",
   không đổi gì.
4. Request đã quá `expires_at` → 409 "Yêu cầu đã quá hạn xử lý", không đổi gì (guard đọc
   `expires_at`, không đợi `BookingExpiryJob` đổi status sang `Expired`).
5. Tài khoản khách đang bị chặn (`AccountStatus.BANNED`) → request chuyển `Rejected` (reason
   `OTHER`, note "Tài khoản khách đã bị chặn"), audit `RENTAL_REQUEST_REJECTED`, gửi mail
   "Yêu cầu thuê kho bị từ chối" (tài khoản bị chặn, liên hệ CSKH nếu sai sót); response 200 với
   `status: "Rejected"`, `unitId`/`expiresAt` null. Không tạo đơn/proposal.
6. Khoang không tồn tại hoặc thuộc cơ sở khác → 404 "Không tìm thấy khoang", không đổi gì
   (khoang ngoài cơ sở trả cùng lỗi để không lộ tài nguyên ngoài phạm vi FM).
7. Khoang mất `Available` → 409 "Khoang đã không còn khả dụng, vui lòng chọn khoang khác",
   request giữ `Pending`.
8. Trước bước kiểm trùng, service khoá advisory theo email khách (giữ tới commit) để hai FM duyệt
   cùng khách chạy tuần tự. Khách đã có đơn/proposal **đang hoạt động** cho khoang đó (proposal
   `Pending`/`Agreed` trên đơn chưa `Canceled`/`Done`) → 409 "Khoang đã được gán cho yêu cầu khác
   của cùng khách hàng...", request giữ `Pending`, không noti; đơn cũ đã đóng thì được gán lại.
   Khác email + cùng khoang vẫn cho (cọc trước giữ trước).
9. OK → 1 transaction: conditional update `WHERE status='Pending'` (đúng spec `draft.md:135` —
   chống race: 2 FM duyệt song song chỉ 1 tx thắng, tx sau nhận 0 row → 409) → `Approved` +
   `responded_at=now` + `expires_at=responded_at + account.claim_ttl_days` (fallback 7 ngày).
10. Cùng transaction: gọi `RentalRequestConversionService.convertOnApproval` — email đã thuộc
    account **đã xác minh** thì tạo `RentalOrder(Pending)` + `ProposalFeedback(Pending)`, request →
    `Converted`, notification + email `RENTAL_REQUEST_APPROVED`; convert lỗi thì duyệt rollback.
    Nếu convert phát hiện trùng khoang (race hiếm — request khác vừa convert) → **409**
    `RentalRequestDuplicateUnitException`, rollback cả transaction duyệt, request giữ `Pending`
    ([booking/conversion.md](../../booking/conversion.md)).
11. Mail duyệt nhánh chưa-có-account theo template `draft.md:156` (after-commit, lỗi chỉ log); link đăng ký là URL
    tuyệt đối `<FRONTEND_URL>/register`, kèm hạn đăng ký và lưu ý "cọc trước giữ trước";
    với verify-first, email đang pending registration chưa có account nên vẫn nhận mail này;
    account legacy chưa xác minh không nhận mail — claim + notification sau khi xác minh.

Body: `{unitId}` bắt buộc. Trả envelope `{message, data: {id, status, unitId, respondedAt,
expiresAt}}`.

## Ghi chú nghiệp vụ

- Spec: `specs/draft.md` §1.1; permission `rental_request.approve` (FACILITY) đã seed V3;
  claim_ttl, chặn gán trùng khoang, mail nhánh chưa-có-account.
- Response giữ `status: "Approved"` (contract endpoint duyệt) kể cả khi convert đã chuyển request sang
  `Converted` trong cùng transaction — luồng convert không đổi contract HTTP. Riêng nhánh tài khoản
  bị chặn trả `status: "Rejected"` với `unitId`/`expiresAt` null.

## Ví dụ

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"fm1@lemar.vn","password":"Test@1234"}' | jq -r .data.token)

# 409 — khoang C-01 đang Maintenance
curl -s -X POST http://localhost:8080/api/fm/rental-requests/12/approve -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"unitId":8}'
# {"message":"Khoang đã không còn khả dụng, vui lòng chọn khoang khác","timestamp":"2026-10-16T14:51:52.423242314Z"}

# 200 — gán A-03 (Available); request giữ Pending sau lần 409 ở trên
curl -s -X POST http://localhost:8080/api/fm/rental-requests/12/approve -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"unitId":3}'
# {"message":"Đã duyệt yêu cầu đặt kho","data":{"id":12,"status":"Approved","unitId":3,"respondedAt":"2026-10-16T14:51:52.445715259Z","expiresAt":"2026-10-23T14:51:52.445715259Z"}}
```

## Liên quan

- Xem thêm: [routes.md](../routes.md) — [index.md](../index.md)
