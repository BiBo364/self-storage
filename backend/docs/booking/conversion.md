---
purpose: Luồng nội bộ convert RentalRequest đã duyệt thành RentalOrder + ProposalFeedback, claim sau xác minh email.
---
# Convert yêu cầu thuê → đơn + proposal

Nguồn flow tham khảo: `specs/draft.md` › 1.1 Yêu cầu đặt kho (nhánh "email đã có tài khoản"),
› 1.2 Khách tạo tài khoản › Case A. Trang này là **nguồn sự thật** cho hành vi convert (as-built):
code và các module khác theo trang này.

Luồng convert **không có endpoint HTTP riêng**. Chạy bên trong hai endpoint có sẵn:
`POST /api/fm/rental-requests/{id}/approve` và `POST /api/auth/verify-email`.

## Sơ đồ

```mermaid
flowchart TB
    subgraph TRIGGERS["Hai trigger"]
        T1["FM duyệt request<br/>(email đã verified)"]
        T2["Khách verify email<br/>→ EmailVerified"]
    end
    T1 --> C0["Khoá email (advisory)<br/>+ pre-check trùng"]
    T2 --> C0
    C0 --> C1{"Request Approved<br/>và chưa quá expires_at?"}
    C1 -- không --> X1["NOT_CONVERTIBLE"]
    C1 -- có --> C2{"Account verified,<br/>không bị chặn?"}
    C2 -- không --> X2["NO_VERIFIED_ACCOUNT<br/>request giữ Approved"]
    C2 -- có --> C3{"Đã có proposal Pending/Agreed<br/>cho khoang này?"}
    C3 -- có --> X3["DUPLICATE_UNIT<br/>duyệt: 409 + rollback<br/>claim: giữ Approved"]
    C3 -- không --> C4["Tạo RentalOrder Pending<br/>+ ProposalFeedback Pending"]
    C4 --> C5["Request → Converted<br/>+ audit + notification + email"]
```

> Claim chỉ chạy khi account đã verified; `NO_VERIFIED_ACCOUNT` chỉ xảy ra ở trigger duyệt.
> Trigger duyệt join transaction của request; claim chạy `REQUIRES_NEW` sau khi verify commit.

## Khi nào convert

| Trigger | Điểm nối | Hàm | Transaction |
|---|---|---|---|
| FM duyệt request, email đã thuộc account **đã xác minh, không bị chặn** | `RentalRequestService.approve` — khoá email trước pre-check trùng, rồi conditional update `Approved` + audit | `RentalRequestConversionService.convertOnApproval(requestId)` | Join transaction duyệt — convert lỗi thì duyệt rollback theo |
| Customer vừa xác minh email | `RegistrationService.verifyEmail` phát `EmailVerified(accountId)` → `booking/application/EmailVerifiedClaimListener` (`@TransactionalEventListener(AFTER_COMMIT)`) | `RentalRequestConversionService.claimForVerifiedAccount(accountId)` | Transaction **riêng** (`REQUIRES_NEW`) sau khi xác minh đã commit — claim lỗi chỉ log, xác minh vẫn thành công |

Identity không gọi sang booking (identity là module nền) — nên trigger 2 đi qua event.
Claim chạy cả lô trong một transaction `REQUIRES_NEW`: một request lỗi (ví dụ thiếu policy
`order.expiry_days`) rollback cả lô, listener chỉ log và không retry — các request vẫn `Approved`.

Trigger 1 khoá advisory theo email **trước pre-check trùng** và giữ tới commit; nếu `convertOnApproval`
vẫn phát hiện trùng (request khác vừa convert), luồng duyệt trả **409** `RentalRequestDuplicateUnitException`
và rollback cả transaction duyệt — request giữ `Pending`, không có ca "duyệt xong nhưng không có đơn".

`convertOnApproval` trả `ConversionResult`:

| `outcome` | Ý nghĩa | Caller (luồng duyệt) làm gì |
|---|---|---|
| `CONVERTED` | Đã tạo đơn + proposal, request → `Converted`, đã gửi notification + email | — |
| `NO_VERIFIED_ACCOUNT` | Email chưa có account (kể cả pending registration) hoặc account chưa xác minh. Request giữ `Approved` | Luồng duyệt gửi email "chưa có tài khoản" khi email **chưa có account**; chưa xác minh → không gửi; khách xác minh xong → claim. Account bị chặn không vào tới đây — luồng duyệt từ chối trước |
| `NOT_CONVERTIBLE` | Request không ở `Approved` hoặc đã quá `expires_at` | — |
| `DUPLICATE_UNIT` | Account đã có đơn/proposal đang hoạt động cho khoang này | Luồng duyệt: trả **409** `RentalRequestDuplicateUnitException`, rollback cả transaction duyệt — request giữ `Pending`. Claim sau xác minh: request giữ `Approved` |

Account `BANNED` không vào convert: luồng duyệt từ chối request, gửi mail và trả `Rejected`
([route duyệt](../routes/booking/fm-rental-request-approve.md)); claim sau xác minh không tạo gì.

`claimForVerifiedAccount` trả danh sách `ConversionResult` cho từng request `Approved` còn hạn khớp
email (rỗng = Case B, hoặc email chưa có account — pending/không tồn tại).

## Convert làm gì

Trong một transaction:

1. `RentalOrder(status = Pending, unit_id = null, expires_at = now + order.expiry_days)`.
2. `ProposalFeedback(status = Pending, unit_id = RentalRequest.unit_id)` — khoang FM đã chỉ định.
   Không có auto-matching hay đề xuất nhiều khoang.
