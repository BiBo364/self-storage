# backend/sql

Bản đọc được của SQL — **mirror byte-identical** với Flyway migration (CI check đồng bộ):

| File | Migration tương ứng |
|---|---|
| `schema.sql` | `src/main/resources/db/migration/V1__baseline_schema.sql` |
| `seed.sql` | `src/main/resources/db/migration/V2__baseline_seed.sql` |

Mirror và migration phải khớp từng byte (CI fail nếu lệch). Thiết kế as-built nằm ở `backend/docs/`;
`specs/` chỉ còn là tham chiếu flow, không sửa.

## Vòng đời baseline (V1/V2)

V1/V2 và mọi migration đã apply đều **đóng băng**: không sửa file đã apply (Flyway báo checksum mismatch). Thay đổi
schema hoặc seed chỉ đi bằng migration mới theo số kế tiếp sau file mới nhất trong `db/migration/`. `schema.sql`/`seed.sql` vì vậy không đổi
theo migration mới.

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

`DB_URL` trong `.env` là URL JDBC (`jdbc:postgresql://…`), `psql` không đọc được — dùng URL libpq (giá trị
`docker-compose.yml`):

```bash
psql "postgresql://self_storage:self_storage@localhost:5432/self_storage" -f sql/schema.sql
psql "postgresql://self_storage:self_storage@localhost:5432/self_storage" -f sql/seed.sql
```

Chỉ chạy tay trên một DB thử riêng, không phải DB app dùng: Flyway không biết các lần chạy tay, và DB đã có schema
từ Flyway sẽ lỗi trùng bảng. DB của app luôn để Flyway tự dựng.

## Seed (V2) — mật khẩu dev

Mọi account `@lemar.vn` dùng mật khẩu `Test@1234`: `admin`(ADMIN) · `bom1`(BOM) · `fm1`(FM, gán Q7) · `fm2`(FM, gán TD) · `fs1`(FS, gán Q7) · `customer1`(CUSTOMER) · `unverified`(CUSTOMER, chưa xác minh — login 403) · `banned`(CUSTOMER, BANNED — 403).
