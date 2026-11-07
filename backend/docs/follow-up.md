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
- Quản trị hệ thống (`/api/admin/**`) có tài khoản / phân quyền / nhật ký ([backoffice/overview.md](backoffice/overview.md));
  các route Flow 3–7 dùng lại permission có sẵn (`rental_order.read`, `appointment.assign`, `unit_access_key.issue`,
  `facility.access`) — chưa có permission riêng cho trả kho / hỗ trợ / back-office.

## Kho đang thuê / trả kho

- Nhắc `CONTRACT_EXPIRING_SOON` / `CONTRACT_OVERDUE` dedup theo đơn + type (`order_notifications`) — sau khi gia hạn
  rồi lại sắp hết hạn, khách không được nhắc lần hai.
- Khoang còn đồ (`PENDING_ITEMS`) không tự tạo lịch `RETURN` mới; FS kiểm lại khi khách quay lại.
- Lịch `RETURN` quá giờ mà khách không đến không có cron xử lý; FM phân công lại.
- Hoá đơn đối trừ cọc ghi `Paid` → báo cáo doanh thu cần tách theo `audit new_value.settledBy` nếu muốn phân biệt.

## Handover

- Cấu hình loại khóa của cơ sở (`facilities.enabled_key_access` / `enabled_code_access`) chưa có màn hình — đổi
  bằng SQL cho tới khi Flow 5 có quản lý cơ sở.
- Mẫu hợp đồng là mẫu dựng sẵn `builtin-v1` trong code; bảng mẫu có version + danh sách biến thuộc Flow 4.
- Ảnh hiện trạng và bản scan hợp đồng chỉ lưu URL; luồng bàn giao chưa gọi `attach` batch media nên FE cần tự gắn
  batch sau khi lưu thành công.

## Notification

- Caller nghiệp vụ đầu tiên: luồng convert gửi `RENTAL_REQUEST_APPROVED` (có `orderId`) —
  [booking/conversion.md](booking/conversion.md). Handover/payment nối sau; khi nối flow, truyền
  `orderId` để FE deep-link mở đơn.
- `RENTAL_ORDER_CANCELED`: template dùng `{{outcome}}` — cascade hiện luôn truyền `"đã bị hủy"`, kể cả các lý do
  quá hạn của cron; muốn hiển thị `"đã hết hạn"` thì cần map `cancel_reason` → outcome.
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