3. `RentalRequest.status = Converted` — conditional update `WHERE status = 'Approved' AND expires_at > now`
   (`RentalRequestRepository.convertIfApproved`, cùng kiểu `approveIfPending` của luồng duyệt); 0 row → `NOT_CONVERTIBLE`,
   không tạo gì. Kiểm trùng chạy trước bước này: trùng ở trigger duyệt thì caller trả **409** và
   rollback cả transaction duyệt (request giữ `Pending`); trùng ở claim thì request giữ `Approved`.
4. Notification `RENTAL_REQUEST_APPROVED` cho customer, gắn `OrderNotification(order_id)`; email
   cùng nội dung gửi **sau commit**. Lỗi SMTP chỉ log, không rollback (xem
   [index.md › Gửi email](../index.md#gửi-email-module-notification)). Link trong body:
   `<FRONTEND_URL>/proposals`. Nội dung có `{{unitCode}}`, `{{unitType}}` = tên loại khoang kèm kích thước
   `rộng × sâu × cao` (vd "Small (1 × 2 × 2,2 m)") và lưu ý khoang chưa được giữ trong lúc chờ — khoang chỉ xác nhận
   cho khách hoàn tất thanh toán cọc đầu tiên (cọc trước giữ trước).
5. Audit: trigger duyệt ghi `RENTAL_REQUEST_CONVERTED` (System) + `RENTAL_ORDER_CREATED` +
   `PROPOSAL_CREATED`; trigger claim ghi `RENTAL_REQUEST_CLAIMED` (customer) + order/proposal created.
   Luồng duyệt vẫn ghi `RENTAL_REQUEST_APPROVED` trước khi convert.

`order.expiry_days` đọc từ bảng `policies` qua `pricing::application` `PricingQuery`, không fallback.
Key này seed ở `V5__order_expiry_days_seed.sql` — thiếu key là lỗi cấu hình: convert ném
`IllegalStateException` và rollback.

## Chống tạo trùng (account + khoang)

- "Đang hoạt động" = có `ProposalFeedback` `Pending`/`Agreed` cho khoang đó, trên một đơn của
  account chưa `Canceled`/`Done` (`ConversionGuardRepository.hasActiveConversion`). Pre-check lúc
  duyệt dùng cùng định nghĩa theo email (`hasActiveConversionForEmail`); đơn cũ đã đóng thì được
  gán lại khoang, không phụ thuộc status request cũ (`Converted` không bao giờ đóng).
  Đơn cũ đã đóng thì convert lại cùng khoang được.
- Hai trigger cùng khoá `pg_advisory_xact_lock` theo **email khách đã chuẩn hoá**, giữ tới hết
  transaction, nên duyệt và claim của cùng email chạy tuần tự (email unique theo account, claim↔claim
  vẫn tuần tự).
  - Khoá row request là chưa đủ: request r1 đang được duyệt trong transaction duyệt chưa commit thì
    với transaction khác r1 vẫn `Pending`, nên claim r2 cùng khoang không đụng row nào của r1.
    Test `claimDuringUncommittedApprovalWaitsAndSkipsSameUnit`.
  - Không khoá theo account id: lúc FM duyệt, khách có thể đang pending đăng ký (chưa có account). Trigger duyệt khoá
    email **trước** khi tra account đã xác minh và giữ khoá cả ở nhánh `NO_VERIFIED_ACCOUNT`, nên claim của lần
    xác minh chạy song song phải chờ duyệt commit rồi mới select — không bỏ sót request.
    Test `approvalBeforeVerificationCommitIsStillClaimed`.
  - Hai test trên chỉ commit transaction duyệt khi thấy claim thật sự bị chặn trên advisory lock
    (`pg_locks`), không dựa vào sleep; bỏ khoá hoặc khoá sau khi tra account thì test fail.
- Thứ tự khoá ở cả hai trigger: **email trước, row request sau** — trigger duyệt khoá email trước
  pre-check và update row; claim khoá email trước select.
- Request trùng ở claim giữ nguyên `Approved`, không auto-reject; trùng ở luồng duyệt bị chặn **409**
  và giữ `Pending`. Request trùng hoặc claim lỗi ở `Approved` tới `expires_at`; sau mốc đó
  `BookingExpiryJob` (5 phút) chuyển request `Pending`/`Approved` quá hạn sang `Expired` (audit, lý do
  "Quá hạn xử lý yêu cầu"). Request quá hạn không bị convert kể cả khi cron chưa chạy — select claim và
  `convertIfApproved` đều yêu cầu `expires_at > now`.

## Code map

| Thành phần | File |
|---|---|
| Convert + claim | `booking/application/RentalRequestConversionService.java`, `ConversionResult.java` |
| Nối trigger | `booking/application/RentalRequestService.java` (approve), `booking/application/EmailVerifiedClaimListener.java`, `identity/application/EmailVerified.java`, `identity/application/RegistrationService.java` |
| Khoá + kiểm trùng | `booking/repository/ConversionGuardRepository.java` |
| Request | `booking/domain/RentalRequest.java`, `booking/repository/RentalRequestRepository.java` (`convertIfApproved`, `findClaimableForUpdate`) |
| Đọc policy | `pricing/application/PricingQuery.java` |
| Account đã xác minh, không bị chặn | `identity/application/AccountQueryService.java` (`findVerifiedByEmail`, `findVerifiedById` — lọc `isLoginAllowed`) |
| Test | `src/test/.../booking/RentalRequestConversionIntegrationTest.java` (Testcontainers) |
