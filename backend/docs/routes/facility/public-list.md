---
purpose: Catalog cơ sở public cho trang browse và form gửi yêu cầu; sơ đồ cơ sở public.
---
# GET /api/facilities · GET /api/facilities/{id}/layout

Trả các cơ sở đang hoạt động, kèm loại khoang mỗi cơ sở cung cấp.

- **Actor / quyền:** public (`permitAll` đúng scope `GET /api/facilities` trong `SecurityConfig`).
- **Contract chi tiết:** annotation tại `FacilityCatalogController.java`; Swagger UI —
  `http://localhost:8080/swagger-ui/index.html`.

## Luồng / hành vi

- Chỉ cơ sở `status = 'Active'`, order by `id`.
- Mỗi dòng: `id`, `code`, `name`, `address`, `operatingHours`, `unitTypeIds`, `latitude`, `longitude` (ghim bản
  đồ, cùng `null` khi chưa ghim), `floorCount`. FE vẽ bản đồ OpenStreetMap ghim các cơ sở có toạ độ, nút "Chỉ đường"
  mở Google Maps theo toạ độ.
- `unitTypeIds` lấy từ `facility_unit_type`, sorted tăng dần — FE lọc theo loại khoang mà không
  phải gọi `GET /api/unit-types` cho từng cơ sở.
- Không trả `fm_account_id`; cơ sở `Inactive` không xuất hiện.

## Sơ đồ cơ sở — GET /api/facilities/{id}/layout

- Public (`permitAll` `GET /api/facilities/*/layout`), mọi trạng thái cơ sở (khách của cơ sở đã ngưng vẫn cần tìm
  khoang). Không có cơ sở → 404 `"Không tìm thấy cơ sở"`.
- Trả `facilityId`, `code`, `name`, `floorCount` và `units` = khoang **đã xếp vị trí**: `code`, `unitTypeName`,
  `floorNo`, `aisleSide` (`Left`/`Right`, nhìn từ cửa vào), `aislePosition` (1 = sát cửa vào), `available`; order theo
  tầng, bên, thứ tự.
- `available` = khoang đang `Available` (còn nhận thuê); `Reserved` / `Rented` / `Maintenance` đều là `false` — không
  lộ trạng thái chi tiết. Trang chọn kho dùng cờ này để khách thấy còn bao nhiêu khoang trống ở từng tầng; thẻ vị trí
  khoang của khách / FS không dùng. FM / BOM đọc trạng thái đầy đủ qua danh sách khoang của back-office
  ([backoffice/overview.md](../../backoffice/overview.md)).
- Vị trí khoang: tầng 1..`facilities.floor_count`, một vị trí một khoang (unique index `uq_storage_units_slot`). Khoang
  chưa xếp không có trong `units`.

## Ghi chú nghiệp vụ

- Catalog public không thay đổi quyền đọc dữ liệu vận hành; thông tin nhạy cảm (FM phụ trách,
  trạng thái nội bộ) không nằm trong response.
- Catalog chỉ lọc `facilities.status`; không kiểm account FM. Cơ sở `Active` vẫn hiện dù FM bị
  `BANNED`, chưa xác minh hoặc chưa được gán.
- Cơ sở `Inactive` vẫn tồn tại trong DB và vẫn dùng được cho dữ liệu lịch sử; chỉ bị ẩn khỏi catalog.

## Ví dụ

```bash
curl -s http://localhost:8080/api/facilities
# {"message":"Lấy danh sách cơ sở thành công","data":[{"id":1,"code":"Q7","name":"Self Storage Quận 7","address":"123 Nguyễn Văn Linh, Quận 7, TP.HCM","operatingHours":"08:00–20:00 hằng ngày","unitTypeIds":[1,2,3],"latitude":10.729650,"longitude":106.718900,"floorCount":2}, ...]}

curl -s http://localhost:8080/api/facilities/1/layout
# {"message":"Sơ đồ cơ sở","data":{"facilityId":1,"code":"Q7","name":"Self Storage Quận 7","floorCount":2,
#  "units":[{"code":"A-01","unitTypeName":"Small","floorNo":1,"aisleSide":"Left","aislePosition":1,"available":true}, ...]}}
```

## Liên quan

- Xem thêm: [routes.md](../../routes.md) · [index.md](../../index.md)
