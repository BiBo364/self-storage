---
purpose: Bản đồ tài liệu backend — backend đang có gì, quy ước chung, mục lục.
---
# Tài liệu Backend — Self Storage

Tài liệu này mô tả backend đang có gì và nó hoạt động thế nào, ở mức **medium-level**:
giải thích high-level, kèm snippet code minh họa khi cần. Data contract đầy đủ của từng
endpoint nằm trong thư mục [`routes/`](routes.md) — file này chỉ ghi quy ước chung.

- Ngôn ngữ: tiếng Việt, thuật ngữ kỹ thuật giữ English.
- Phạm vi: những gì **đang có trong code**, không mô tả kế hoạch.

## Backend có gì

Spring Boot 3.3.5 · Java 17 · Spring Modulith · PostgreSQL 16 · Redis · Gmail SMTP (gửi mail thật).

Kiến trúc **modular monolith**: `vn.lemar.selfstorage` là package gốc,
mỗi package con trực tiếp là một module độc lập (`identity`, `booking`, `facility`, ...).
`ModuleStructureTest` chặn việc gọi sai chiều giữa các module ngay lúc build.

Hiện tại `identity` xử lý xác thực/RBAC; `facility` cung cấp public API kiểm tra facility tồn tại,
catalog cơ sở public và đọc khoang theo cơ sở; `audit` ghi vết thao tác nghiệp vụ nhạy cảm; `media`
upload file (ảnh/PDF) lên Cloudinary và dọn asset theo batch; `booking` có luồng nội bộ convert
request đã duyệt thành đơn + proposal ([booking/conversion.md](booking/conversion.md));
`handover` có data layer bàn giao, mở biên bản khi đặt lịch, listener set `due_at`
([handover/records.md](handover/records.md)); thanh toán cọc → đặt lịch check-in
([booking/deposit-payment.md](booking/deposit-payment.md), [booking/appointment-booking.md](booking/appointment-booking.md)).

Layout trong một module:

```text
<module>/
  domain/       entity + invariant
  repository/   truy cập dữ liệu
  application/  service + transaction
  controller/   HTTP API
```

Code map (identity):

| Thành phần | File |
|---|---|
| Login + `/me` | `identity/application/AuthService.java`, `identity/controller/AuthController.java` |
| Phát JWT | `identity/application/JwtService.java` |
| Verify + cấp quyền theo DB | `identity/application/AccountJwtAuthenticationConverter.java`, `identity/config/JwtConfiguration.java` |
| RBAC (permission + facility scope) | `identity/application/Access.java`, `identity/repository/PermissionRepository.java`, `identity/application/CurrentAccountProvider.java` |
| Kiểm tra FM phụ trách facility | `facility/application/FacilityAccess.java` |
| Khai báo route policy | `SecurityConfig.java` |
| Envelope + lỗi chung | `ApiEnvelope.java`, `ApiError.java`, `ApiExceptionHandler.java`, `JsonAuthenticationEntryPoint.java`, `JsonAccessDeniedHandler.java` |

## Xác thực & phân quyền

Mô hình stateless JWT: access token chỉ mang claim `email`; role + status được đọc lại từ DB
mỗi request. Chi tiết:

- [authentication.md](authentication.md) — danh tính, token, luồng xác thực mỗi request, giới hạn.
- [authorization.md](authorization.md) — authorities, ba kiểu ràng buộc quyền, 401 vs 403, điều kiện xác minh email.
- [permission-catalog.md](permission-catalog.md) — catalog permission + mapping role mặc định.

Guard RBAC cho route nghiệp vụ đi qua `Access.can(permission[, facilityId])`; quyền quản trị hệ thống
(role-bound) giữ ở `SecurityConfig` (`/api/admin/**` → `ADMIN`). Chi tiết từng route: [routes.md](routes.md).

## Cấu hình

Nguồn: `backend/src/main/resources/application.yml`, override qua biến môi trường:

| Biến | Ý nghĩa | Mặc định |
|---|---|---|
| `JWT_SECRET` | Khóa ký JWT, Base64, ≥ 32 byte sau decode | — (bắt buộc) |
| `JWT_ACCESS_TOKEN_TTL` | TTL access token (ISO-8601 duration) | `PT15M` |
| `BCRYPT_STRENGTH` | Bcrypt cost (log2 rounds), OWASP khuyến nghị ≥ 10 | `10` |
| `EMAIL_VERIFICATION_TTL` | TTL mã xác minh email (ISO-8601 duration) | `PT10M` |
| `EMAIL_VERIFICATION_MAX_ATTEMPTS` | Số lần nhập sai tối đa cho mỗi mã xác minh | `5` |
| `EMAIL_VERIFICATION_RESEND_COOLDOWN` | Cooldown giữa hai lần gửi lại mã | `PT60S` |
| `LOGIN_RATE_LIMIT_EMAIL_MAX` | Số lần đăng nhập sai tối đa theo email / window | `5` |
| `LOGIN_RATE_LIMIT_IP_MAX` | Số lần đăng nhập sai tối đa theo IP / window | `20` |
| `LOGIN_RATE_LIMIT_WINDOW` | Cửa sổ đếm lần đăng nhập sai | `PT15M` |
| `REDIS_HOST` | Redis host cho rate-limit auth / cache | `localhost` |
| `PAYMENT_PROVIDER` | Cổng thanh toán đang dùng: `payos` \| `vnpay` | `payos` |
| `PAYOS_CLIENT_ID` / `PAYOS_API_KEY` / `PAYOS_CHECKSUM_KEY` | Credential payOS (my.payos.vn) | — (bắt buộc khi `payos`) |
| `PAYOS_RETURN_URL` / `PAYOS_CANCEL_URL` | Trang FE nhận khách quay về từ payOS | `<FRONTEND_URL>/payments/result` |
| `VNPAY_TMN_CODE` | Mã website đăng ký trên VNPay | — (bắt buộc khi `vnpay`) |
| `VNPAY_HASH_SECRET` | Chuỗi bí mật ký/kiểm checksum VNPay | — (bắt buộc khi `vnpay`) |
| `VNPAY_PAY_URL` | Endpoint cổng thanh toán VNPay | — (bắt buộc khi `vnpay`) |
| `VNPAY_RETURN_URL` | Nơi VNPay đẩy trình duyệt khách về | — (bắt buộc khi `vnpay`) |
| `VNPAY_API_URL` | API merchant VNPay cho `querydr` (đối soát) | sandbox `merchant_webapi/api/transaction` |
| `FRONTEND_URL` | Gốc URL frontend, dựng link trong notification/email (vd `{{confirmLink}}` / `{{proposalLink}}` = `<FRONTEND_URL>/proposals`) | `http://localhost:5173` |
| `CLOUDINARY_NAME` | Cloud name tài khoản Cloudinary | — (bắt buộc) |
| `CLOUDINARY_KEY` | API key Cloudinary | — (bắt buộc) |
| `CLOUDINARY_SECRET` | API secret Cloudinary | — (bắt buộc) |
| `GEMINI_API_KEY` | Key Google Gemini cho chatbot tư vấn (aistudio.google.com/apikey) | — (trống → `POST /api/chat` trả 503) |
| `CHAT_MODEL` / `CHAT_THINKING_LEVEL` | Model Gemini và mức thinking (trống = không gửi `thinkingConfig`) | `gemini-3.6-flash` / `LOW` |

Chạy local không cần export tay: `application.yml` có
`spring.config.import: optional:file:../.env[.properties]` → copy `.env.example` thành `.env`.
Đường dẫn `../.env` tính theo working directory (đúng khi chạy từ `backend/`).

Credential của **cổng đang dùng** (`PAYMENT_PROVIDER`, mặc định payOS) thiếu là **app chết ngay lúc khởi động**,
có chủ đích — thà chết lúc boot còn hơn NPE giữa luồng thu tiền; cổng không dùng thì không bắt buộc.
payOS không có sandbox (giao dịch là tiền thật) — chi tiết ở [payment.md](payment.md). Credential sandbox mỗi người tự đăng ký riêng,
hướng dẫn ở [docs/vnpay-sandbox.md](../../docs/vnpay-sandbox.md).

