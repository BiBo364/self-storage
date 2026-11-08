---
purpose: Back-office as-built — FM quản lý khoang / xem hoá đơn, hợp đồng, báo cáo; BOM quản lý cơ sở, policy, giá; Admin tài khoản, phân quyền, nhật ký.
---
# Back-office (Flow 4, Flow 5, quản trị hệ thống)

Spec Flow 4/5 chỉ có tiêu đề; trang này là hành vi as-built và là contract của các route `/api/fm/invoices`,
`/api/fm/contracts`, `/api/fm/reports`, `/api/fm/storage-units…`, `/api/fm/facilities/{id}/access-options`,
`/api/bom/**`, `/api/admin/**` (không có file riêng trong `routes/`; field chi tiết ở Swagger trên
`BackofficeController`).

| Phần | Code |
|---|---|
| Kiểm quyền, audit, controller | `backoffice/application/BackofficeService.java`, `backoffice/controller/BackofficeController.java` |
| Báo cáo / danh sách chỉ đọc | `backoffice/repository/BackofficeReadRepository.java` |
| Ghi cơ sở / khoang | `facility/application/FacilityAdminCommands.java` |
| Ghi policy / giá loại khoang | `pricing/application/PricingAdminCommands.java` |
| Tài khoản, phân quyền, lịch sử đăng nhập | `identity/application/AccountAdminService.java` |

Mọi route cần bearer token + `ACCOUNT_ACTIVE` và đúng role theo prefix (`/api/fm/**` → `FM`, `/api/bom/**` →
`BOM`, `/api/admin/**` → `ADMIN`; sai role → 403). Lỗi nghiệp vụ của `BackofficeController`
(`BackofficeExceptionHandler`): dữ liệu sai **và** không tìm thấy khoang / cơ sở / policy / loại khoang → 400
(`IllegalArgumentException`), xung đột trạng thái → 409 (`IllegalStateException`). Route FM kiểm thêm
`facility.access` theo cơ sở: thiếu quyền / không được gán cơ sở → 403, `facilityId` không tồn tại → 404.

## FM (theo cơ sở, `facility.access` + facility scope)

| Method | Path | Hành vi |
|---|---|---|
| GET | `/api/fm/invoices?facilityId=&status=&type=` | hoá đơn của cơ sở (500 mới nhất). Hoá đơn gắn đơn thuộc cơ sở của yêu cầu đặt kho; hoá đơn gắn hợp đồng thuộc cơ sở của khoang |
| GET | `/api/fm/contracts?facilityId=&status=` | hợp đồng của cơ sở + tổng hoá đơn `Unpaid` |
| GET | `/api/fm/reports?facilityId=&from=&to=` | KPI (gồm cọc đang giữ), doanh thu theo tháng × loại (bên dưới), số khoang theo trạng thái. Mặc định 6 tháng gần nhất |
| POST | `/api/fm/storage-units` `{facilityId, code, unitTypeId, monthlyPrice?}` | thêm khoang `Available`; mã trùng trong cơ sở → 400; cơ sở tự "cung cấp" loại khoang đó (`facility_unit_type`) |
| PATCH | `/api/fm/storage-units/{id}` `{unitTypeId?, monthlyPrice?}` | đổi loại / giá riêng (`monthlyPrice` null = giá loại khoang) |
| POST | `/api/fm/storage-units/{id}/maintenance` `{maintenance}` | bảo trì tay: `Available ↔ Maintenance`. Khoang đang có đơn chưa hoàn tất trỏ tới → 409. Mở lại tay xoá `maintenance_started_at` |
| POST | `/api/fm/storage-units/{id}/maintenance-hold` | "Giữ bảo trì": khoang `Maintenance` đang chờ cron mở lại (`maintenance_started_at` khác `null`) → xoá mốc, thành bảo trì thủ công — cron không tự mở lại, FM mở lại bằng `/maintenance`. Audit `STORAGE_UNIT_MAINTENANCE` (`new_value.reason` "FM giữ bảo trì", old/new `maintenanceStartedAt`). Không tìm thấy khoang → 400; ngoài cơ sở → 403; khoang không ở bảo trì sau trả kho → 409 |
| GET | `/api/fm/facilities/{id}/access-options` | thông tin cơ sở kèm hình thức truy cập đang bật (`keyAccess`, `codeAccess`) — màn cấu hình truy cập của FM đọc từ đây |
| PUT | `/api/fm/facilities/{id}/access-options` `{keyAccess, codeAccess}` | bật / tắt khóa cơ, mã số; tắt cả hai → 400 |

