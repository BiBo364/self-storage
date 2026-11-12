---
purpose: Back-office as-built — FM quản lý khoang / xem hoá đơn, hợp đồng, báo cáo; BOM quản lý cơ sở, policy, giá; Admin tài khoản, phân quyền, nhật ký.
---
# Back-office (Flow 4, Flow 5, quản trị hệ thống)

Spec Flow 4/5 chỉ có tiêu đề; trang này là hành vi as-built và là contract của các route
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
| Nhân sự: ca làm việc, nghỉ phép, hồ sơ nhân viên, báo cáo nhân sự | [hr.md](hr.md) (module `staffing`) |

Mọi route cần bearer token + `ACCOUNT_ACTIVE` và đúng role theo prefix (`/api/fm/**` → `FM`, `/api/bom/**` →
`BOM`, `/api/admin/**` → `ADMIN`; sai role → 403). Lỗi nghiệp vụ của `BackofficeController`
(`BackofficeExceptionHandler`): dữ liệu sai **và** không tìm thấy khoang / cơ sở / policy / loại khoang → 400
(`IllegalArgumentException`), xung đột trạng thái → 409 (`IllegalStateException`). Route FM kiểm thêm
`facility.access` theo cơ sở: thiếu quyền / không được gán cơ sở → 403, `facilityId` không tồn tại → 404.

## FM (theo cơ sở, `facility.access` + facility scope)

