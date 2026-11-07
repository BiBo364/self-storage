---
purpose: Xoá một batch upload chưa attach — dùng khi người dùng bấm X trên file trong phase hiện tại.
---

# DELETE /api/media/uploads/batch/{batchId}

FE gọi khi người dùng bấm X trên một file đã upload **trong phase hiện tại** (trước khi submit
phase). Không gọi khi rời trang hoặc Hủy upload — batch Pending được giữ để quay lại.

- **Actor / quyền:** mọi tài khoản đã đăng nhập (`ACCOUNT_ACTIVE`); chỉ chủ batch
  (`media_uploads.account_id`).
- **Contract chi tiết:** annotation tại `MediaController.java`; Swagger UI —
  `http://localhost:8080/swagger-ui/index.html`.

## Luồng / hành vi

- Khoá các row của batch theo `account_id + batch_id`; không có row nào:
  - batch thuộc account khác → 403;
  - batch không tồn tại → 404.
- Row `Uploading` / `Pending` → `Canceled`; nếu đã có `public_id` thì xoá asset trên Cloudinary rồi
  chuyển `Deleted`.
- Row `Uploading` chưa có `public_id`: giữ `Canceled`; upload completion (sau khi Cloudinary trả về)
  thấy row `Canceled` sẽ xoá asset vừa lưu. Nhánh này là phòng thủ: row `Uploading` nằm trong
  transaction upload, delete batch từ transaction khác chỉ thấy row sau khi upload commit. FE chỉ
  hiện X sau khi upload xong nên flow thực tế không trúng; xoá giữa lúc upload không được bảo đảm.
- Row `Attached` bỏ qua (phase đã submit, file không được xoá nữa); row `Canceled` / `Deleted` bỏ qua.
- Idempotent: gọi lại trả `deleted=0`, `skipped=n`.

## Ghi chú nghiệp vụ

- Response `{deleted, skipped}`: số asset đã xoá/cancel và số asset bỏ qua.
- Gọi Cloudinary nằm trong transaction; provider lỗi → rollback row, client có thể retry.
- Không audit — hành vi dọn file, không đổi state nghiệp vụ của flow.
- Sau khi phase chuyển, FE không còn hiển thị nút X; file đã attach và delete batch sẽ bỏ qua.
- Batch `Pending` bị bỏ ngang (không X, không attach) giữ nguyên: không có job nào tự dọn, asset còn trên Cloudinary.

## Ví dụ

```bash
curl -s -X DELETE http://localhost:8080/api/media/uploads/batch/a7d650a8-634d-42fa-8687-a8d303eab1e1 \
  -H "Authorization: Bearer <JWT>"
{"message":"Đã huỷ batch","data":{"deleted":1,"skipped":0}}
```

## Liên quan

- [upload.md](upload.md) · [attach-batch.md](attach-batch.md) · [routes.md](../routes.md)