Doanh thu — theo **kế toán** (`BackofficeReadRepository.revenue`, nhóm theo tháng giờ VN × loại). Tiền cọc là khoản
giữ hộ khách, **không** phải doanh thu khi thu; chỉ thành doanh thu khi bị đối trừ phí hoặc bị giữ lại.

| Dòng | Loại (`type`) | Số tiền | Mốc tháng |
|---|---|---|---|
| Hoá đơn `Rental` / `Extension` / `Penalty` / `Service` `Paid` — gồm cả phí trả kho đối trừ vào cọc (`INV-CLN` / `INV-DMG` / `INV-KEY` / `INV-PEN`) và phí quá hạn | loại hoá đơn | `amount` | `max(paid_at)` giao dịch `PAY` `Success`; không có giao dịch thu (đối trừ cọc) → `created_at` hoá đơn |
| Khoản hoàn FM ghi trên các hoá đơn trên (giao dịch `REFUND` `Success`, vd hoàn tháng đầu) | loại hoá đơn | `−amount` | `paid_at` của giao dịch hoàn |
| Cọc bị giữ lại: hoá đơn `Deposit` / `DepositSupplement` `Paid` của đơn `Canceled` | `ForfeitedDeposit` | `amount` − khoản hoàn ghi trước lúc huỷ | `rental_orders.canceled_at` (V23) |
| Hoàn cọc sau lúc huỷ (huỷ do lỗi cơ sở) | `ForfeitedDeposit` | `−amount` | `paid_at` của giao dịch hoàn |

- `count` = số hoá đơn được ghi nhận; dòng hoàn tiền không cộng `count`. `totalRevenue` = tổng `amount` mọi dòng.
- Đơn huỷ trước V23 lấy `canceled_at` từ audit `RENTAL_ORDER_CANCELED`; đơn không có audit thì `canceled_at` `NULL`
  và cọc của đơn không vào báo cáo.
- Tiền vào sau khi hoá đơn đã đóng (giao dịch `PAY` trên hoá đơn không `Paid`) không phải doanh thu — FM hoàn thủ công.

KPI:
- yêu cầu đặt kho `Pending`, đơn đang mở;
- hợp đồng `Active`, hợp đồng quá hạn;
- yêu cầu trả kho / hỗ trợ đang mở;
- tổng hoá đơn `Unpaid`;
- `depositHeld` — **cọc đang giữ** tại lúc xem: hoá đơn `Deposit` / `DepositSupplement` `Paid` của đơn chưa `Canceled`
  (trừ khoản hoàn ghi trên hoá đơn cọc), trừ hoá đơn `Penalty` `Paid` không có giao dịch thu (phần đã đối trừ vào cọc
  lúc trả kho). Cọc dư FM chưa ghi nhận hoàn vẫn nằm trong số này.

Màn "Hoá đơn cơ sở" của FM có panel "Hoàn tiền" trên từng hoá đơn, gọi `GET` / `POST
/api/fm/invoices/{invoiceId}/refunds` (quyền `payment_transaction.refund`, không phải `facility.access` —
[routes/booking/fm-invoice-refunds.md](../routes/booking/fm-invoice-refunds.md)).

## BOM (`/api/bom/**`, role `BOM`)

