---
purpose: Gắn batch upload — chặn X/delete xoá asset sau khi phase đã submit.
---

# POST /api/media/uploads/batch/{batchId}/attach

Flow gọi sau khi đã submit URL của batch (ví dụ bước inspection/sign của handover). Sau đó file
không còn sửa/xoá từ checklist.

- **Actor / quyền:** mọi tài khoản đã đăng nhập (`ACCOUNT_ACTIVE`); chỉ chủ batch.
- **Contract chi tiết:** annotation tại `MediaController.java`; Swagger UI —
  `http://localhost:8080/swagger-ui/index.html`.

## Luồng / hành vi

- Khoá các row của batch theo `account_id + batch_id`; không có row nào:
  - batch thuộc account khác → 403;
  - batch không tồn tại → 404.
- Batch có row `Canceled` / `Deleted` → 409 "Batch đã bị huỷ".
- Row `Pending` → `Attached`; row đã `Attached` bỏ qua. Row `Uploading` bỏ qua.
- Idempotent: gọi lại trả `attached=0` nếu tất cả đã attach.

## Ghi chú nghiệp vụ

- Response `{attached}`: số row Pending vừa chuyển Attached.
- Không audit — hành vi đánh dấu file đã dùng, không đổi state nghiệp vụ của flow.
- Khi phase chuyển, FE không còn hiển thị nút X; dù có gọi delete batch thì row `Attached` bị bỏ qua.

## Ví dụ

```bash
curl -s -X POST http://localhost:8080/api/media/uploads/batch/abafc81e-ed18-4014-a6b5-ca2dd8445ffa/attach \
  -H "Authorization: Bearer <JWT>"
{"message":"Đã gắn batch","data":{"attached":1}}
```

## Liên quan

- [upload.md](upload.md) · [delete-batch.md](delete-batch.md) · [routes.md](../routes.md)
