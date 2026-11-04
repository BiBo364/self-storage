---
purpose: Đăng ký tài khoản khách hàng — ghi nhận yêu cầu và gửi mã xác minh; account chỉ được tạo sau khi xác minh.
---
# POST /api/auth/register

Đăng ký tài khoản khách hàng: ghi nhận pending registration và gửi email chứa mã xác minh 6 số —
**`Account` chỉ được tạo ở bước [POST /api/auth/verify-email](verify-email.md)**.

- **Actor / quyền:** public — không cần token.
- **Contract chi tiết:** annotation (`@Operation`/`@ApiResponses`/`@ExampleObject`) tại
  `identity/controller/AuthController.java`; Swagger UI — `http://localhost:8080/swagger-ui/index.html`.

## Luồng / hành vi

`RegistrationService.register` (verify-first):

1. Chuẩn hoá email (`trim` + lowercase — `Account.normalize`); email đã có account → **409**
   (pending registration không tính là account — dùng [resend-verification](resend-verification.md)).
2. Băm mật khẩu BCrypt và **chưa tạo `Account`**: ghi pending registration (email + hash mật khẩu +
   `name` + `phone`) vào Redis cùng mã xác minh, TTL 10 phút. Đăng ký lại khi đang pending chịu
   cooldown 60 giây (429).
3. Phát mã xác minh (6 số) — lưu Redis dạng BCRYPT hash, không lưu plaintext — rồi phát event
   `VerificationEmailRequested`; module notification gửi email **sau commit**: rollback thì không
   gửi, SMTP lỗi không rollback (best-effort, chỉ log).
4. `Account` + audit `ACCOUNT_REGISTERED` được tạo ở bước [verify-email](verify-email.md).

## Ghi chú nghiệp vụ

- Spec: `specs/draft.md` › Flow 1 §1.2; cơ chế OTP: mã 6 số, TTL 10 phút; verify-first: account tạo sau xác minh.
- `name` bắt buộc (≤255), `phone` optional (≤20), mật khẩu tối thiểu 8 ký tự (validate DTO → 400
  `Dữ liệu không hợp lệ`).
- **Không có account nào được tạo ở bước này** — email typo/bỏ dở không để lại account rác. Khách
  hoàn tất [verify-email](verify-email.md) để tạo account rồi mới đăng nhập được:
  [authentication.md › Đăng ký và xác minh email](../../authentication.md#đăng-ký-và-xác-minh-email).
- Chống spam gửi lại mã: [resend-verification](resend-verification.md); chống brute-force login:
  [login](login.md).

## Ví dụ

```bash
curl -s -X POST http://localhost:8080/api/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"name":"Nguyễn Văn A","email":"khach-moi@example.com","phone":"0900000000","password":"matkhau123"}'
# {"message":"Đăng ký thành công. Kiểm tra email để lấy mã xác minh.","data":null}

# 409 — email đã đăng ký
# {"message":"Email đã được đăng ký","timestamp":"2026-10-15T10:07:56.911733683Z"}

# 400 — mật khẩu dưới 8 ký tự / email sai định dạng
# {"message":"Dữ liệu không hợp lệ","timestamp":"2026-10-15T10:07:56.947684468Z"}
```

Email xác minh (render qua `NotificationEmailLayout` — HTML chuẩn; xem tại MailHog dev
`http://localhost:8025` hoặc hộp thư thật theo `MAIL_*`):

```text
Subject: Mã xác minh email đăng ký Kho Mộc

Mã xác minh email của bạn là: 123456

Mã có hiệu lực trong 10 phút. Nếu bạn không đăng ký tài khoản, hãy bỏ qua email này.
```

## Liên quan

- Xem thêm: [routes.md](../routes.md) · [index.md](../index.md) · [verify-email](verify-email.md)