| Method | Path | Hành vi |
|---|---|---|
| GET / POST | `/api/bom/facilities` | danh sách (kèm FM, số khoang, số khoang `Rented`) / tạo cơ sở; mã viết hoa, trùng → 400 |
| PATCH | `/api/bom/facilities/{id}` | tên, địa chỉ, giờ mở cửa, `status` (`Active`/`Inactive`) |
| PUT | `/api/bom/facilities/{id}/manager` `{fmAccountId}` | gán FM (1–1: FM đang ở cơ sở khác bị gỡ), `null` = bỏ FM; tài khoản không phải FM → 400 |
| PUT | `/api/bom/facilities/{id}/access-options` | như route FM (cùng kiểm `facility.access`; BOM có quyền mọi cơ sở) |
| GET | `/api/bom/managers` | tài khoản FM đang hoạt động |
| GET / PUT | `/api/bom/policies`, `/api/bom/policies/{key}` `{value}` | policy đang là số thì giá trị mới phải là số ≥ 0 |
| GET / PUT | `/api/bom/unit-types`, `/api/bom/unit-types/{id}/price` `{monthlyPrice}` | giá theo loại khoang (> 0). Hợp đồng đã ký giữ giá chốt lúc ký |
| PATCH | `/api/bom/storage-units/{id}` | giá riêng từng khoang |
| GET | `/api/bom/reports?facilityId=&from=&to=` | báo cáo toàn chuỗi (bỏ `facilityId`) hoặc một cơ sở |
| GET | `/api/bom/invoices?facilityId=&status=&type=` | hoá đơn toàn chuỗi |

Audit:
- `FACILITY_CREATED`, `FACILITY_UPDATED` (`Facility`);
- `STORAGE_UNIT_CREATED`, `STORAGE_UNIT_UPDATED`, `STORAGE_UNIT_MAINTENANCE`, `STORAGE_UNIT_AVAILABLE` (`StorageUnit`);
- `POLICY_UPDATED` (`Policy`), `UNIT_TYPE_UPDATED` (`UnitType`).

## Admin (`/api/admin/**`, role `ADMIN`)

| Method | Path | Hành vi |
|---|---|---|
| GET | `/api/admin/accounts?role=&status=&q=&page=&size=` | tài khoản + cơ sở được gán / phụ trách + lần đăng nhập thành công gần nhất |
| POST | `/api/admin/accounts` `{email, password, name?, phone?, role}` | tạo tài khoản đã xác minh email; mật khẩu ≥ 8; email trùng → 409 |
| PATCH | `/api/admin/accounts/{id}` `{role?, status?, name?, phone?}` | đổi role / khoá (`BANNED`) / mở; không tự đổi role hoặc khoá chính mình (409); đổi khỏi FS thì gỡ gán cơ sở |
| PUT | `/api/admin/accounts/{id}/facilities` `{facilityIds}` | gán FS vào đúng các cơ sở này (thay toàn bộ); chỉ tài khoản FS |
| GET | `/api/admin/login-events?email=` | lịch sử đăng nhập (thành công / thất bại + lý do + IP), mới nhất trước |
| GET | `/api/admin/audit-logs?action=&entityType=&entityId=&actor=` | nhật ký thao tác (lọc theo email actor) |
| GET | `/api/admin/permissions` | ma trận permission × role (scope `GLOBAL`/`FACILITY`) |
| PUT | `/api/admin/roles/{role}/permissions` `{code: scope}` | thay toàn bộ quyền của role; `ADMIN` không chỉnh được (409); code / scope sai → 400 |

- **Lịch sử đăng nhập:** bảng `login_events` (V21). `AuthService.login` ghi mọi lần thử trong transaction riêng,
  `reason` = tên lỗi (`InvalidCredentials`, `AccountNotAllowed`, `EmailNotVerified`, `TooManyLoginAttempts`…).
- **Audit:** `ACCOUNT_CREATED`, `ACCOUNT_UPDATED` (`Account`), `ROLE_PERMISSIONS_UPDATED` (`Role`).

## Test

`backoffice/SupportBackofficeIntegrationTest.java` (Postgres), `backoffice/controller/BackofficeControllerTest.java`.
