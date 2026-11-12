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

## Dữ liệu demo (`dev/demo-seed.sql`) — chạy tay, một lệnh

Không phải migration: test tích hợp và bản deploy không nhận dữ liệu này. Sau khi backend đã chạy Flyway trên DB,
từ thư mục gốc repo:

```bat
backend\sql\dev\seed-demo.cmd
```

(Git Bash / macOS: `docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U self_storage -d self_storage < backend/sql/dev/demo-seed.sql`.)
Lệnh tự đợi Flyway tới V34 (tối đa 3 phút) nên chạy ngay sau khi bật backend được. Reset rồi nạp lại:
`docker compose down -v` → `docker compose up -d` → bật backend → chạy lệnh. Một DB chỉ nạp được một lần — lần
hai báo lỗi và không ghi gì.

Nạp vào:
- họ tên đầy đủ cho 8 account seed V2 (chỉ đổi khi tên vẫn là tên mặc định của V13);
- 3 cơ sở mới `BT` / `TB` / `GV`, mỗi cơ sở một FM (`fm3`–`fm5`); hai FS mỗi cơ sở (`fs2`–`fs10`) kèm hồ sơ nhân
  viên và ca 07:00–21:00 từ 7 ngày trước tới 60 ngày sau;
- khoang có vị trí trên sơ đồ ở cả 5 cơ sở;
- 12 hợp đồng `Active` (`customer2`–`customer13`) kèm hoá đơn cọc / tiền thuê đã trả và giao dịch `Success`.

Kịch bản quá hạn ở Q7 (mốc ngày tính theo ngày chạy, tiền theo policy hiện có), mô tả ở đầu file SQL: sắp hết hạn,
trong ân hạn, quá hạn có phí `INV-OVD`, quá hạn kéo dài bị tạm khoá mã khoá số, quá hạn đã gửi yêu cầu trả kho, tiền
thuê tháng quá hạn. Hoá đơn phí quá hạn, tạm khoá truy cập và thông báo được dựng đúng như `OverdueJob` tạo, nên
lượt cron đầu không tạo thêm hay đổi số tiền và không gửi lại mail.
