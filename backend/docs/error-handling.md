---
purpose: Cách backend xử lý lỗi và format response lỗi — envelope, các tầng bắt lỗi, bảng status nền.
---
# Error Handling

Cách backend xử lý lỗi và format response lỗi — trạng thái hiện tại (as-built).
Quy ước envelope chung nằm ở [index.md › Quy ước data contract](index.md#quy-ước-data-contract).

## Envelope

- Success (mọi 2xx): `{message, data}` — `ApiEnvelope`; message riêng từng route.
- Error (mọi 4xx/5xx): `{message, timestamp}` — `ApiError`; message tiếng Việt hiển thị trực tiếp,
  timestamp ISO-8601 UTC.

## Các tầng bắt lỗi — theo đường request đi qua

```text
Request
  │
  ▼ Filter chain (Spring Security + filter tương lai)
  │    401/403 → JsonAuthenticationEntryPoint / JsonAccessDeniedHandler
  │    exception khác trong filter → container → /error (chưa theo envelope, xem cuối trang)
  ▼
DispatcherServlet
  │    exception từ controller / service / interceptor
  ▼
@RestControllerAdvice (theo @Order)
  │    AuthExceptionHandler (HIGHEST_PRECEDENCE)
  │      InvalidCredentialsException  → 401 "Email hoặc mật khẩu không đúng"
  │      AccountNotAllowedException   → 403 "Tài khoản đã bị chặn"
  │      UnauthenticatedException     → 401 "Yêu cầu chưa được xác thực"
  │      ForbiddenException           → 403 "Bạn không có quyền thực hiện thao tác này"
  │                                     hoặc "Bạn không được gán vào cơ sở này"
  │      AccountNotFoundException     → 404 "Không tìm thấy tài khoản"
  │      FacilityNotFoundException    → 404 "Không tìm thấy cơ sở"
  │    NotificationExceptionHandler (HIGHEST_PRECEDENCE)
  │      NotificationNotFoundException → 404 "Không tìm thấy thông báo"
  │    ApiExceptionHandler (LOWEST_PRECEDENCE)
  │      validate / JSON hỏng         → 400 "Dữ liệu không hợp lệ"
  │      lỗi khung (ErrorResponse)    → giữ status + message tiếng Việt (404/405/415…)
  │      exception không lường trước   → 500 "Internal server error" (chi tiết chỉ vào log)
```

**Thứ tự advice:** `ExceptionHandlerExceptionResolver` chọn advice **match đầu tiên**, không phải
handler cụ thể nhất. Vì vậy `AuthExceptionHandler` và `NotificationExceptionHandler` đặt
`@Order(HIGHEST_PRECEDENCE)`, `ApiExceptionHandler` đặt `@Order(LOWEST_PRECEDENCE)`; advice mới
phải set `@Order` tường minh — nếu không, lỗi nghiệp vụ sẽ bị catch-all 500 đè. Hai advice cùng mức
cao không đè nhau vì mỗi cái match theo exception type riêng.

## Bảng status

| Status | Nguồn | `message` |
|---|---|---|
| 200 | controller | riêng từng route (envelope `{message, data}`) |
| 400 | `ApiExceptionHandler` | `Dữ liệu không hợp lệ` |
| 401 | `AuthExceptionHandler` | `Email hoặc mật khẩu không đúng` (sai email/mật khẩu) |
| 401 | `AuthExceptionHandler` | `Yêu cầu chưa được xác thực` (guard chạy khi thiếu/xóa account) |
| 401 | `JsonAuthenticationEntryPoint` | `Bạn cần đăng nhập để tiếp tục.` + header `WWW-Authenticate` (`Bearer` / `Bearer error="invalid_token"`) |
| 403 | `AuthExceptionHandler` | `Tài khoản đã bị chặn` (login với account `BANNED`) hoặc `Tài khoản chưa xác minh email` (account legacy chưa xác minh) |
| 403 | `AuthExceptionHandler` | `Bạn không có quyền thực hiện thao tác này` (thiếu permission) hoặc `Bạn không được gán vào cơ sở này` (ngoài facility scope) |
| 403 | `JsonAccessDeniedHandler` | `Tài khoản đã bị chặn` (`BANNED`), `Tài khoản chưa xác minh email` (token của account chưa xác minh) hoặc `Bạn không có quyền truy cập` (sai role) |
| 404 | `AuthExceptionHandler` | `Không tìm thấy tài khoản` / `Không tìm thấy cơ sở` (nghiệp vụ) |
| 404 | `NotificationExceptionHandler` | `Không tìm thấy thông báo` (nghiệp vụ) |
| 404 | `ApiExceptionHandler` | `Không tìm thấy tài nguyên` (khung — sai path) |
| 405 | `ApiExceptionHandler` | `Phương thức không được hỗ trợ` |
| 415 | `ApiExceptionHandler` | `Định dạng nội dung không được hỗ trợ` |
| 4xx khác | `ApiExceptionHandler` | `Yêu cầu không hợp lệ` |
| 500 | `ApiExceptionHandler` | `Internal server error` — chi tiết exception chỉ vào log |

405/415 là status nền, áp dụng cho mọi route — Swagger từng route không lặp lại. 404 có cả nhánh
khung (`ApiExceptionHandler` — sai path) lẫn nhánh nghiệp vụ (`AuthExceptionHandler` — tài khoản/cơ
sở không tồn tại), nên route cần phân biệt thì ghi rõ trong Swagger.

## Ví dụ (output thật)

```text
# 401 token — body chung; header RFC 6750 không kèm error_description
$ curl -s -i -H 'Authorization: Bearer abc.def.ghi' http://localhost:8080/api/auth/me
WWW-Authenticate: Bearer error="invalid_token"
{"message":"Bạn cần đăng nhập để tiếp tục.","timestamp":"2026-10-05T06:20:49.329273914Z"}

# 403 — account BANNED
{"message":"Tài khoản đã bị chặn","timestamp":"2026-10-04T08:14:28.027349693Z"}

# 403 — chưa xác minh email
{"message":"Tài khoản chưa xác minh email","timestamp":"2026-10-04T08:14:28.027349693Z"}

# 400 — validate
{"message":"Dữ liệu không hợp lệ","timestamp":"2026-10-04T08:14:28.048550698Z"}

# 405 — sai method
{"message":"Phương thức không được hỗ trợ","timestamp":"2026-10-04T08:46:02.634342450Z"}

# 404 — sai path (đã đăng nhập; ẩn danh vào /api/** bị 401 chặn trước)
{"message":"Không tìm thấy tài nguyên","timestamp":"2026-10-04T08:46:24.796283083Z"}
```

500 hiện xảy ra khi path/query sai kiểu — `MethodArgumentTypeMismatchException` rơi vào catch-all
(bug đã biết). `ApiExceptionHandlerTest` assert body generic không lộ chi tiết nội bộ.

## Chưa theo envelope

Exception ném từ **filter** (không phải 401/403 của security — đã có handler riêng) bay ra servlet
container → error-dispatch `/error` → `BasicErrorController` của Spring Boot trả
`{timestamp,status,error,path}` — không có `message`. Repo không có filter tự viết nên trường hợp này
không xảy ra với route hiện có; không có `ErrorController` tùy biến theo envelope.

## Test

- `ApiExceptionHandlerTest` — 500 generic không lộ chi tiết; 405 giữ status + message
- `AuthControllerTest` — login 200 envelope; 400/401/403 message
- `SecurityConfigTest` — 401 thiếu token, 403 sai role/`BANNED`/chưa xác minh có body
- `ExpiredTokenTest` — token hết hạn 401 + header `WWW-Authenticate`
- `RequiredClaimsTest` — token thiếu claim bắt buộc 401
