<!--
Hướng dẫn đầy đủ: docs/guides/pull-request.md
Giữ nguyên các heading bắt buộc; thêm/bớt subsection bên trong nếu cần.
PR title: conventional commit + scope, tiếng Anh. PR body: tiếng Việt.
-->

## Việc gì

<!-- 1-2 câu. Ghi issue cụ thể: `Closes #N`. Không Closes epic/package. -->

`Closes #N`

### Scope

<!-- Việc PR này làm. 3-6 bullet, nêu behaviour, không liệt kê file. -->

- ...

### Không làm trong PR này

<!-- Phần liên quan nhưng cố ý để PR/issue khác. -->

- ...

## Căn cứ trong docs module

<!--
Bảng hành vi → docs. Ghi file + heading cụ thể.
Nếu docs chưa rõ: behavior-gap #N đã duyệt.
Nếu đổi hành vi đã có: proposal #N đã duyệt.
-->

| Hành vi | Căn cứ |
|---|---|
| ... | `backend/docs/...` › ... |

## Đã đổi gì

<!--
Đây là phần reviewer dùng để hiểu implementation.
Không kể lịch sử commit, không dump file. Nêu behaviour, invariant, đường đi.
-->

### Migration map

<!-- Bỏ subsection này nếu PR không đổi schema. -->

| Version | File | Nội dung |
|---|---|---|
| V… | `db/migration/...` | ... |

### Luồng chính

#### Entry point

<!-- Controller/event/job nào bắt đầu flow. -->

- ...

#### Behaviour

<!--
Các bước theo thứ tự thực thi. Ghi rõ state transition, lock, idempotency,
side effect, audit/notification. Mỗi bullet một fact.
-->

1. ...
2. ...

### API

<!-- Liệt kê endpoint thay đổi/thêm: method, path, actor/quyền, input/output chính. -->

- `METHOD /path` — ...

### Frontend

<!-- Route/page/API client và hành vi UI. Bỏ nếu không có. -->

- ...

### Review order

<!-- Thứ tự đọc diff hợp lý nhất, đặc biệt với PR lớn. -->

1. Migration
2. Money/idempotency/concurrency
3. Listener/side effect
4. API/FE
5. Docs/tests

## Đã chạy gì — kèm kết quả

<!-- Dán output thật trong code block. Không có output = chưa chạy. -->

```text
$ cd backend && ./mvnw -B clean verify -Dapi.version=1.44
...
```

## Chưa kiểm chứng

<!--
BẮT BUỘC. Ghi rõ chưa chạy gì và vì sao. Không được để trống.
Ghi "không có" nếu đã kiểm hết.
-->

- ...

## Rủi ro & chỗ cần review kỹ

<!--
Ưu tiên: migration, tiền, quyền, lock/concurrency, dữ liệu cũ, test flake.
Nêu trade-off hoặc giả định, không chỉ "cần review".
-->

- ...

## Checklist

- [ ] Docs chỗ nào không rõ → đã mở **behavior-gap** và dừng đúng ở phần đó
- [ ] Không sửa test để cho pass
- [ ] Đổi schema → có migration, không dùng `ddl-auto`
- [ ] Thay đổi trạng thái / tiền / quyền sở hữu → có `AuditLog`
- [ ] Không commit `.env`, credential, token
- [ ] Không mở rộng scope ngoài issue