## Database & seed

Flyway chạy migration lúc khởi động: `V1__baseline_schema.sql` … `V21__support_and_backoffice.sql`.
V1/V2 có mirror ở `backend/sql/`
— sửa thì sửa cả hai nơi (CI check đồng bộ); V3+ đọc trực tiếp trong `db/migration/`.

Seed dev có 8 account `@lemar.vn`: 6 account phủ đủ 5 role (FM có 2) + 2 ca đặc biệt `unverified`
(chưa xác minh — login bị chặn 403) và `banned` (403), mật khẩu `Test@1234`.
Chi tiết: `backend/sql/README.md`.

Schema Flow 1.3 đi bằng migration V8–V11 (V1/V2 đóng băng sau khi main có V7):

- Cột `rental_orders.needs_reproposal BOOLEAN NOT NULL DEFAULT FALSE` — Flow 2 bật/tắt; Flow 1.3 chỉ đọc:
  FM được đề xuất lại khi đơn chưa đóng, không có proposal `Pending`, và đơn `Pending` hoặc cờ này bật.
- CHECK `proposal_feedbacks.status` đổi `Expired` → **`Canceled`** (khoang hết khả dụng khi khách đồng ý,
  hoặc đơn bị huỷ theo Cancellation Cascade); dòng `Expired` cũ (nếu có) được chuyển sang `Canceled`.
- Audit action `PROPOSAL_CANCELED` (proposal bị huỷ vì khoang hết khả dụng lúc khách đồng ý).
- Seed policy `invoice.deposit_months = 1`: tiền cọc = giá thuê hiệu lực
  (`storage_units.monthly_price`, trống thì `unit_types.monthly_price`) × số tháng này.
- V9 seed `fee.unit_change = 0` (Flow 4 chốt giá trị) và thêm audit action `DEPOSIT_REFUND_REQUIRED`
  cho trường hợp đổi khoang sau khi cọc dư — trước B4, FM hoàn thủ công; B4 hoàn tự động về thẻ đã liên kết.
- V10 thêm audit action `UNIT_CHANGE_RELEASED` — nhả khoang cũ khi khách đổi khoang sau khi đã cọc;
  khoang mới được giữ khi hoá đơn bổ sung `Paid`, hoặc ngay lúc đồng ý nếu cọc đã đủ (`delta ≤ 0`).
- V11 thêm type `DepositSupplement` cho hoá đơn chênh lệch cọc và 2 partial unique index: một `Deposit`
  active (Unpaid/Paid) + một `DepositSupplement` Unpaid trên mỗi đơn.
- V12 thêm `media_uploads` — theo dõi upload theo batch, dọn asset chưa attach khi Hủy (chi tiết ở
  [routes/media/delete-batch.md](routes/media/delete-batch.md)).
- V16 seed `handover.due_days = 1` — hạn hoàn tất bàn giao sau khi khách đến (Flow 2.1).
- V17 thêm audit action `INVOICE_PAID`, `INVOICE_EXPIRED` (thanh toán / cron hoá đơn quá hạn). Notification type
  `PROPOSAL_UNIT_TAKEN` (khách: khoang đã có người đặt cọc trước) chỉ thêm ở code — cột `notifications.type` không có
  CHECK.
- V18 seed policy `appointment.booking_window_days = 7`, `appointment.max_days_after_deposit = 14`,
  `appointment.daily_slot_count = 3`, `appointment.capacity_mode = fixed_windows` (đặt lịch check-in),
  `order.expiry_reminder_days = 3` (cron nhắc), `handover.payment_grace_hours = 24`, `contract.prepaid_months = 1`
  (Flow 2.3 — spec ghi "chờ BOM", đây là giá trị mặc định).
