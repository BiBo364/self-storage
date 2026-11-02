---
auth-model: role-bound
purpose: Cơ sở mà FM hiện tại phụ trách — bỏ hardcode facility ID ở màn hàng đợi.
---
# GET /api/fm/facility

Trả cơ sở gắn với `facilities.fm_account_id` của account đang đăng nhập.

- **Actor / quyền:** role `FM` (route `/api/fm/**`); self-scoped, không cần permission theo cơ sở.
- **Contract chi tiết:** annotation tại `FmFacilityController.java`; Swagger UI —
  `http://localhost:8080/swagger-ui/index.html`.

## Luồng / hành vi

- `facilities.fm_account_id` là quan hệ 1–1 → trả một object, không trả list.
- Fields: `id`, `code`, `name`, `address`, `operatingHours`, `status`.
- Account FM chưa được gán cơ sở → `404` `"Tài khoản FM chưa được gán cơ sở"`.

## Ghi chú nghiệp vụ

- Không dùng `facility.access` vì tài khoản chỉ có tối đa một cơ sở; route đã role-bound `FM`.
- Đây là nguồn để FE lấy `facilityId` cho các màn FM khác, thay vì hardcode.
- Account FM bị `BANNED` hoặc chưa xác minh bị chặn ở authentication (`403`), nên endpoint không
  được gọi. `facilities.fm_account_id` không tự xóa: cơ sở không còn FM vận hành, FM khác không
  thuộc assignment nhận `404`. Chưa có API gán lại FM — reassign hiện thủ công/Flow 5.
- Endpoint không lọc `facilities.status`: cơ sở `Inactive` vẫn trả nếu `fm_account_id` khớp.

## Ví dụ

```bash
curl -s -H "Authorization: Bearer $FM_TOKEN" \
  http://localhost:8080/api/fm/facility
# {"message":"Lấy cơ sở của FM thành công","data":{"id":1,"code":"Q7","name":"Self Storage Quận 7","address":"123 Nguyễn Văn Linh, Quận 7, TP.HCM","operatingHours":"08:00–20:00 hằng ngày","status":"Active"}}
```

## Liên quan

- Xem thêm: [routes.md](../../routes.md) · [index.md](../../index.md)
