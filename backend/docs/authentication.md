---
purpose: Mô hình xác thực — JWT, vòng đời token, resolve account mỗi request.
---
# Authentication Model

Mô hình xác thực của backend: stateless JWT + đọc DB mỗi request.
Tài liệu này trả lời **"bạn là ai"** (authentication); phần **"bạn được làm gì"** (authorization)
nằm ở [authorization.md](authorization.md).

## Tổng quan

- Access token: JWT **HS256**, ký bằng `JWT_SECRET`.
- Token **chỉ mang claim `email`** — không có `role`, không có `sub`.
- Không lưu session phía server; mỗi request tự xác thực lại.
- Role + status đọc từ DB mỗi request (qua converter) → khóa tài khoản / đổi role có hiệu lực ngay.
- Mọi response theo envelope chung — xem [index.md › Quy ước data contract](index.md#quy-ước-data-contract).

## Token

Payload thật của token phát bởi `POST /api/auth/login`:

```json
{
  "iss": "self-storage",
  "iat": 1791012361,
  "exp": 1791013261,
  "email": "customer1@lemar.vn"
}
```

- `iss` cố định `self-storage` — bắt buộc, validate khi decode (`JwtIssuerValidator`).
- `exp` = `iat` + `security.jwt.access-token-ttl` (mặc định `PT15M` = 900 giây) — bắt buộc;
  kiểm hạn khi decode (`JwtTimestampValidator`, cho phép lệch đồng hồ 60 giây).
- `email` — bắt buộc, dùng để tra account (`AccountJwtAuthenticationConverter`).
- `iat` — metadata, không bắt buộc (không nơi nào đọc giá trị).
- Token thiếu `exp` hoặc `email` bị từ chối ngay khi decode (`RequiredJwtClaimsValidator` → 401) —
  mọi producer phải phát token đủ claim, một shape duy nhất.
- Không có claim phân quyền — có chủ ý, xem "Vì sao token không mang role".

## Token được phát thế nào

Việc kiểm mật khẩu/status và phát token là hành vi của endpoint — luồng xử lý chi tiết và
contract nằm ở [routes/auth/login.md](routes/auth/login.md).

## Xác thực mỗi request

```text
Authorization: Bearer <JWT>
  │
  ▼ BearerTokenAuthenticationFilter (OAuth2 Resource Server)
  │    JwtDecoder verify: chữ ký HS256 + iss + claim bắt buộc (exp, email),
  │    và exp còn hạn                                      (JwtConfiguration)
  ▼
Jwt (claims: email, iss, exp…)
  │
  ▼ AccountJwtAuthenticationConverter.convert(jwt)        (đọc DB mỗi request)
  │    email claim rỗng                     → InvalidBearerTokenException (401)
  │    findWithRoleByEmail(email)
  │      không tồn tại                      → 401
  │    isLoginAllowed == true (ACTIVE + đã xác minh):
  │      authorities = [ACCOUNT_ACTIVE, ROLE_<role>]
  │    ACTIVE nhưng chưa xác minh email:
  │      authorities = [ACCOUNT_UNVERIFIED] → 403 "Tài khoản chưa xác minh email"
  │    BANNED / INACTIVE:
  │      authorities = []                   → 403 "Tài khoản đã bị chặn"
  ▼
JwtAuthenticationToken → SecurityContext
  │
  ▼ authorizeHttpRequests đối chiếu rule (policy khai báo trong `SecurityConfig`;
       quyền từng route xem [routes.md](routes.md))
```

Principal name = email của account trong DB. Snippet rút gọn:

```java
// AccountJwtAuthenticationConverter.java
String email = jwt.getClaimAsString("email");
Account account = accountRepository.findWithRoleByEmail(Account.normalize(email))
        .orElseThrow(() -> new InvalidBearerTokenException("Account for JWT was not found"));

List<GrantedAuthority> authorities = new ArrayList<>();
if (account.isLoginAllowed()) {                        // status ACTIVE && đã xác minh email
    authorities.add(new SimpleGrantedAuthority("ACCOUNT_ACTIVE"));
    authorities.add(new SimpleGrantedAuthority("ROLE_" + account.getRole().getName()));
} else if (account.getStatus() == AccountStatus.ACTIVE) {
    authorities.add(new SimpleGrantedAuthority("ACCOUNT_UNVERIFIED"));   // chỉ để 403 có message đúng
}
return new JwtAuthenticationToken(jwt, authorities, account.getEmail());
```

## Trạng thái account

- `status` (`AccountStatus`): `ACTIVE` (mặc định khi tạo) | `BANNED` (bị khoá) | `INACTIVE` (nhân viên đã nghỉ
  việc — đặt khi ADMIN duyệt yêu cầu thay người, xem
  [backoffice/overview.md › Yêu cầu tạo tài khoản](backoffice/overview.md#yêu-cầu-tạo-tài-khoản-bom--admin)). Hai
  trạng thái khác `ACTIVE` đều chặn đăng nhập và mọi thao tác authenticated.
- `email_verified_at = null` (chỉ còn ở account legacy/seed/admin tạo tay) **chặn đăng nhập và mọi
  thao tác authenticated** — account tự đăng ký luôn được tạo **sau khi** xác minh; chỉ luồng xác
  minh email (register/verify/resend) hoạt động. Login trả 403 `Tài khoản chưa xác minh email`; token
  phát trước đó (nếu có) cũng không được cấp quyền — xem
  [authorization.md › Điều kiện nghiệp vụ](authorization.md#điều-kiện-nghiệp-vụ-xác-minh-email).

## Đăng ký và xác minh email

Đăng ký → email mã 6 số → xác minh bằng mã:

- `POST /api/auth/register`: validate email chưa có account + password; **chưa tạo `Account`** —
  ghi pending registration (email + hash mật khẩu BCrypt) trong Redis cùng TTL với mã rồi gửi email
  mã xác minh. Đăng ký lại khi đang chờ dùng chung cooldown 60 giây (429).
- `POST /api/auth/verify-email`: mã đúng → **tạo `Account` role `CUSTOMER` với `email_verified_at`
  set ngay** + ghi audit `ACCOUNT_REGISTERED` (actor = chính account vừa tạo); mã chỉ bị xoá sau khi
  transaction commit (save lỗi thì mã còn để nhập lại/resend); account legacy chưa xác minh chỉ được
  mark verified. Account đã xác minh trước đó → 200 với message
  `Email đã được xác minh trước đó` (no-op, không kiểm mã). Việc xác minh tự nó **không** ghi
  `AuditLog` riêng (bản ghi vòng đời là `Account.email_verified_at`).
- Mã: 6 số, TTL 10 phút, tối đa 5 lần nhập sai (429), gửi lại tối đa mỗi 60 giây; lưu Redis dạng
  BCRYPT hash (`auth:verify-code:<email>` — không plaintext), dùng một lần. Redis lỗi thì
  register/verify/resend trả lỗi (không fail-open).
- Mã gắn theo email: mã của account này **không** verify được account khác (tra key theo email đích).
  Mặt trái: đoán sai 5 lần khoá mã của account — kẻ xấu biết email có thể cố tình khoá để buộc nạn
  nhân gửi lại mã (DoS nhẹ, khôi phục bằng resend; verify chưa có rate limit theo IP — bổ sung khi cần).
- Account tạo từ đăng ký **đã xác minh ngay khi tạo**; account legacy/seed có `email_verified_at
  = null` bị chặn đăng nhập (403 `Tài khoản chưa xác minh email`, kể cả mật khẩu đúng).
- Gửi email đi qua event `VerificationEmailRequested` (identity không phụ thuộc module
  `notification` — cycle); listener `VerificationEmailListener` gửi after-commit như mọi email.
- Route chi tiết: [register](routes/auth/register.md) · [verify-email](routes/auth/verify-email.md)
  · [resend-verification](routes/auth/resend-verification.md).

## Chống brute-force đăng nhập

Sai 5 lần/15 phút theo email hoặc 20 lần/15 phút theo IP → **429**:

- Đếm trên Redis (`auth:login-fail:email:<email>`, `auth:login-fail:ip:<ip>`), fixed window 15 phút
  (không sliding — chấp nhận cho MVP); đăng nhập thành công xoá bộ đếm của email.
- Sai email không tồn tại cũng tính là một lần sai; account `BANNED` (403) không tính.
- **Fail-open**: Redis lỗi thì chỉ log và cho đăng nhập đi tiếp — đổi lấy availability, chấp nhận
  tạm mất lớp chống brute-force. Ngưỡng cấu hình qua `LOGIN_RATE_LIMIT_*` (bảng dưới).
- IP lấy từ `HttpServletRequest.getRemoteAddr()` — chạy sau reverse proxy (ngrok/cloudflared) sẽ
  thấy IP của proxy; **chưa** đọc `X-Forwarded-For` (tránh giả mạo khi chưa cấu hình trusted proxy).
  Cần xử lý khi triển khai thật sau proxy.

## Vì sao token không mang role

Token mang role thì nhanh hơn (không cần query DB mỗi request), nhưng khi admin khóa tài khoản
hoặc đổi role, token cũ vẫn dùng được tới lúc hết hạn. Mô hình này chọn ngược lại: token nói
"tôi là account có email X", còn **quyền hiện tại của X do DB trả lời ở mỗi request**.
Đánh đổi: thêm 1 query mỗi request, bù lại thu hồi quyền tức thời.

## 401 vs 403

401 là lỗi xác thực, 403 là lỗi phân quyền — bảng đối chiếu, policy route và điều kiện nghiệp vụ:
[authorization.md](authorization.md).

## Tín hiệu 401 cho client

Token sai / hết hạn / thiếu claim bắt buộc → 401 body chung `{message, timestamp}` **và** header
`WWW-Authenticate` (RFC 6750, không kèm `error_description`) — output thật:

```text
$ curl -s -i -H 'Authorization: Bearer abc.def.ghi' http://localhost:8080/api/auth/me
WWW-Authenticate: Bearer error="invalid_token"
{"message":"Bạn cần đăng nhập để tiếp tục.","timestamp":"2026-10-05T06:20:49.329273914Z"}
```

Chi tiết lỗi token chỉ ghi log server (debug), không trả cho client; thiếu token hoàn toàn thì
header là `Bearer` trần.

Client xử lý theo `status = 401` (không parse message để làm logic); `message` để hiển thị/log;
refresh token sẽ bám vào tín hiệu này. Toàn bộ các tầng bắt lỗi:
[error-handling.md](error-handling.md).

## Cấu hình liên quan

| Biến | Ý nghĩa | Mặc định |
|---|---|---|
| `JWT_SECRET` | Khóa ký JWT, Base64, ≥ 32 byte sau decode | — (bắt buộc) |
| `JWT_ACCESS_TOKEN_TTL` | TTL access token (ISO-8601 duration) | `PT15M` |
| `BCRYPT_STRENGTH` | Bcrypt cost (log2 rounds) | `10` |
| `EMAIL_VERIFICATION_TTL` | TTL mã xác minh email (ISO-8601 duration) | `PT10M` |
| `EMAIL_VERIFICATION_MAX_ATTEMPTS` | Số lần nhập sai tối đa cho mỗi mã | `5` |
| `EMAIL_VERIFICATION_RESEND_COOLDOWN` | Cooldown giữa hai lần gửi lại mã | `PT60S` |
| `LOGIN_RATE_LIMIT_EMAIL_MAX` | Số lần đăng nhập sai tối đa theo email / `window` | `5` |
| `LOGIN_RATE_LIMIT_IP_MAX` | Số lần đăng nhập sai tối đa theo IP / `window` | `20` |
| `LOGIN_RATE_LIMIT_WINDOW` | Cửa sổ đếm lần đăng nhập sai | `PT15M` |

## Mô hình này có gì / chưa có

Có:

- Thu hồi quyền tức thời qua status/role trong DB.
- Không lưu session phía server (stateless).
- Swagger khai báo bearer scheme để thử API.

Chưa có:

- Refresh token + rotation → hết hạn 15 phút phải login lại.
- Logout endpoint (revoke) — access token không thu hồi được trước hạn, nhưng khóa account
  thì có hiệu lực ngay.
- `kid` / key rotation — xoay `JWT_SECRET` làm toàn bộ token cũ hết hiệu lực.

## Test

- `JwtServiceTest` — claims email-only, issuer, expiry.
- `SecurityConfigTest` — route gate theo role/`ACCOUNT_ACTIVE`, `/me` 401/200/403, account bị xóa,
  token chưa xác minh bị 403 ở route role lẫn dòng vét.
- `AuthServiceTest` + `RegistrationIntegrationTest` — trước verify chưa có account nên login 401;
  verify tạo account rồi login 200; 403 `Tài khoản chưa xác minh email` chỉ còn cho account legacy.
- `AuthServiceTest` — login 401/403, chuẩn hoá email, `/me`, rate limit (429/đếm/reset).
- `RegistrationServiceTest` — đăng ký (409, audit, phát mã), verify (idempotent), resend (cooldown).
- `EmailVerificationCodesTest`, `LoginAttemptLimiterTest` — kho mã Redis + bộ đếm rate limit (unit).
- `AuthControllerTest` — contract HTTP register/verify/resend (400/409/429), Swagger examples.
- `RegistrationIntegrationTest` — end-to-end Postgres + Redis: register → verify → login, khoá sau
  5 lần sai, cooldown resend, audit `ACCOUNT_REGISTERED`.
- `ExpiredTokenTest` — token hết hạn → 401 + header `WWW-Authenticate`.
- `RequiredClaimsTest` — token thiếu `exp` / `email` → 401.