- V19 thêm `facilities.enabled_key_access` / `enabled_code_access` (mặc định `true`), `handover_records.requested_access`
  (`AccessCode`/`PhysicalKey`/`Both`) + `customer_confirmed_at`, seed `contract.start_date_rule = handover_date`
  (bàn giao Flow 2.2–2.5, xem [handover/records.md](handover/records.md)). Notification type
  `HANDOVER_KEY_REQUESTED` (FS) và `FIRST_MONTH_REFUND_REQUIRED` (FM) chỉ thêm ở code.
- V20 thêm `return_requests` và `checkout_records`, 9 policy (`extension.*`, `contract.expiry_reminder_days`,
  `overdue.*`, `fee.cleaning`, `fee.lost_key`, `unit.maintenance_days`, `checkout.invoice_due_days`), audit action
  `CONTRACT_EXTENDED`, `RETURN_REQUESTED`/`_ASSIGNED`/`_CANCELED`/`_COMPLETED`, `OVERDUE_FEE_ACCRUED` và entity
  type `ReturnRequest`. Dùng lại cột `storage_units.maintenance_started_at` có từ V1. Xem
  [tenancy/rentals.md](tenancy/rentals.md), [checkout/return.md](checkout/return.md).
- V21 thêm `support_requests` (hỗ trợ / sự cố), `login_events` (lịch sử đăng nhập), audit action hỗ trợ + back-office
  (`SUPPORT_REQUEST_*`, `FACILITY_*`, `STORAGE_UNIT_CREATED`/`_UPDATED`, `POLICY_UPDATED`, `UNIT_TYPE_UPDATED`,
  `ACCOUNT_CREATED`/`_UPDATED`, `ROLE_PERMISSIONS_UPDATED`) và entity type `SupportRequest`, `Facility`, `Policy`,
  `UnitType`, `Role`. Xem [support/requests.md](support/requests.md), [backoffice/overview.md](backoffice/overview.md).

Các migration khác trên main: V5 seed `order.expiry_days` (luồng convert), V6 bỏ `Expired` khỏi CHECK
`rental_orders.status` (quá hạn cũng là `Canceled` + `cancel_reason`), V7 thêm 4 action convert vào
CHECK `audit_logs.action`.

Seed V2 chưa có đơn thuê/proposal (do luồng convert tạo). Muốn thử Flow 1.3 trên DB local thì
chạy tay `backend/sql/dev/proposal-sample.sql` — **không** phải migration, Flyway không đọc file này.
Muốn thử thanh toán cọc ngay (đơn + proposal `Agreed` + hoá đơn `Deposit` `Unpaid` 2.000đ, đủ 5 kịch bản
spec 1.4) thì chạy `backend/sql/dev/payment-sample.sql` — cũng chạy tay, chạy lại được nhiều lần.
Phiên bàn giao dựng sẵn tới bước khách đã đến: `backend/sql/dev/handover-sample.sql`. Hợp đồng `Active`
(còn hạn / sắp hết hạn / quá hạn) để thử gia hạn, trả kho, phí quá hạn: `backend/sql/dev/rental-sample.sql`.

## Gửi email (module notification)

- Module khác inject `vn.lemar.selfstorage.notification.EmailSender` và gọi
  `send(to, subject, body)` (văn bản thuần) hoặc `sendHtml(to, subject, htmlBody)`.
- Module `identity` **không** gọi trực tiếp `EmailSender` (notification đã phụ thuộc ngược lại
  identity — cycle): identity phát event `VerificationEmailRequested`, listener
  `VerificationEmailListener` trong notification gửi email. Dùng pattern này khi module nguồn
  không thể phụ thuộc notification.
- Gửi lỗi chỉ log, không ném exception → không rollback nghiệp vụ của caller; `true` = đã gửi
  hoặc đã xếp lịch, `false` = không gửi được (thiếu recipient/lỗi).
- Đang trong transaction → email được xếp lịch gửi **sau commit**; rollback thì không gửi.
  Ngoài transaction gửi ngay.
- Có sẵn layout HTML chuẩn: `NotificationEmailLayout.wrap(title, body)` → truyền thẳng vào
  `sendHtml(...)`.
