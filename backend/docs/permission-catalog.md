---
purpose: Catalog permission + mapping role mặc định — quy ước đặt code, cách chọn model guard.
---
# Permission Catalog

Catalog permission cho model RBAC data-driven (Flow 5.0) — danh sách code mà source đang gọi qua `Access`. Các
flow sau bàn giao (trả kho, quá hạn, hỗ trợ, back-office) dùng lại code có sẵn thay vì thêm code mới (xem
[Code dùng lại](#code-dùng-lại)). Được seed ở
`backend/src/main/resources/db/migration/V3__rbac_permissions_seed.sql` (V3+ không mirror trong
`backend/sql/` — chỉ baseline V1/V2 có mirror).

Mô hình guard: [authorization.md › Models](authorization.md#models).

## Quy ước đặt code

- `Permission.code = <resource>.<action>`.
- `resource` = tên bảng snake_case, **số ít**: `rental_request`, `rental_order`, `proposal_feedback`,
  `rental_contract`, `handover_record`, `unit_access_key`, `payment_transaction`, `appointment`,
  `notification`, `facility`.
- `action` = động từ nghiệp vụ: `read`, `approve`, `reject`, `assign`, `create_other`, `refund`…
- Mọi code dùng trong source (`can(...)`) phải có trong catalog **trước** khi wire.
- Thêm code mới → cập nhật catalog này + seed `permissions` + `role_permissions`; không hard-code
  mapping trong source.

## Cách đọc bảng

- `G`/`F` là `scope` lưu ở `role_permissions.scope` — `G` = `GLOBAL`, `F` = `FACILITY`.
- `G` — call site gọi `can(permission)`.
- `F` — call site gọi `can(permission, facilityId)` với `facilityId` bắt buộc. ADMIN/BOM luôn bỏ qua scope; FM/FS kiểm `fm_account_id` / `account_facility_assignments`.
- Gọi sai dạng (1 tham số cho mapping `F`, 2 tham số cho mapping `G`) là **lỗi lập trình** — `Access` ném `IllegalStateException`, không âm thầm bỏ scope.
- `own` — ownership, **không seed** vào `role_permissions`; service kiểm chủ tài nguyên.
- Ô trống — role không có quyền (403).
- Mỗi ô `G`/`F` = một dòng `role_permissions` khi seed.

## Catalog

Sắp theo code alphabet.

| Permission | Ý nghĩa | ADMIN | BOM | FM | FS | CUSTOMER | Căn cứ |
|---|---|:-:|:-:|:-:|:-:|:-:|---|
| `appointment.assign` | Phân công FS | | | F | | | draft.md §1.5 |
| `appointment.checkin` | Xác nhận khách đã đến (Done) | | | | F | | §2.1 |
| `appointment.create` | Chọn lịch check-in | | | | | own | §1.5 |
| `appointment.read` | Xem lịch theo cơ sở / được gán | G | G | F | F | | §2.1 |
| `facility.access` | Truy cập dữ liệu cơ sở — guard back-office FM | F | F | F | F | | `GET /api/fm/storage-units`, back-office FM, `GET /api/facility-access/ping/{facilityId}` |
| `handover_record.inspect` | Ghi/xác nhận hiện trạng khoang | | | | F | own | §2.2 — `HandoverWorkflowService.recordInspection` (`PUT /api/staff/handovers/{id}/inspection`), `correctInspection` (`POST /api/staff/handovers/{id}/inspection-correction`) |
| `handover_record.reject` | Từ chối khoang tại chỗ | | | | | own | §2.5 |
| `handover_record.verify_identity` | Xác minh danh tính | | | | F | | §2.2 |
| `invoice.pay` | Thanh toán hóa đơn | | | | | own | §1.4 |
| `invoice.read` | Xem hóa đơn | | | | | own | §1.4 |
| `notification.create_other` | Tạo thông báo `OTHER` thủ công | G | G | | | | `POST /api/notifications` |
| `payment_transaction.refund` | Ghi nhận hoàn tiền thủ công | | | F | | | §2.5 — `ManualRefundService` theo cơ sở của hoá đơn (`GET`/`POST /api/fm/invoices/{invoiceId}/refunds`) |
| `proposal_feedback.override` | Override khoang đã bị từ chối (audit) | | | F | | | §1.3 |
| `proposal_feedback.repropose` | Đề xuất lại khoang | | | F | | | §1.3 |
| `proposal_feedback.respond` | Đồng ý/từ chối đề xuất | | | | | own | §1.3 |
| `rental_contract.sign` | Ký/upload hợp đồng | | | | F | | §2.3 |
| `rental_contract.start_date_override.request` | Đề nghị đổi mốc tính tiền | | | | F | | §2.3 |
| `rental_contract.start_date_override.review` | Duyệt/từ chối đổi mốc | | | F | | | §2.3 |
| `rental_order.cancel` | Hủy đơn | | | F | | own | §1.3, §1.5 |
| `rental_order.read` | Xem đơn | G | G | F | F | own | §1.2–1.5 |
| `rental_request.approve` | Duyệt + gán unit | | | F | | | §1.1 |
| `rental_request.read` | Xem yêu cầu đặt kho | G | G | F | | | §1.1 |
| `rental_request.reject` | Từ chối yêu cầu | | | F | | | §1.1 |
| `unit_access_key.issue` | Bàn giao khóa/mã | | | | F | | §2.4 |

Ghi chú:

- `facility.access` guard toàn bộ back-office FM theo cơ sở (`BackofficeService`: hoá đơn, hợp đồng, báo cáo, thêm /
  sửa / bảo trì / giữ bảo trì khoang, cấu hình truy cập — gồm cả `PUT /api/bom/facilities/{id}/access-options`), danh sách khoang
  `GET /api/fm/storage-units` (`FmFacilityReadService`) và endpoint kiểm RBAC `GET /api/facility-access/ping/{id}`.
  Mô tả seed ở V3 vẫn ghi "permission tạm cho endpoint demo"; hành vi là như trên.
- Các dòng chỉ có `own` (`invoice.*`, `appointment.create`, `handover_record.reject`,
  `proposal_feedback.respond`) không seed — guard ownership ở service.

## Code dùng lại

Các flow không có permission riêng tái dùng code sẵn có, gọi `can(code, facilityId)` — riêng `ContractDocumentService`
gọi `canGlobalOrAt` vì `rental_order.read` của ADMIN/BOM là `G`:

| Code | Call site dùng lại | Route |
|---|---|---|
| `rental_order.read` | `HandoverQueryService` (chi tiết phiên bàn giao FS), `ContractDocumentService` (bản hợp đồng), `TenancyService.facilityReturns`, `OverdueService.facilityOverdue`, `SupportService.facilityRequests` | `GET /api/staff/handovers/{id}`, `GET /api/contracts/{id}/document`, `GET /api/fm/return-requests`, `GET /api/fm/overdue-contracts`, `GET /api/fm/support-requests` |
| `appointment.assign` | `TenancyService.assign`, `SupportService.assign`, `SupportService.resolve` (người gọi không phải FS được giao) | `POST /api/fm/return-requests/{id}/assign`, `POST /api/fm/support-requests/{id}/assign`, `POST /api/fm/support-requests/{id}/resolve` |
| `unit_access_key.issue` | `CheckoutService` (xem / ghi / chốt biên bản trả kho), `SupportService.resolve` (FS được giao) | `…/api/staff/return-requests/{id}/checkout…`, `POST /api/staff/support-requests/{id}/resolve` |

## Không thuộc catalog

- **Quản trị hệ thống** (`account`, `role`, `permission`): model role-bound, guard tại
  `/api/admin/**` → `hasRole("ADMIN")` trong `SecurityConfig`. Không tạo permission — quyền quản trị
  RBAC không được là dữ liệu có thể sửa, tránh tự nâng quyền qua bảng mapping.
- **Hành vi `own`**: ownership, kiểm ở service, không seed.

## Nguồn

- Catalog theo quyết định của chủ dự án: không có nhóm `*.manage`, có `facility.access`, hoàn tiền dùng code
  `payment_transaction.refund` (không phải `payment.refund`).
- Cột "Căn cứ" trỏ mục `specs/draft.md` của từng flow; code nào khớp seed V3 là nguồn sự thật.
