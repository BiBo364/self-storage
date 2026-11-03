---
purpose: Trả email, tên, phone + role của account đang đăng nhập (xác minh session).
---
# GET /api/auth/me

Xác minh session hiện tại là ai — FE dùng sau login để biết email, tên, phone + role.

- **Actor / quyền:** bearer token + authority `ACCOUNT_ACTIVE`.
- **Contract chi tiết:** annotation tại `identity/controller/AuthController.java`; Swagger UI —
  `http://localhost:8080/swagger-ui/index.html` (bấm **Authorize** và dán JWT).

## Luồng / hành vi

- Route nằm dưới `/api/auth/**` nhưng được match **trước** rule `permitAll` trong `SecurityConfig`,
  nên vẫn yêu cầu token và account `ACTIVE` (đã xác minh email).
- Role đọc từ DB **mỗi request**: admin đổi role hoặc khóa account thì request kế tiếp thấy hiệu lực ngay.
- 401/403 trả body `{message, timestamp}`; 401 kèm header `WWW-Authenticate` — xem
  [authentication.md › Tín hiệu 401 cho client](../../authentication.md#tín-hiệu-401-cho-client).

## Ghi chú

- Đây là endpoint chính thức để FE lấy role, thay cho field `role` đã bỏ khỏi response login.

## Ví dụ

```bash
TOKEN='<token từ POST /api/auth/login>'

curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/auth/me
# {"message":"Lấy thông tin tài khoản thành công","data":{"email":"customer1@lemar.vn","role":"CUSTOMER","name":"Khách Demo","phone":null}}

curl -s http://localhost:8080/api/auth/me
# {"message":"Bạn cần đăng nhập để tiếp tục.","timestamp":"2026-10-05T06:20:49.321777582Z"}

curl -s -i -H 'Authorization: Bearer abc.def.ghi' http://localhost:8080/api/auth/me | grep -iE 'WWW-Authenticate|^\{'
# WWW-Authenticate: Bearer error="invalid_token"
# {"message":"Bạn cần đăng nhập để tiếp tục.","timestamp":"2026-10-05T06:20:49.329273914Z"}
```
