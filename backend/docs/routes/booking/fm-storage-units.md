---
auth-model: permission-bound
purpose: Danh sách khoang của một cơ sở cho màn duyệt yêu cầu / quản lý khoang của FM.
---
# GET /api/fm/storage-units?facilityId=&status=&unitTypeId=

Trả danh sách khoang của cơ sở trong phạm vi FM.

- **Actor / quyền:** role `FM`; `facility.access` + facility scope (403 nếu ngoài cơ sở).
- **Contract chi tiết:** annotation tại `FmStorageUnitController.java`; Swagger UI —
  `http://localhost:8080/swagger-ui/index.html`.

## Luồng / hành vi

- `facilityId` bắt buộc.
- `status` optional: `Available` / `Reserved` / `Rented` / `Maintenance`; sai → `400`.
- `unitTypeId` optional.
- Order by `code`; MVP không phân trang.
- Mỗi dòng: `id`, `code`, `facilityId`, `unitTypeId`, `unitTypeName`, `size`, `area`, `status`,
  `monthlyPrice`, `unitPrice`, `maintenanceStartedAt`.
- `monthlyPrice = coalesce(storage_units.monthly_price, unit_types.monthly_price)`; `unitPrice` = giá riêng của
  khoang (`null` = dùng giá loại khoang).
- `maintenanceStartedAt` = mốc bảo trì sau trả kho (cron tự mở lại sau `unit.maintenance_days`); `null` = khoang không
  bảo trì hoặc bảo trì thủ công (FM bật tay, trả kho có mất chìa, FM "Giữ bảo trì"). FE dùng field này để hiện nút
  "Giữ bảo trì" (`POST /api/fm/storage-units/{unitId}/maintenance-hold`,
  [backoffice/overview.md](../../backoffice/overview.md)).
- Cơ sở không tồn tại → `404`; ngoài phạm vi FM → `403`.

## Ghi chú nghiệp vụ

- Controller nằm ở `booking` vì module `facility` không được phụ thuộc `identity` để gọi `Access`;
  dữ liệu vẫn đọc qua public API của `facility`.
- Guard bằng `facility.access` theo cơ sở — cùng permission với back-office FM
  ([permission-catalog.md](../../permission-catalog.md)).
- Trạng thái trả về giữ PascalCase như DB.

## Ví dụ

```bash
curl -s -H "Authorization: Bearer $FM_TOKEN" \
  "http://localhost:8080/api/fm/storage-units?facilityId=1&status=Maintenance"
# {"message":"Lấy danh sách khoang thành công","data":[{"id":8,"code":"C-01","facilityId":1,"unitTypeId":3,"unitTypeName":"Large","size":null,"area":7.50,"status":"Maintenance","monthlyPrice":1400000.00}]}
```

## Liên quan

- Xem thêm: [routes.md](../../routes.md) · [index.md](../../index.md)
