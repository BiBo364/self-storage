---
purpose: Upload file (ảnh/PDF) dùng chung cho các flow — trả URL để submit vào flow sau.
---

# POST /api/media/uploads

FE upload ảnh hiện trạng / scan hợp đồng / ảnh giấy tờ trước, rồi dùng URL trả về khi submit flow.

- **Actor / quyền:** mọi tài khoản đã đăng nhập (`ACCOUNT_ACTIVE`) — không giới hạn role.
- **Contract chi tiết:** annotation tại `MediaController.java`; Swagger UI —
  `http://localhost:8080/swagger-ui/index.html` (bấm **Authorize** và dán JWT).

## Luồng / hành vi

- Nhận `multipart/form-data` field `file`; kiểm loại **hai lớp**: content-type client khai phải nằm
  trong allowlist (JPEG/PNG/WebP/PDF — header kèm tham số như `; charset=binary` vẫn nhận,
  `image/jpg` map về JPEG), và **nội dung phải khớp loại khai báo** theo chữ ký (magic bytes) —
  khai PDF mà nội dung là ảnh → 400 "Nội dung file không khớp với định dạng khai báo". Kích thước
  ≤ 10MB — sai trả 400 với message cụ thể.
- Lưu lên Cloudinary với `resource_type=image` → Cloudinary kiểm nội dung thật; file hỏng hoặc bị
  provider từ chối nội dung → **400** ("File không đọc được hoặc không đúng định dạng ảnh/PDF"),
  không lọt thành asset `raw`.
- File nằm trong folder riêng của account đang đăng nhập: `uploads/acc-{accountId}` — upload mồ côi
  (flow không hoàn tất) vẫn truy vết được chủ sở hữu trong console storage.
- `batchId` tuỳ chọn (form field, UUID): các file cùng một đợt upload dùng chung batchId. Thiếu thì
  server generate UUID và trả về. FE dùng batchId để gọi [delete-batch](delete-batch.md) khi người
  dùng bấm X trên file trong phase hiện tại, hoặc [attach-batch](attach-batch.md) khi phase đã
  submit. Rời trang/Hủy upload không xoá; batch Pending được giữ để quay lại.
- Mỗi upload ghi một row `media_uploads`: `Uploading` trước khi gọi Cloudinary, `Pending` sau khi
  lưu xong. Row `Canceled` (do Hủy batch) làm upload completion xoá asset vừa lưu.
- Servlet multipart nhận tới 12MB (`application.yml › spring.servlet.multipart`) để file 10–12MB
  rơi vào validator và có message 400 riêng.
- Ghi `media_uploads` để dọn theo batch; **không audit** — audit nằm ở flow gắn URL vào entity.
- Lỗi provider/network → 500 chung; transaction rollback row `Uploading`, không có row `Failed`;
  chi tiết chỉ vào log.

## Ghi chú nghiệp vụ

- Giới hạn 10MB khớp cap upload của Cloudinary free plan.
- Mọi file (kể cả ảnh giấy tờ) dùng URL public của Cloudinary; không có cơ chế delivery private.
- File mồ côi còn lại trên Cloudinary nếu FE không gọi delete batch — không có job tự dọn.

## Ví dụ

```bash
curl -s -X POST http://localhost:8080/api/media/uploads \
  -H "Authorization: Bearer <JWT>" -F "file=@anh.png"
{"message":"Tải file lên thành công","data":{"url":"https://res.cloudinary.com/dhbcghtis/image/upload/v1793616392/uploads/acc-5/p8wyywluqr8jbiqfqp82.png","publicId":"uploads/acc-5/p8wyywluqr8jbiqfqp82","bytes":68,"format":"png","batchId":"a7d650a8-634d-42fa-8687-a8d303eab1e1"}}
```

## Liên quan

- [delete-batch.md](delete-batch.md) · [attach-batch.md](attach-batch.md) ·
  [routes.md](../routes.md) · [index.md](../index.md)
