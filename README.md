# self-storage

Hệ thống cho thuê kho self-storage. Modular monolith: **Spring Boot 3.3 · Java 17** (backend) + **React 19 · Vite** (frontend).

- Nguồn sự thật: docs trong từng module (`backend/docs/`, và `frontend/docs/` khi có).
  `specs/draft.md` chỉ để tham khảo flow — không sửa; `specs/db-table-draft.md` cập nhật theo schema.
  Đọc `AGENTS.md` trước khi code.
- Quy trình: issue → branch → PR (`Closes #N`) → CI xanh + 1 approval → merge. Trước khi mở PR,
  đọc [hướng dẫn viết PR body](docs/guides/pull-request.md).
- Roadmap: theo dõi trong GitHub Issues.

## Chạy nhanh (dev)

```bash
# 0) Env: copy rồi điền 5 giá trị bắt buộc (Cloudinary ×3 + 2 key cổng thanh toán)
cp .env.example .env

# 1) Hạ tầng: Postgres + Redis (mail gửi thật qua Gmail SMTP — điền MAIL_* trong .env)
docker compose up -d

# 2) Backend (Java 17)
cd backend
./mvnw spring-boot:run            # Windows: mvnw.cmd spring-boot:run
curl http://localhost:8080/api/health
# Swagger UI: http://localhost:8080/swagger-ui/index.html

# 3) Frontend
cd frontend && npm ci && npm run dev
```

- Chạy cả app trong Docker (không cần JDK/Node): `docker compose --profile app up -d --build` — service
  `backend` đọc `.env` của repo rồi trỏ DB/Redis vào mạng Docker, nghe cổng **8080**. FE vẫn chạy `npm run dev`
  (proxy `/api` → `localhost:8080`).
- Env: copy `.env.example` → `.env` ở thư mục gốc repo (không commit `.env`). Backend tự đọc `../.env` khi chạy từ
  `backend/` (`spring.config.import` trong `application.yml`), không cần export biến tay.
- Thiếu `CLOUDINARY_*` hoặc credential của cổng đang chọn thì app **chết lúc khởi động**, có chủ đích; cổng không
  dùng thì không bắt buộc.
- Backend cần `JWT_SECRET` dạng Base64, giải mã tối thiểu 32 byte; `.env.example` và Docker Compose có key mẫu chỉ
  dùng cho local.
- `JWT_ACCESS_TOKEN_TTL` là thời hạn access token dạng ISO-8601, mặc định `PT15M`.
- Build + test BE: `cd backend && ./mvnw -B verify` (JaCoCo: chặn nếu coverage LINE hoặc BRANCH toàn codebase < 80%; CI chặn thêm nếu dòng thay đổi < 80% và lưu artifact `backend-coverage`).
- Seed dev (Flyway V2): 8 account `@lemar.vn` (admin/bom1/fm1/fm2/fs1/customer1/unverified/banned) — mật khẩu `Test@1234`. `unverified` chưa xác minh email — bị chặn login (403); `banned` bị chặn (403).

## Cấu trúc

| Thư mục | Nội dung |
|---|---|
| `backend/` | Spring Boot modular monolith (16 module) — xem `backend/AGENTS.md` |
| `frontend/` | React + Vite — xem `frontend/AGENTS.md` |
| `specs/` | Flow reference (`draft.md` đọc-only) + schema DB |
| `.github/` | Issue form, PR gate, CI |
