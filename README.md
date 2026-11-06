# self-storage

Hệ thống cho thuê kho self-storage. Modular monolith: **Spring Boot 3.3 · Java 17** (backend) + **React 19 · Vite** (frontend — đang hợp nhất).

- Nguồn sự thật: docs trong từng module (`backend/docs/`, và `frontend/docs/` khi có).
  `specs/draft.md` chỉ để tham khảo flow — không sửa; `specs/db-table-draft.md` cập nhật theo schema.
  Đọc `AGENTS.md` trước khi code.
- Quy trình: issue → branch → PR (`Closes #N`) → CI xanh + 1 approval → merge. Trước khi mở PR,
  đọc [hướng dẫn viết PR body](docs/guides/pull-request.md).
- Roadmap: theo dõi trong GitHub Issues.

## Chạy nhanh (dev)

```bash
# 1) Hạ tầng: Postgres + Redis + MailHog (xem mail tại http://localhost:8025)
docker compose up -d

# 2) Backend (Java 17)
cd backend
./mvnw spring-boot:run            # Windows: mvnw.cmd spring-boot:run
curl http://localhost:8080/api/health
# Swagger UI: http://localhost:8080/swagger-ui/index.html

# 3) Frontend (khi có trong main)
cd frontend && npm ci && npm run dev
```

- Build cả app trong Docker (tuỳ chọn): `docker compose --profile app up -d`
- Env: copy `.env.example` → `.env` khi cần override (không commit `.env`).
- Backend cần `JWT_SECRET` dạng Base64, giải mã tối thiểu 32 byte; Docker Compose dùng key mẫu
	chỉ cho local. Khi chạy `mvnw spring-boot:run` trực tiếp, đặt `JWT_SECRET` trong environment trước.
- `JWT_ACCESS_TOKEN_TTL` là thời hạn access token dạng ISO-8601, mặc định `PT15M`.
- Build + test BE: `cd backend && ./mvnw -B verify` (JaCoCo: chặn nếu coverage LINE hoặc BRANCH toàn codebase < 80%; CI chặn thêm nếu dòng thay đổi < 80% và lưu artifact `backend-coverage`).
- Seed dev (Flyway V2): 8 account `@lemar.vn` (admin/bom1/fm1/fm2/fs1/customer1/unverified/banned) — mật khẩu `Test@1234`. `unverified` chưa xác minh email — bị chặn login (403); `banned` bị chặn (403).

## Cấu trúc

| Thư mục | Nội dung |
|---|---|
| `backend/` | Spring Boot modular monolith (12 module) — xem `backend/AGENTS.md` |
| `frontend/` | React + Vite — xem `frontend/AGENTS.md` |
| `specs/` | Flow reference (`draft.md` đọc-only) + schema DB |
| `.github/` | Issue form, PR gate, CI |
