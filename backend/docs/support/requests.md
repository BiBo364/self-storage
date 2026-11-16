---
purpose: Yêu cầu hỗ trợ / sự cố (Flow 7) as-built — khách gửi, FM giao FS, FS xử lý, thay chìa, phí dịch vụ, chuyển khoang khi khoang hư.
---
# Yêu cầu hỗ trợ / sự cố

Spec Flow 7 chỉ có tiêu đề; trang này là hành vi as-built và là contract của các route hỗ trợ (không có file riêng
trong `routes/`; field chi tiết ở Swagger trên `SupportController`). Code:
`support/application/SupportService.java`, `support/application/UnitTransferService.java`,
`support/controller/SupportController.java`, bảng `support_requests` (V26), `unit_transfers` (V37).

## Trạng thái

`Open` → `Assigned` → `Resolved`. Khách huỷ được khi còn `Open` / `Assigned` (`Canceled`). Khoang hư không sửa ngay
được: `Assigned` → `Transferring` (đang chuyển khoang) → `Resolved` khi chuyển xong, hoặc về `Assigned` khi FM huỷ
chuyển ([Chuyển khoang](#chuyển-khoang-khoang-hư-giữa-kỳ-thuê)). `Transferring` vẫn tính là đang mở (hàng chờ FM / FS,
việc còn mở của FS) nhưng khách không huỷ được và không ghi "đã xử lý" thường được.

| Bước | Ai | Điều kiện | Kết quả |
|---|---|---|---|
| Gửi | Khách | gắn `contractId` (hợp đồng của mình) hoặc chọn `facilityId`; `LOST_KEY` bắt buộc hợp đồng; `UNIT_ISSUE` (khoang có vấn đề) bắt buộc hợp đồng `Active` và ≥ 1 ảnh bằng chứng là file chính khách đã upload (`MediaUrlLookup.notUploadedBy`); tiêu đề ≤ 255, mô tả ≤ 4000, ảnh ≤ 10 (ảnh là upload `Pending` của khách thì attach phía server) | `Open`; FM của cơ sở nhận `SUPPORT_REQUEST_CREATED`; audit `SUPPORT_REQUEST_CREATED` |
| Giao | FM (`appointment.assign` + facility scope), FS đang hoạt động của cơ sở, không nghỉ phép hôm nay và (policy `staff.shift_required` bật) có ca tại cơ sở hôm nay ([backoffice/hr.md](../backoffice/hr.md#luật-phân-công-theo-ca--nghỉ-phép)) | yêu cầu còn mở (giao lại được) | `Assigned`; FS và khách nhận `SUPPORT_ASSIGNED`; audit `SUPPORT_REQUEST_ASSIGNED` |
| Xử lý | FS được giao (`unit_access_key.issue`) hoặc FM của cơ sở | `Open` / `Assigned`; kết quả ≤ 2000 ký tự | `Resolved`; khách nhận `SUPPORT_RESOLVED`; audit `SUPPORT_REQUEST_RESOLVED` |
| Huỷ | Khách | `Open` / `Assigned` | `Canceled`; audit `SUPPORT_REQUEST_CANCELED` |

Loại: `LOST_KEY`, `ACCESS_CODE`, `UNIT_ISSUE`, `BILLING`, `OTHER`.

## Xử lý kèm thao tác

Áp dụng khi yêu cầu gắn hợp đồng `Active`. Thứ tự khoá: đơn → hợp đồng → yêu cầu.

- **Thay chìa:** `newKeyQuantity` nhận 0–10; `0` hoặc bỏ trống = không thay chìa, `1–10` → chìa đang `Active` chuyển
  `Lost`, cấp bộ chìa mới
  (`RentalContractService.replacePhysicalKey`). Audit `ACCESS_KEY_REVOKED` + `ACCESS_KEY_ISSUED`.
- **Thu phí:** phí > 0 → hoá đơn `Service`:
  - phí = `feeAmount` nếu có nhập (kể cả `0` = không thu);
  - bỏ trống `feeAmount` + yêu cầu `LOST_KEY` + có thay chìa → phí mặc định = `fee.lost_key` (mặc định 100.000 đ / chìa) × `quantity` của bộ chìa
    `Active` bị thay (đọc trước khi bộ cũ chuyển `Lost`; không có bộ chìa đang dùng → 0, không thu);
  - loại yêu cầu khác không có phí tự động;
  - mã `INV-SVC-{facility}-…`, gắn hợp đồng, hạn 7 ngày;
  - khách thanh toán qua `/api/invoices/{id}/pay`;
  - khi trả kho mà hoá đơn chưa trả thì được gộp vào quyết toán.
- **Mã khóa số:** khách tự cấp lại qua `POST /api/customer/contracts/{id}/access-code`; FS chỉ ghi kết quả.

## Chuyển khoang (khoang hư giữa kỳ thuê)

Khách báo `UNIT_ISSUE` kèm ảnh → FM xem ảnh, giao FS. FS sửa được ngay thì xử lý như thường (`Resolved`); không sửa
ngay được thì chuyển khách sang khoang khác. **Hợp đồng giữ nguyên** — giá thuê, cọc, kỳ hạn không đổi, không thu phí
(lỗi do cơ sở); chỉ `rental_contracts.unit_id` đổi.

| Bước | Ai | Điều kiện | Kết quả |
|---|---|---|---|
| Đề xuất | FS được giao (`unit_access_key.issue`) | yêu cầu `Assigned`, gắn hợp đồng `Active`; lý do ≤ 1000; hợp đồng không có yêu cầu trả kho `Pending`/`Assigned` và không có lần chuyển khác đang mở | yêu cầu `Transferring`; `unit_transfers` `Requested` (`from_unit_id` = khoang hiện tại); FM nhận `UNIT_TRANSFER_REQUESTED`; audit `UNIT_TRANSFER_REQUESTED` |
| Điều khoang | FM (`appointment.assign`) | khoang `Available` cùng cơ sở. **Ưu tiên cùng loại (cùng giá):** còn khoang trống cùng loại thì chỉ được chọn cùng loại; hết mới cho loại có diện tích ≥ khoang cũ (vẫn giữ giá cũ) | khoang mới `Reserved` (giữ theo đơn của hợp đồng — `reserved_order_id`); điều lại thì nhả khoang trước (`Available`); `Allocated`; FS và khách nhận `UNIT_TRANSFER_ALLOCATED`; audit `STORAGE_UNIT_RESERVED`, `UNIT_TRANSFER_ALLOCATED` |
| Hoàn tất | FS được giao (hoặc FM) | `Allocated`, hợp đồng `Active`; `keyQuantity` 1–10 bắt buộc khi hợp đồng đang có chìa cơ `Active` | khoang mới `Reserved → Rented`; khoang cũ `Rented → Maintenance` (bảo trì thủ công, không có mốc tự mở — FM mở lại khi sửa xong); `RentalContractService.moveToUnit`: chìa / mã đang `Active` → `Revoked` (lý do "Chuyển khoang", không tính mất chìa), cấp lại trên khoang mới đúng loại đang dùng (chìa cơ `keyQuantity`; mã khóa số mới — trả rõ một lần trong response `accessCode`; hợp đồng đang tạm khoá do quá hạn thì không có mã để cấp lại); `Completed`; yêu cầu `Resolved` ("Đã chuyển sang khoang …"); khách nhận `UNIT_TRANSFER_COMPLETED`; audit `STORAGE_UNIT_RENTED`, `STORAGE_UNIT_MAINTENANCE`, `ACCESS_KEY_REVOKED`, `ACCESS_KEY_ISSUED`, `CONTRACT_UNIT_CHANGED`, `UNIT_TRANSFER_COMPLETED`, `SUPPORT_REQUEST_RESOLVED` |
| Huỷ | FM | `Requested` / `Allocated`; lý do ≤ 500 | nhả khoang đang giữ; `Canceled`; yêu cầu về `Assigned` (FS xử lý tiếp như thường); FS nhận `UNIT_TRANSFER_CANCELED`; audit `UNIT_TRANSFER_CANCELED` |

- Thứ tự khoá như xử lý thường: đơn → hợp đồng → yêu cầu → `unit_transfers`; khoang khoá trong `StorageUnitService`.
  Unique index `uq_unit_transfers_open_per_contract`: mỗi hợp đồng tối đa một lần chuyển đang mở.
- Bản hợp đồng đã ký (`document_html`) không đổi, vẫn ghi khoang cũ. `GET /api/customer/rentals/{id}` trả
  `unitMoves` (khoang cũ → mới, thời điểm) để trang hợp đồng hiện "Đã chuyển từ khoang … sang …".
- Response yêu cầu hỗ trợ có `transfer` = lần chuyển gần nhất (`status`, khoang cũ / mới, lý do, lý do huỷ,
  `keyQuantity` = số chìa đã giao ở khoang mới khi `Completed`), kèm hai trường đọc từ bộ chìa của hợp đồng lúc trả
  response:
  - `currentKeyQuantity` — tổng `quantity` các bộ chìa cơ `Active` (khách đang giữ); `null` khi không dùng chìa cơ.
    Màn FS hiện "Khách đang giữ N chìa cơ — thu lại N chìa cũ, giao N chìa của khoang mới" và điền sẵn N vào ô số
    chìa (FS sửa được; server vẫn bắt buộc gửi `keyQuantity`). Không dùng chìa cơ thì màn FS ẩn ô số chìa.
  - `hasAccessCode` — hợp đồng đang có mã khoá số `Active` (mã mới cấp khi hoàn tất).

## Route

Bearer token + `ACCOUNT_ACTIVE`; prefix quyết role (`/api/customer/**` → `CUSTOMER`, `/api/fm/**` → `FM`,
`/api/staff/**` → `FS`, sai role → 403). Lỗi nghiệp vụ trả `{message, timestamp}` qua `TenancyExceptionHandler`.

| Method | Path | Ai gọi | Lỗi chính |
|---|---|---|---|
| GET | `/api/customer/support-requests` | khách — yêu cầu của mình, mới nhất trước | — |
| POST | `/api/customer/support-requests` `{contractId?, facilityId?, category, title, description, photos?}` | khách | 400 thiếu/quá dài `title` (255) / `description` (4000), `category` sai, > 10 ảnh, không chọn hợp đồng lẫn cơ sở hợp lệ, `LOST_KEY` không gắn hợp đồng, `UNIT_ISSUE` thiếu hợp đồng `Active` / không có ảnh / ảnh không phải khách upload; 404 hợp đồng không phải của khách |
| POST | `/api/customer/support-requests/{id}/cancel` | khách chủ yêu cầu | 404 (không có / của khách khác); 409 "Yêu cầu hỗ trợ đã đóng", đang chuyển khoang |
| GET | `/api/fm/support-requests?facilityId=&includeClosed=` | FM có `rental_order.read` tại cơ sở; mặc định chỉ `Open`/`Assigned`/`Transferring` | 403 thiếu quyền / ngoài cơ sở |
| POST | `/api/fm/support-requests/{id}/assign` `{staffId}` | FM có `appointment.assign` tại cơ sở của yêu cầu | 400 FS không thuộc cơ sở / không hoạt động; 403; 404; 409 đã đóng, FS nghỉ phép hôm nay, FS không có ca tại cơ sở hôm nay |
| POST | `/api/fm/support-requests/{id}/resolve` `{resolution, newKeyQuantity?, feeAmount?}` | FM có `appointment.assign` tại cơ sở | như route FS bên dưới |
| GET | `/api/staff/support-requests` | FS — yêu cầu `Assigned` / `Transferring` giao cho mình | — |
| POST | `/api/staff/support-requests/{id}/resolve` `{resolution, newKeyQuantity?, feeAmount?}` | FS được giao, có `unit_access_key.issue` tại cơ sở (người khác phải có `appointment.assign`) | 400 thiếu/quá dài `resolution` (2000), `newKeyQuantity` ngoài 0–10, `feeAmount` âm hoặc có phần lẻ (VND nguyên); 403; 404; 409 đã đóng, thay chìa / thu phí khi yêu cầu không gắn hợp đồng hoặc hợp đồng không `Active`, yêu cầu đang chuyển khoang |
| POST | `/api/staff/support-requests/{id}/transfer` `{reason}` | FS được giao, `unit_access_key.issue` | 400 thiếu lý do; 409 không phải FS được giao, không `Assigned`, không gắn hợp đồng / hợp đồng không `Active`, đang có yêu cầu trả kho / lần chuyển khác |
| GET | `/api/fm/support-requests/{id}/transfer/candidates` | FM có `appointment.assign` tại cơ sở | 409 không có đề xuất đang mở. Mỗi khoang: `sameType`, `allowed` |
| POST | `/api/fm/support-requests/{id}/transfer/allocate` `{unitId}` | FM có `appointment.assign` | 400 khoang không thuộc cơ sở / không trống; 409 còn khoang cùng loại mà chọn khác loại, khoang nhỏ hơn, khoang vừa bị giữ, yêu cầu không `Transferring`, hợp đồng hết hiệu lực |
| POST | `/api/fm/support-requests/{id}/transfer/cancel` `{reason}` | FM có `appointment.assign` | 400 thiếu lý do; 409 không có đề xuất đang mở |
| POST | `/api/staff/support-requests/{id}/transfer/complete` `{keyQuantity?}` | FS được giao (`unit_access_key.issue`) hoặc FM (`appointment.assign`) | 400 thiếu `keyQuantity` khi đang dùng chìa cơ / ngoài 1–10; 409 FM chưa điều khoang, khoang mới không còn giữ, hợp đồng hết hiệu lực. Trả `{request, accessCode}` |

## Test

`backoffice/SupportBackofficeIntegrationTest.java` (gồm bằng chứng ảnh khoang hư, chuyển khoang cùng loại giữ giá,
điều lại / huỷ và chỉ cho loại rộng hơn khi hết cùng loại), `backoffice/controller/BackofficeControllerTest.java`.