- Cấu hình: gửi mail thật qua Gmail SMTP — mặc định `smtp.gmail.com:587`, `auth` + STARTTLS bật.
  `MAIL_USERNAME` = địa chỉ Gmail, `MAIL_PASSWORD` = App Password 16 ký tự (cần bật xác minh 2 bước,
  tạo tại `https://myaccount.google.com/apppasswords`); chỉ đặt trong `.env`, không commit.
  `MAIL_FROM` để trống → gửi từ `MAIL_USERNAME` (Gmail không cho gửi từ địa chỉ khác tài khoản đăng
  nhập); muốn tên hiển thị thì đặt `MAIL_FROM=Tên <địa-chỉ-gmail>`. `MAIL_TIMEOUT_MS` (mặc định
  `10000`) giới hạn connect/read/write SMTP để SMTP treo không giữ request quá lâu.
- Thiếu `MAIL_USERNAME`/`MAIL_PASSWORD` thì app vẫn khởi động, nhưng mỗi lần gửi Gmail từ chối xác
  thực → log ERROR và `send` trả `false` (nghiệp vụ không rollback).
- Giới hạn Gmail: khoảng 500 người nhận/ngày với tài khoản thường; account seed `@lemar.vn` là địa
  chỉ giả nên mail gửi tới sẽ bị trả về — test bằng email thật.
- Caller tự dựng `subject` và `body`.
- **Known limitation**:
  - Gửi best-effort, không retry/outbox. Process chết sau commit nhưng trước khi gửi xong thì
    email mất, không dấu vết. Gửi lỗi trả `false` và không thử lại.
  - Gửi đồng bộ trên thread caller, kể cả nhánh afterCommit — SMTP chậm làm chậm response.
    Chưa có `@Async`. Muốn bền (không mất mail, retry được) phải làm outbox + worker — cần chốt và ghi vào docs trước.
  - Layout HTML là mẫu cố định dùng chung; nội dung theo `(type, recipient)` do
    `NotificationTemplateCatalog` render (placeholder `{{key}}`).
- Việc còn lại của module (caller nghiệp vụ, cron dedup, pagination): [follow-up.md](follow-up.md).

## Ghi audit (module audit)

- Module khác inject `vn.lemar.selfstorage.audit.application.AuditRecorder` (named interface
  `audit::application`) và gọi `record(entry)` **trong cùng transaction nghiệp vụ** (propagation
  `MANDATORY` — gọi ngoài transaction là lỗi wiring); rollback nghiệp vụ thì bản ghi audit
  rollback theo.
- Một thao tác đổi nhiều entity → gọi `record` một lần cho mỗi entity; ví dụ `RENTAL_ORDER_CANCELED`
  hợp lệ với 4 loại entity (`RentalOrder`, `StorageUnit`, `ProposalFeedback`, `RentalContract`).
- `AuditAction` (50 action) / `AuditEntityType` (12 loại) khớp Audit action catalog
  (`specs/db-table-draft.md` › AuditLog); action không khớp entity → `IllegalStateException`
  (lỗi lập trình, 500).
- Cột `entity_id` lưu dạng chuỗi; `old_value`/`new_value` là JSONB; `actor_account_id` null =
  hệ thống/cron/IPN. Bảng `audit_logs` append-only.
- Hợp đồng tích hợp đầy đủ cho module gọi (checklist, ví dụ, call site hiện có):
  [audit-log.md](audit-log.md).
