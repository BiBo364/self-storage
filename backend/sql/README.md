# backend/sql

Bản đọc được của SQL — **mirror byte-identical** với Flyway migration (CI check đồng bộ):

| File | Migration tương ứng |
|---|---|
| `schema.sql` | `src/main/resources/db/migration/V1__baseline_schema.sql` |
| `seed.sql` | `src/main/resources/db/migration/V2__baseline_seed.sql` |

**Sửa SQL thì sửa cả hai nơi** (CI sẽ fail nếu lệch). Thiết kế as-built nằm ở `backend/docs/`;
`specs/` chỉ còn là tham chiếu flow, không sửa.

## Vòng đời baseline (V1)

- **Khi mọi DB đã apply migration còn disposable** (chưa có prod/staging/sandbox dùng dữ liệu thật): được sửa trực tiếp `V1__baseline_schema.sql` + mirror `schema.sql`, không cần migration mới. Đổi lại, đồng đội phải `docker compose down -v && docker compose up -d` một lần vì Flyway báo checksum mismatch.
- **Khi có bất kỳ DB không thể xoá trắng** (prod, staging, sandbox có người dùng): V1 và mọi migration đã apply **đóng băng**. Thay đổi schema chỉ được thêm bằng migration mới (`V3__…`, `V4__…`, theo số kế tiếp), không sửa file đã apply.

**Lưu ý:** chỉ **baseline V1/V2** có mirror trong `sql/`. Các migration mới hơn (V3+…) đọc trực tiếp trong `db/migration/` (mỗi file tự mô tả trong header).

## Chạy migration

Migrations tự chạy khi app khởi động (Flyway + `spring.datasource`). Máy dev:

```bash
docker compose up -d          # Postgres + Redis
cd backend && ./mvnw spring-boot:run
```

⚠️ DB cũ tạo tay (mock-data.sql trước đây) sẽ làm Flyway fail — xoá volume rồi lên lại:
`docker compose down -v && docker compose up -d`.

## Chạy tay bằng psql (khi cần)

```bash
psql "$DB_URL" -f sql/schema.sql
psql "$DB_URL" -f sql/seed.sql
```

## Seed (V2) — mật khẩu dev

Mọi account `@lemar.vn` dùng mật khẩu `Test@1234`: `admin`(ADMIN) · `bom1`(BOM) · `fm1`(FM, gán Q7) · `fm2`(FM, gán TD) · `fs1`(FS, gán Q7) · `customer1`(CUSTOMER) · `unverified`(CUSTOMER, chưa xác minh — login 403) · `banned`(CUSTOMER, BANNED — 403).
