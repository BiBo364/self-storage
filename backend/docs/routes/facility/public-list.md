---
purpose: Catalog cơ sở public cho trang browse và form gửi yêu cầu.
---
# GET /api/facilities

Trả các cơ sở đang hoạt động, kèm loại khoang mỗi cơ sở cung cấp.

- **Actor / quyền:** public (`permitAll` đúng scope `GET /api/facilities` trong `SecurityConfig`).
- **Contract chi tiết:** annotation tại `FacilityCatalogController.java`; Swagger UI —
  `http://localhost:8080/swagger-ui/index.html`.

## Luồng / hành vi

- Chỉ cơ sở `status = 'Active'`, order by `id`.
- Mỗi dòng: `id`, `code`, `name`, `address`, `operatingHours`, `unitTypeIds`.
- `unitTypeIds` lấy từ `facility_unit_type`, sorted tăng dần — FE lọc theo loại khoang mà không
  phải gọi `GET /api/unit-types` cho từng cơ sở.
- Không trả `fm_account_id`; cơ sở `Inactive` không xuất hiện.

## Ghi chú nghiệp vụ

- Catalog public không thay đổi quyền đọc dữ liệu vận hành; thông tin nhạy cảm (FM phụ trách,
  trạng thái nội bộ) không nằm trong response.
- Catalog chỉ lọc `facilities.status`; không kiểm account FM. Cơ sở `Active` vẫn hiện dù FM bị
  `BANNED`, chưa xác minh hoặc chưa được gán.
- Cơ sở `Inactive` vẫn tồn tại trong DB và vẫn dùng được cho dữ liệu lịch sử; chỉ bị ẩn khỏi catalog.

## Ví dụ

```bash
curl -s http://localhost:8080/api/facilities
# {"message":"Lấy danh sách cơ sở thành công","data":[{"id":1,"code":"Q7","name":"Self Storage Quận 7","address":"123 Nguyễn Văn Linh, Quận 7, TP.HCM","operatingHours":"08:00–20:00 hằng ngày","unitTypeIds":[1,2,3]},{"id":2,"code":"TD","name":"Self Storage Thủ Đức","address":"456 Võ Văn Ngân, Thủ Đức, TP.HCM","operatingHours":"08:00–20:00 hằng ngày","unitTypeIds":[1,2]}]}
```

## Liên quan

- Xem thêm: [routes.md](../../routes.md) · [index.md](../../index.md)