- Caller nghiệp vụ hiện có: đăng ký (`ACCOUNT_REGISTERED`), duyệt/từ chối request
  (`RENTAL_REQUEST_APPROVED`/`REJECTED`), convert (`RENTAL_REQUEST_CONVERTED`, `RENTAL_ORDER_CREATED`,
  `PROPOSAL_CREATED`), claim (`RENTAL_REQUEST_CLAIMED`) — bảng call site ở
  [audit-log.md](audit-log.md#call-site-hiện-có).

## Upload file (module media)

- FE upload qua `POST /api/media/uploads` (multipart, field `file`, `batchId` tuỳ chọn) →
  `{url, publicId, bytes, format, batchId}`; URL dùng lại khi submit flow (ảnh hiện trạng,
  scan hợp đồng, ảnh giấy tờ).
- Validate JPEG/PNG/WebP/PDF ≤ 10MB (khớp cap free plan Cloudinary); sai → 400 message cụ thể.
- Storage sau interface `FileStorage` (impl Cloudinary) — đổi provider chỉ thay impl.
- File lưu theo folder `uploads/acc-{accountId}` — upload mồ côi vẫn truy vết được chủ sở hữu.
- Mỗi upload ghi row `media_uploads` (`Uploading` → `Pending`). `DELETE /batch/{batchId}` dọn asset
  chưa attach; `POST /batch/{batchId}/attach` đánh dấu `Attached` để không xoá nữa.
- **Xoá media:** chỉ khi người dùng bấm X trên file đã upload **trong phase hiện tại**. Hủy/rời trang
  không xoá; batch `Pending` được giữ để quay lại. Sau khi phase submit, file `Attached` — delete batch
  bỏ qua. Order `Canceled` không xoá media (giữ evidence); order hard-delete/retention xử lý riêng.
- Creds `CLOUDINARY_NAME/KEY/SECRET` để `@NotBlank` — thiếu là app chết lúc boot; test/CI dùng
  giá trị giả, không gọi mạng.
- Chi tiết route: [routes/media/upload.md](routes/media/upload.md),
  [routes/media/delete-batch.md](routes/media/delete-batch.md),
  [routes/media/attach-batch.md](routes/media/attach-batch.md).

## Cron

| Job | Chu kỳ (property) | Làm gì |
|---|---|---|
| `AppointmentNoShowJob` | `booking.appointment-no-show.fixed-delay-ms` (5') | huỷ lịch check-in no-show — [booking/appointment-no-show.md](booking/appointment-no-show.md) |
| `BookingExpiryJob` | `booking.expiry.fixed-delay-ms` (5') | request hết hạn, hoá đơn quá hạn, catch-all đơn, nhắc sắp hết hạn — [booking/deposit-payment.md](booking/deposit-payment.md#cron-flow-1) |
| `PaymentReconcileJob` | `payment.reconcile.fixed-delay-ms` (5') | đối soát giao dịch `Pending` quá 20' bằng `querydr` — [payment.md](payment.md#đối-soát-querydr) |

## Chạy & kiểm thử

```bash
docker compose up -d                  # Postgres + Redis
cd backend && ./mvnw spring-boot:run  # chạy app

./mvnw -B verify                      # checkstyle + test + module boundary + coverage ≥80%
```

> Test không bao giờ gửi mail thật: `src/test/resources/application.properties` ghim SMTP về `localhost:2`
> (connection refused) vì `application.yml` đọc `../.env` của dev — nơi có thể có Gmail App Password thật. Test cần
> kiểm nội dung mail thì `@MockBean JavaMailSender`. Hai cron mới để initial delay 1 giờ trong test để không chạy
> chen giữa test.

> Lưu ý: `MigrationTest` và `SchemaValidationTest` tự skip khi máy không có Docker/Testcontainers,
> nên `verify` local có thể xanh mà chưa validate Flyway/schema — CI mới chạy đủ hai test này.

Coverage: `verify` chạy **cổng LINE và BRANCH ≥ 80%** toàn codebase (JaCoCo check) — dưới ngưỡng là đỏ.
CI chặn thêm **dòng thay đổi ≥ 80%** (diff-cover) và lưu artifact `backend-coverage`
(HTML + XML + `diff-cover.html`). Báo cáo local: `backend/target/site/jacoco/index.html`.

Swagger UI: `http://localhost:8080/swagger-ui/index.html` — đã khai báo bearer scheme;
bấm **Authorize** và dán token (không cần prefix `Bearer `) để gọi API cần đăng nhập.

## Quy ước data contract

- Base URL: `/api`; body JSON, `Content-Type: application/json`
- API cần đăng nhập: `Authorization: Bearer <JWT>`
- **Mọi response 2xx** — envelope `{message, data}`; `message` là câu tiếng Việt để FE hiển thị
  trực tiếp, không hardcode phía FE:

```json
{ "message": "Đăng nhập thành công", "data": { "accountId": 6, "email": "customer1@lemar.vn", "token": "eyJ..." } }
```

- **Mọi lỗi 4xx/5xx** — `{message, timestamp}`; message set trong exception/handler tương ứng:

```json
{ "message": "Email hoặc mật khẩu không đúng", "timestamp": "2026-10-04T08:14:27.947381536Z" }
```

- 400 validate/JSON hỏng dùng message chung `"Dữ liệu không hợp lệ"`; 401/403 tầng security cũng
  trả đúng shape trên (401 giữ header `WWW-Authenticate`, không còn body rỗng).
- Lỗi không lường trước (bug) → 500 `{"message":"Internal server error","timestamp":"..."}` —
  chi tiết exception chỉ vào log, không lộ ra response.
- Lỗi khung (404 sai path, 405 sai method, 415 sai content-type…) giữ đúng status, message tiếng
  Việt tương ứng. Đây là các status **nền, áp dụng cho mọi route** — Swagger từng route không lặp lại.
- Message cố định tiếng Việt phía BE; i18n (nếu cần) xử lý phía FE sau.
- Chi tiết các tầng bắt lỗi và bảng status: [error-handling.md](error-handling.md).

## Mục lục

| Tài liệu | Nội dung |
|---|---|
| [authentication.md](authentication.md) | Mô hình xác thực: token, luồng request, giới hạn |
| [authorization.md](authorization.md) | Mô hình phân quyền: authorities, ba kiểu ràng buộc, điều kiện xác minh |
| [permission-catalog.md](permission-catalog.md) | Catalog permission + mapping role mặc định |
| [error-handling.md](error-handling.md) | Xử lý lỗi: envelope, các tầng bắt lỗi, bảng status nền |
| [audit-log.md](audit-log.md) | Hợp đồng tích hợp AuditLog cho module gọi + call site hiện có |
| [booking/conversion.md](booking/conversion.md) | Convert request → đơn + proposal, claim sau xác minh |
| [booking/proposal-flow.md](booking/proposal-flow.md) | Đồng ý / từ chối đề xuất khoang, đề xuất lại: sơ đồ, state, invariant |
| [booking/appointment-no-show.md](booking/appointment-no-show.md) | Cron no-show check-in, guard `noShowDue`, audit/notification |
| [booking/deposit-payment.md](booking/deposit-payment.md) | Thanh toán hoá đơn + chốt cọc (IPN/querydr) + cron Flow 1 |
| [booking/appointment-booking.md](booking/appointment-booking.md) | Đặt lịch check-in: cửa sổ ngày, 3 khung, capacity, HandoverRecord |
| [handover/records.md](handover/records.md) | `HandoverRecord` + `RentalContract`, `due_at` Flow 2.1, luật huỷ |
| [flows/cancellation-cascade.md](flows/cancellation-cascade.md) | Cascade huỷ đơn: entity, khoang, audit, notification |
| [routes.md](routes.md) | Index toàn bộ route hiện có |
| [routes/health.md](routes/health.md) | `GET /api/health` |
| [routes/auth/login.md](routes/auth/login.md) | `POST /api/auth/login` |
| [routes/auth/me.md](routes/auth/me.md) | `GET /api/auth/me` |
| [routes/booking/invoice-list.md](routes/booking/invoice-list.md) | `GET /api/invoices` |
| [routes/facility/public-list.md](routes/facility/public-list.md) | `GET /api/facilities` |
| [routes/booking/fm-facility.md](routes/booking/fm-facility.md) | `GET /api/fm/facility` |
| [routes/booking/fm-storage-units.md](routes/booking/fm-storage-units.md) | `GET /api/fm/storage-units` |
| [routes/media/upload.md](routes/media/upload.md) | `POST /api/media/uploads` |
| [routes/media/delete-batch.md](routes/media/delete-batch.md) | `DELETE /api/media/uploads/batch/{batchId}` |
| [routes/media/attach-batch.md](routes/media/attach-batch.md) | `POST /api/media/uploads/batch/{batchId}/attach` |
