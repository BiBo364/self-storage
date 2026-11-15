---
purpose: Đăng nhập bằng email + mật khẩu, trả access token (JWT).
---
# POST /api/auth/login

Đăng nhập bằng email + mật khẩu, trả access token (JWT).

- **Actor / quyền:** public — không cần token.
- **Contract chi tiết:** annotation (`@Operation`/`@ApiResponses`/`@ExampleObject`) tại
  `identity/controller/AuthController.java`; Swagger UI — `http://localhost:8080/swagger-ui/index.html`.

## Luồng xử lý

`AuthService.login`:

1. Chuẩn hoá email (`trim` + lowercase — `Account.normalize`).
2. Kiểm rate limit (Redis — theo email và IP); vượt ngưỡng → 429 trước khi kiểm mật khẩu.
3. `findByEmail`; không thấy → 401 (ghi nhận 1 lần sai).
4. `passwordEncoder.matches` (BCrypt); sai → 401 **cùng message** (không lộ tài khoản nào tồn tại)
   — ghi nhận 1 lần sai.
5. `account.isLoginAllowed()` (status `ACTIVE` **và** email đã xác minh):
   - `BANNED` → 403 "Tài khoản đã bị chặn";
   - `INACTIVE` (đã nghỉ việc) → 403 "Tài khoản đã ngừng hoạt động (nhân viên đã nghỉ việc)";
   - chưa xác minh email (account legacy/seed) → 403 "Tài khoản chưa xác minh email" (không tính là lần sai).
6. Xoá bộ đếm sai của email; `JwtService.issueAccessToken` → envelope
   `{message: "Đăng nhập thành công", data: {accountId, email, token}}`.

## Ghi chú

- Mật khẩu lưu bằng BCrypt; cost cấu hình qua `BCRYPT_STRENGTH` (mặc định 10).
- Chống brute-force: 5 lần sai/15 phút theo email, 20 lần/15 phút theo IP → 429.
  Redis lỗi thì fail-open (chỉ log) — xem
  [authentication.md › Chống brute-force đăng nhập](../../authentication.md#chống-brute-force-đăng-nhập).
- Token chỉ chứa claim `email`; role/status đọc từ DB ở mỗi request — xem
  [authentication.md › Xác thực mỗi request](../../authentication.md#xác-thực-mỗi-request).
- FE lấy role qua [GET /api/auth/me](me.md) — role **không** có trong response login.
- Account seed để test: `customer1@lemar.vn` / `Test@1234`; `unverified@lemar.vn` (403 — chưa xác
  minh email) và `banned@lemar.vn` (403 — bị chặn).

## Ví dụ

```bash
# 200 — account seed
curl -s -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"customer1@lemar.vn","password":"Test@1234"}'
# {"message":"Đăng nhập thành công","data":{"accountId":6,"email":"customer1@lemar.vn","token":"eyJhbGciOiJIUzI1NiJ9..."}}

# 401 — sai mật khẩu
curl -s -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"customer1@lemar.vn","password":"sai-mat-khau"}'
# {"message":"Email hoặc mật khẩu không đúng","timestamp":"2026-10-04T08:14:27.947381536Z"}

# 403 — account bị chặn (account seed: banned@lemar.vn)
# {"message":"Tài khoản đã bị chặn","timestamp":"2026-10-04T08:14:28.027349693Z"}

# 403 — chưa xác minh email (account seed: unverified@lemar.vn)
# {"message":"Tài khoản chưa xác minh email","timestamp":"2026-10-04T08:14:28.027349693Z"}

# 429 — sai quá ngưỡng (5 lần/15 phút theo email hoặc 20 lần/15 phút theo IP)
# {"message":"Quá nhiều lần thử. Vui lòng thử lại sau.","timestamp":"2026-10-15T10:08:49.428678409Z"}

# 400 — email sai định dạng
# {"message":"Dữ liệu không hợp lệ","timestamp":"2026-10-04T08:14:28.048550698Z"}
```
