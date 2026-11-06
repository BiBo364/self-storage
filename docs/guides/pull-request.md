# Viết PR body để review

PR body không phải changelog. Nó là **bản đồ để người khác review implementation mà không phải
dựng lại bức tranh từ diff**. Người viết biết code; reviewer thì không. Viết cho reviewer.

Template: [`.github/pull_request_template.md`](../../.github/pull_request_template.md)

Gate: [`.github/workflows/pr-gate.yml`](../../.github/workflows/pr-gate.yml)

## 1. Nguyên tắc

1. **Scope trước, chi tiết sau.** Nói PR làm gì và **không** làm gì trước khi đi vào flow.
2. **Nói behaviour, không kể file.** "Success thứ hai thành Duplicate" tốt hơn "sửa PaymentService".
3. **Đưa thứ nguy hiểm lên trước.** Migration, tiền, quyền, lock, idempotency, audit.
4. **Mỗi ý có địa chỉ.** File path, endpoint, migration version, test class.
5. **Tách implementation khỏi review order.** `Đã đổi gì` mô tả; `Review order` chỉ thứ tự đọc.
6. **Không giấu phần chưa làm.** `Chưa kiểm chứng` ghi rõ chưa chạy gì và vì sao.
7. **Evidence là output thật.** Command + output + kết quả, không phải "test pass".
8. **Không viết lại lịch sử commit.** Người đọc không cần biết commit nào ra đời trước.

## 2. Trước khi viết body

Thu thập đủ nguyên liệu:

1. `git diff --name-only origin/main...HEAD` — nhóm file theo feature.
2. `git diff --stat` — biết PR lớn cỡ nào.
3. Đọc migration trước: version, thứ tự, constraint, audit check.
4. Đọc entrypoint: controller → service → listener/repository.
5. Trace một flow end-to-end: request → DB → event → side effect.
6. Đọc test: điều gì thực sự được bảo vệ, test nào chỉ mock.
7. Đọc docs module: contract hiện tại và docs đã sửa trong PR.
8. Ghi lại phần chưa chạy, chưa chắc, hoặc phải để PR sau.

Không cần đọc từng dòng của PR lớn. Đọc **đủ để giải thích đường đi và invariant**.

## 3. Cấu trúc bắt buộc

`pr-gate` kiểm đúng các heading này. Giữ nguyên, chỉ thêm subsection bên trong:

```text
## Việc gì
## Căn cứ trong docs module
## Đã đổi gì
## Đã chạy gì — kèm kết quả
## Chưa kiểm chứng
## Rủi ro & chỗ cần review kỹ
## Checklist
```

Không đổi thành `## 1. Việc gì` hoặc `## Scope` — gate sẽ fail.

## 4. Viết từng mục

### `## Việc gì`

- 1–2 câu mô tả kết quả, không mô tả hoạt động.
- Ghi issue cụ thể: `Closes #N`. Không `Closes` epic/package.
- Thêm `### Scope` và `### Không làm trong PR này`.
- Scope nên 3–6 bullet, mỗi bullet là một behaviour hoặc một giới hạn.

### `## Căn cứ trong docs module`

Dùng bảng:

```markdown
| Hành vi | Căn cứ |
|---|---|
| Invoice chuyển Paid sau IPN | `backend/docs/routes/booking/invoice-pay.md` › Nhánh thành công |
```

- Ghi file + heading, không chỉ file.
- Nếu docs chưa rõ: behavior-gap đã duyệt.
- Nếu đổi hành vi đã có: proposal đã duyệt.
- Nếu docs và code lệch: dừng, mở behavior-gap; không tự chọn.

### `## Đã đổi gì`

Đây là phần chính. Chia subsection theo **cách reviewer nghĩ**, không theo thứ tự file:

- `### Migration map` — bảng version/file/nội dung; bỏ nếu không đổi schema.
- `### Luồng chính` — entry point + các bước theo thứ tự.
- `### API` — method/path/actor/input/output/lỗi.
- `### Frontend` — route/page/API client/hành vi.
- `### Review order` — thứ tự đọc diff.

Trong flow, ghi rõ:

- state transition;
- lock và thứ tự lock;
- idempotency/dedup;
- audit/notification side effect;
- chỗ có thể rollback hoặc commit ngoài ý muốn.

Không liệt kê file không liên quan. Không dán diff.

### `## Đã chạy gì — kèm kết quả`

Luôn có code block output thật:

```text
$ cd backend && ./mvnw -B clean verify -Dapi.version=1.44
Tests run: ...
BUILD SUCCESS
```

- Ghi cả frontend nếu có.
- Nếu có flake, ghi rõ lần đầu fail gì, chạy lại thế nào, kết quả cuối.
- Không ghi "test pass" mà không có output.

### `## Chưa kiểm chứng`

Bắt buộc có nội dung. Ghi:

- chưa chạy E2E vì ...;
- chưa test DB cũ vì ...;
- chưa bấm browser vì ...;
- phần thuộc PR sau.

Nếu đã kiểm hết, ghi `Không có`.

### `## Rủi ro & chỗ cần review kỹ`

Ưu tiên:

- migration/constraint;
- tiền/idempotency;
- quyền/ownership;
- lock/concurrency;
- dữ liệu cũ;
- test flake/giả định một instance.

Nêu trade-off hoặc giả định cụ thể. "Cần review kỹ" một mình là vô nghĩa.

### `## Checklist`

Tick hết trước khi xin review. Gate chặn `- [ ]`.

## 5. Lỗi thường gặp

- **Kể lịch sử commit** thay vì behavior.
- **Dump file** thay vì flow.
- **Scope mở** nhưng không ghi phần không làm.
- **Migration map mơ hồ**: không ghi version/file.
- **Test evidence không có output**.
- **`Chưa kiểm chứng` để trống**.
- **Checklist còn ô chưa tick**.
- **Title không theo `type(scope): ...`**.
- **Dùng `Closes` cho epic/package**.

## 6. Kiểm tra trước khi mở PR

```text
[ ] Title: type(scope): mô tả ngắn, tiếng Anh
[ ] Body có đủ 7 heading bắt buộc
[ ] Có `Closes #N` với issue cụ thể
[ ] `Đã chạy gì` có code block output thật
[ ] `Chưa kiểm chứng` có nội dung
[ ] `Rủi ro` nêu chỗ cụ thể
[ ] Checklist đã tick hết
[ ] Không có secret/token/.env
```

## 7. Liên quan

- [AGENTS.md](../../AGENTS.md) — workflow, evidence, definition of done.
- [`.github/pull_request_template.md`](../../.github/pull_request_template.md) — mẫu điền.
- [`.github/workflows/pr-gate.yml`](../../.github/workflows/pr-gate.yml) — gate tự động.
