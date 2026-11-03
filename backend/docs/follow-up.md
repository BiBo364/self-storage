---
purpose: Việc còn lại đã biết nhưng chưa có issue riêng — ghi để không quên.
---
# Follow-up

Việc còn lại đã biết, chưa có issue riêng — ghi để không quên. Việc nào thành task chính thức
thì mở issue theo workflow; file này không thay thế issue.

## RBAC

- Guard hiện gọi trực tiếp `Access.can(...)` ở controller. Cân nhắc chuyển sang annotation
  (`@PreAuthorize` gọi bean, hoặc annotation riêng + interceptor) khi số endpoint tăng — lý do chưa
  làm ghi ở `TODO(flow-5)` trong `identity/application/Access.java`.
- Quản trị hệ thống (`/api/admin/**`) chưa có endpoint nào; permission catalog mở rộng dần theo
  Flow 3–7 — xem [permission-catalog.md](permission-catalog.md).

## Notification

- Caller nghiệp vụ đầu tiên: luồng convert gửi `RENTAL_REQUEST_APPROVED` (có `orderId`) —
  [booking/conversion.md](booking/conversion.md). Handover/payment nối sau; khi nối flow, truyền
  `orderId` để FE deep-link mở đơn.
- `RENTAL_ORDER_CANCELED`: template dùng `{{outcome}}` — caller của Cancellation Cascade phải map
  `cancel_reason` → `"đã bị hủy"` / `"đã hết hạn"`; nên có một helper dùng chung khi làm cascade.
- Nhắc `RENTAL_ORDER_EXPIRING_SOON` mỗi đơn một lần: cron tương lai phải tự dedup qua liên kết
  `OrderNotification` (repository hiện chỉ có `findByOrderId`, chưa có query theo order + type).
- Phân trang list trả mảng không kèm `total` — FE tải thêm tới khi trang rỗng; nếu cần
  `total`/`hasMore` phải mở issue đổi contract.

## Audit

- AuditLog đi theo hướng service ghi audit gọi tường minh từ call site. Hướng AOP interceptor
  tạm gác lại: interceptor không tự biết `entity_id` / `old_value` / `new_value` khi
  một thao tác đổi nhiều entity — caller vẫn phải truyền đủ dữ liệu, thêm annotation lúc này chỉ
  che luồng chạy thật. Xem lại khi số call site đủ nhiều và pattern đã ổn định.

## Media

- **File mồ côi**: batch delete/attach đã có (`media_uploads`); FE chỉ xoá khi người dùng bấm X
  trên file trong phase hiện tại. Batch Pending bị bỏ ngang (rời trang/Hủy) vẫn mồ côi — chưa có
  job sweep `Uploading`/`Pending` quá hạn; cân nhắc khi hạ tầng scheduler xong.
- Không rate limit upload; trùng file tạo nhiều asset (không dedup) — chấp nhận ở MVP.
- Batch delete không xoá asset `Attached`; nếu phase đã submit rồi mới phát hiện sai thì cần flow xử
  lý riêng (hiện chưa có route delete theo `publicId`).
- Xoá media khi order bị **hard-delete** hoặc theo retention policy chưa có; chưa nên tự xoá khi
  đơn chỉ bị Canceled vì media là bằng chứng/audit.