| Method | Path | Hành vi |
|---|---|---|
| GET | `/api/fm/contracts?facilityId=&status=` | hợp đồng của cơ sở + tổng hoá đơn `Unpaid` |
| GET | `/api/fm/reports?facilityId=&from=&to=` | KPI (gồm cọc đang giữ), doanh thu theo tháng × loại (bên dưới), số khoang theo trạng thái. Mặc định 6 tháng gần nhất |
| GET | `/api/fm/reports/staff?facilityId=&from=&to=` | báo cáo nhân sự của cơ sở — [hr.md › Báo cáo nhân sự](hr.md#báo-cáo-nhân-sự) |
| POST | `/api/fm/storage-units` `{facilityId, code, unitTypeId, monthlyPrice?, floorNo?, aisleSide?, aislePosition?}` | thêm khoang `Available`; mã trùng trong cơ sở → 400; cơ sở tự "cung cấp" loại khoang đó (`facility_unit_type`). Vị trí: đủ ba trường hoặc bỏ cả ba — xem [Sơ đồ cơ sở](#sơ-đồ-cơ-sở) |
| PUT | `/api/fm/storage-units/{id}/location` `{floorNo, aisleSide, aislePosition}` | đặt / đổi vị trí khoang trên sơ đồ; cả ba `null` = gỡ khỏi sơ đồ. Mọi trạng thái khoang. Audit `STORAGE_UNIT_UPDATED` (vị trí cũ / mới) |
| PATCH | `/api/fm/storage-units/{id}` `{unitTypeId?, monthlyPrice?}` | đổi loại / giá riêng (`monthlyPrice` null = giá loại khoang; có giá thì là số nguyên VND > 0, cả lúc tạo khoang) |
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
| Cọc bị giữ lại: hoá đơn `Deposit` / `DepositSupplement` `Paid` của đơn `Canceled` | `ForfeitedDeposit` | `amount` − khoản hoàn ghi trước lúc huỷ | `rental_orders.canceled_at` (V28) |
| Hoàn cọc sau lúc huỷ (huỷ do lỗi cơ sở) | `ForfeitedDeposit` | `−amount` | `paid_at` của giao dịch hoàn |

- `count` = số hoá đơn được ghi nhận; dòng hoàn tiền không cộng `count`. `totalRevenue` = tổng `amount` mọi dòng.
- Đơn huỷ trước V28 lấy `canceled_at` từ audit `RENTAL_ORDER_CANCELED`; đơn không có audit thì `canceled_at` `NULL`
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

Danh sách hoá đơn của FM là `GET /api/fm/invoices` ([routes/booking/fm-invoice-list.md](../routes/booking/fm-invoice-list.md),
quyền `invoice.read`). Tab "Hoàn tiền" của màn "Hoá đơn cơ sở" đọc hàng đợi `GET /api/fm/invoices/refunds` và ghi nhận
qua `GET` / `POST /api/fm/invoices/{invoiceId}/refunds` (quyền `payment_transaction.refund`, không phải `facility.access` —
[routes/booking/fm-invoice-refunds.md](../routes/booking/fm-invoice-refunds.md)).

## BOM (`/api/bom/**`, role `BOM`)

| Method | Path | Hành vi |
|---|---|---|
| GET / POST | `/api/bom/facilities` | danh sách (kèm FM, số khoang, số khoang `Rented`, `staffCount` = số FS `ACTIVE` đang gán, `latitude`, `longitude`, `floorCount`) / tạo cơ sở; mã viết hoa, chỉ `A-Z 0-9 -`, tối đa 20 ký tự (mã nằm trong mã hoá đơn, cột 50 ký tự), trùng → 400. Tạo kèm ghim bản đồ, số tầng và sơ đồ khoang ban đầu — [Sơ đồ cơ sở](#sơ-đồ-cơ-sở) |
| PATCH | `/api/bom/facilities/{id}` | tên, địa chỉ, giờ mở cửa, `status` (`Active`/`Inactive`), `latitude` + `longitude` (đổi khi gửi đủ cả hai), `floorCount`; trường `null` giữ nguyên |
| GET | `/api/bom/facilities/{id}/units` | mọi khoang của cơ sở kèm trạng thái + vị trí (cùng dạng `GET /api/fm/storage-units`) — sơ đồ có màu trạng thái cho BOM; không có cơ sở → 400 |
| PUT | `/api/bom/storage-units/{id}/location` | như route FM |
| PUT | `/api/bom/facilities/{id}/manager` `{fmAccountId}` | gán FM (1–1: FM đang ở cơ sở khác bị gỡ), `null` = bỏ FM; tài khoản không phải FM → 400 |
| PUT | `/api/bom/facilities/{id}/access-options` | như route FM (cùng kiểm `facility.access`; BOM có quyền mọi cơ sở) |
| GET | `/api/bom/managers` | tài khoản FM đang hoạt động |
| GET | `/api/bom/staff` | tài khoản FS (mọi trạng thái) kèm `facilityIds` đang gán |
| PUT | `/api/bom/staff/{id}/facilities` `{facilityIds}` | như `PUT /api/admin/accounts/{id}/facilities` — BOM điều phối FS giữa các cơ sở (xem [Gán FS vào cơ sở](#gán-fs-vào-cơ-sở)) |
| GET / PUT | `/api/bom/policies`, `/api/bom/policies/{key}` `{value}` | policy đang là số thì giá trị mới phải là số ≥ 0; policy đang là số nguyên (ngày, tháng, số lần, phí) thì giá trị mới phải là số nguyên ≥ 0 trong miền `int` — `2.5` / `1e2` → 400; policy đang là `true`/`false` (vd `staff.shift_required`) chỉ nhận `true` hoặc `false` |
| GET / PUT | `/api/bom/unit-types`, `/api/bom/unit-types/{id}/price` `{monthlyPrice}` | giá theo loại khoang (số nguyên VND > 0 — cổng payOS chỉ thu số nguyên). Hợp đồng đã ký giữ giá chốt lúc ký |
| PATCH | `/api/bom/storage-units/{id}` | giá riêng từng khoang |
| GET | `/api/bom/reports?facilityId=&from=&to=` | báo cáo toàn chuỗi (bỏ `facilityId`) hoặc một cơ sở |
| GET | `/api/bom/reports/staff?facilityId=&from=&to=` | báo cáo nhân sự toàn chuỗi / một cơ sở ([hr.md](hr.md#báo-cáo-nhân-sự)) |
| GET / PUT | `/api/bom/staff/{id}/profile` | hồ sơ nhân viên FS / FM gồm số giấy tờ ([hr.md › Hồ sơ nhân viên](hr.md#hồ-sơ-nhân-viên)) |
| GET | `/api/bom/invoices?facilityId=&status=&type=` | hoá đơn toàn chuỗi |

Audit:
- `FACILITY_CREATED`, `FACILITY_UPDATED` (`Facility`);
- `STORAGE_UNIT_CREATED`, `STORAGE_UNIT_UPDATED`, `STORAGE_UNIT_MAINTENANCE`, `STORAGE_UNIT_AVAILABLE` (`StorageUnit`);
- `POLICY_UPDATED` (`Policy`), `UNIT_TYPE_UPDATED` (`UnitType`).

## Sơ đồ cơ sở

Mỗi cơ sở có ghim bản đồ (`latitude` + `longitude`, tuỳ chọn) và số tầng (`floor_count` 1–20, mặc định 1). Mỗi khoang
có thể có vị trí: **tầng** (`floor_no`) + **bên lối đi** nhìn từ cửa vào (`aisle_side` = `Left` / `Right`) + **thứ tự
tính từ cửa vào** (`aisle_position`, 1 = sát cửa). Ba trường cùng có hoặc cùng `null` (khoang chưa xếp lên sơ đồ).

- **Tạo cơ sở** (`POST /api/bom/facilities`): body thêm `latitude`, `longitude`, `floorCount`, `units[]`
  (`code`, `unitTypeId`, `floorNo`, `aisleSide`, `aislePosition` — vị trí bắt buộc với khoang trên sơ đồ). Một
  transaction: sơ đồ sai thì không tạo gì → 400 — mã lặp (không phân biệt hoa thường), loại khoang không tồn tại, thiếu
  vị trí, tầng vượt `floorCount`, hai khoang một vị trí; toạ độ chỉ có một nửa / ngoài ±90, ±180; `floorCount` ngoài
  1–20. Khoang tạo ra `Available`, giá theo loại khoang; mỗi khoang một audit `STORAGE_UNIT_CREATED`; `FACILITY_CREATED`
  ghi thêm `floorCount`, `unitCount`, toạ độ.
- **Sửa cơ sở**: giảm `floorCount` xuống dưới tầng cao nhất đang có khoang → 409 (`"Tầng N đang có khoang — …"`).
  Audit `FACILITY_UPDATED` ghi tên, trạng thái, số tầng, toạ độ cũ / mới.
- **Vị trí khoang** (FM theo cơ sở, BOM mọi cơ sở): tạo khoang kèm vị trí hoặc `PUT …/location`. Tầng vượt số tầng /
  thiếu một phần → 400; vị trí đã có khoang khác → 409 kèm mã khoang đang chiếm (`"Tầng 1 · bên phải · vị trí 1 đã có
  khoang A-03"`). Kiểm trước bằng `select`, unique index `uq_storage_units_slot` chặn khi hai request đua.
- Đọc: FM / BOM qua danh sách khoang (có trạng thái); khách và FS qua `GET /api/facilities/{id}/layout` (không trạng
  thái — [routes/facility/public-list.md](../routes/facility/public-list.md)). FE hiện vị trí khoang ở trang đề xuất
  khoang (khi đề xuất đang chờ), thẻ khoang đang thuê, chi tiết hợp đồng và checklist bàn giao của FS.
- V34 seed toạ độ cho Q7 / TD, Q7 2 tầng và xếp 8 khoang seed của Q7 (A-01…A-04 tầng 1, B-01…C-01 tầng 2) — chỉ khi
  cơ sở chưa có toạ độ / khoang chưa có vị trí.

## Gán FS vào cơ sở

BOM (`PUT /api/bom/staff/{id}/facilities`) và ADMIN (`PUT /api/admin/accounts/{id}/facilities`) dùng chung
`AccountAdminService.setStaffFacilities`. FS chuyển cơ sở giữ nguyên tài khoản — không tạo tài khoản mới; lịch sử
audit và việc đã làm vẫn gắn với một account id.

- Body là danh sách cơ sở **đích** (thay toàn bộ, trùng id bị gộp). Cơ sở giữ nguyên không bị xoá / chèn lại nên
  `assigned_at` không đổi; chỉ cơ sở bị bỏ mới xoá dòng, cơ sở mới mới chèn dòng.
- **Việc còn mở chặn gỡ:** FS bị gỡ khỏi cơ sở thì mất quyền ở đó (mọi thao tác FS kiểm scope cơ sở theo từng
  request), nên việc đang giao sẽ bị treo. Còn một trong các việc sau tại cơ sở bị bỏ → 409, không đổi gì:

  | Việc | Điều kiện | Cách gỡ chặn |
  |---|---|---|
  | lịch check-in | `appointments` `CHECKIN` `Pending`, `staff_id` = FS | FM đổi FS: `PUT /api/fm/appointments/{id}/staff` |
  | lịch trả kho | `appointments` `RETURN` `Pending`, `staff_id` = FS (yêu cầu trả kho `Assigned`) | FM phân công lại yêu cầu trả kho |
  | phiên bàn giao | `handover_records` `IN_PROGRESS`, khách đã đến, lịch có `staff_id` = FS | FS hoàn tất / đóng phiên — không phân công lại được |
  | yêu cầu hỗ trợ | `support_requests` `Assigned`, `assigned_staff_id` = FS | FM giao lại yêu cầu |

  Message liệt kê tối đa 5 việc (`… và N việc khác`), mỗi việc kèm cách gỡ chặn của cột cuối trong ngoặc. Trả kho sau khi khách đến (checkout) không chặn: mọi FS của
  cơ sở có `unit_access_key.issue` đều làm tiếp được.
- Mỗi module giao việc cho FS khai báo việc còn mở qua interface `identity.application.StaffOpenWork`
  (booking, handover, support) — identity không phụ thuộc ngược các module đó. Module nhân sự dùng các biến thể theo
  ngày của cùng interface để chặn duyệt nghỉ phép, chặn xoá / thu hẹp ca và đếm việc trên màn phân công
  ([hr.md](hr.md)).
- **Tranh chấp với FM đang phân công:** `FacilityStaffQuery.isActiveStaffOf` khoá `FOR SHARE` dòng gán tới hết
  transaction phân công. Lệnh gỡ xoá dòng gán **trước** rồi mới kiểm việc, nên chờ FM commit và thấy việc vừa giao
  (→ 409, rollback). FM phân công sau khi dòng đã xoá → 400 "không phải FS của cơ sở".
- Audit một bản ghi `ACCOUNT_UPDATED` (`Account`): `old_value.facilityIds` / `new_value.facilityIds`.
- Đổi role khỏi `FS` (`PATCH /api/admin/accounts/{id}`) vẫn xoá mọi dòng gán mà **không** kiểm việc còn mở.
- Đổi role khỏi `FM` gỡ tài khoản khỏi vị trí quản lý mọi cơ sở (`facilities.fm_account_id = null`) — BOM gán FM
  mới. Gán FM cho cơ sở chỉ nhận tài khoản FM `ACTIVE` (FM bị khoá → 400).

```bash
TOKEN='<token của bom1@lemar.vn>'

# 409 — fs1 (id 5) còn lịch check-in #1 ở Q7
curl -s -X PUT http://localhost:8080/api/bom/staff/5/facilities -H "Authorization: Bearer $TOKEN"   -H 'Content-Type: application/json' -d '{"facilityIds":[2]}'
# {"message":"FS còn việc chưa đóng tại cơ sở Q7: lịch check-in #1 (FM đổi FS). Xử lý các việc này trước khi gỡ FS khỏi cơ sở","timestamp":"2026-11-08T18:44:03.649603100Z"}

# 200 — sau khi FM đổi lịch #1 sang FS khác (PUT /api/fm/appointments/1/staff)
# {"message":"Đã gán cơ sở","data":{"id":5,"email":"fs1@lemar.vn","name":"FS Quận 7","phone":null,"role":"FS","status":"ACTIVE","verified":true,"createdAt":"2026-11-08T18:41:03.175270Z","facilityIds":[2],"lastLoginAt":null}}
```

Lần 409 không ghi audit; lần 200 ghi `ACCOUNT_UPDATED | Account | 5 | {"facilityIds": [1]} → {"facilityIds": [2]}`
(actor = BOM).

## Admin (`/api/admin/**`, role `ADMIN`)

| Method | Path | Hành vi |
|---|---|---|
| GET | `/api/admin/accounts?role=&status=&q=&page=&size=` | tài khoản + cơ sở được gán / phụ trách + lần đăng nhập thành công gần nhất |
| POST | `/api/admin/accounts` `{email, password, name?, phone?, role, facilityIds?}` | tạo tài khoản đã xác minh email; mật khẩu ≥ 8; email trùng → 409. `facilityIds` gán cơ sở ngay lúc tạo — chỉ cho role `FS` (role khác kèm cơ sở → 400), cơ sở không tồn tại → 400; ghi vào audit `ACCOUNT_CREATED` (`new_value.facilityIds`). FS mới chưa có ca: khi `staff.shift_required` bật, FM xếp ca trước khi phân công |
| PATCH | `/api/admin/accounts/{id}` `{role?, status?, name?, phone?}` | đổi role / khoá (`BANNED`) / mở; không tự đổi role hoặc khoá chính mình (409); đổi khỏi FS thì gỡ gán cơ sở |
| GET / PUT | `/api/admin/accounts/{id}/profile` | hồ sơ nhân viên của tài khoản FS / FM gồm số giấy tờ ([hr.md › Hồ sơ nhân viên](hr.md#hồ-sơ-nhân-viên)) |
| PUT | `/api/admin/accounts/{id}/facilities` `{facilityIds}` | gán FS vào đúng các cơ sở này (thay toàn bộ); không phải tài khoản FS → 409; cơ sở không tồn tại → 400; gỡ khỏi cơ sở mà FS còn việc mở → 409 ([Gán FS vào cơ sở](#gán-fs-vào-cơ-sở)) |
| GET | `/api/admin/login-events?email=` | lịch sử đăng nhập (thành công / thất bại + lý do + IP), mới nhất trước |
| GET | `/api/admin/audit-logs?action=&entityType=&entityId=&actor=` | nhật ký thao tác (lọc theo email actor) |
| GET | `/api/admin/permissions` | ma trận permission × role (scope `GLOBAL`/`FACILITY`) |
| PUT | `/api/admin/roles/{role}/permissions` `{code: scope}` | thay toàn bộ quyền của role; `ADMIN` không chỉnh được (409); code / scope sai → 400 |

- **Lịch sử đăng nhập:** bảng `login_events` (V26). `AuthService.login` ghi mọi lần thử trong transaction riêng,
  `reason` = tên lỗi (`InvalidCredentials`, `AccountNotAllowed`, `EmailNotVerified`, `TooManyLoginAttempts`…).
- **Audit:** `ACCOUNT_CREATED`, `ACCOUNT_UPDATED` (`Account`), `ROLE_PERMISSIONS_UPDATED` (`Role`).

## Test

`backoffice/SupportBackofficeIntegrationTest.java` (Postgres), `backoffice/controller/BackofficeControllerTest.java`.
