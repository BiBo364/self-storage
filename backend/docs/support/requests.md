---
purpose: Yêu cầu hỗ trợ / sự cố (Flow 7) as-built — khách gửi, FM giao FS, FS xử lý, thay chìa, phí dịch vụ.
---
# Yêu cầu hỗ trợ / sự cố

Spec Flow 7 chỉ có tiêu đề; trang này là hành vi as-built và là contract của các route hỗ trợ (không có file riêng
trong `routes/`; field chi tiết ở Swagger trên `SupportController`). Code:
`support/application/SupportService.java`, `support/controller/SupportController.java`, bảng `support_requests` (V26).

## Trạng thái

`Open` → `Assigned` → `Resolved`. Khách huỷ được khi còn `Open` / `Assigned` (`Canceled`).

| Bước | Ai | Điều kiện | Kết quả |
|---|---|---|---|
| Gửi | Khách | gắn `contractId` (hợp đồng của mình) hoặc chọn `facilityId`; `LOST_KEY` bắt buộc hợp đồng; tiêu đề ≤ 255, mô tả ≤ 4000, ảnh ≤ 10 (ảnh là upload `Pending` của khách thì attach phía server) | `Open`; FM của cơ sở nhận `SUPPORT_REQUEST_CREATED`; audit `SUPPORT_REQUEST_CREATED` |
| Giao | FM (`appointment.assign` + facility scope), FS đang hoạt động của cơ sở | yêu cầu còn mở (giao lại được) | `Assigned`; FS và khách nhận `SUPPORT_ASSIGNED`; audit `SUPPORT_REQUEST_ASSIGNED` |
| Xử lý | FS được giao (`unit_access_key.issue`) hoặc FM của cơ sở | còn mở; kết quả ≤ 2000 ký tự | `Resolved`; khách nhận `SUPPORT_RESOLVED`; audit `SUPPORT_REQUEST_RESOLVED` |
| Huỷ | Khách | còn mở | `Canceled`; audit `SUPPORT_REQUEST_CANCELED` |

Loại: `LOST_KEY`, `ACCESS_CODE`, `UNIT_ISSUE`, `BILLING`, `OTHER`.

## Xử lý kèm thao tác

Áp dụng khi yêu cầu gắn hợp đồng `Active`. Thứ tự khoá: đơn → hợp đồng → yêu cầu.

- **Thay chìa:** `newKeyQuantity` nhận 0–10; `0` hoặc bỏ trống = không thay chìa, `1–10` → chìa đang `Active` chuyển
  `Lost`, cấp bộ chìa mới
  (`RentalContractService.replacePhysicalKey`). Audit `ACCESS_KEY_REVOKED` + `ACCESS_KEY_ISSUED`.
- **Thu phí:** phí > 0 → hoá đơn `Service`:
  - phí = `feeAmount` nếu có nhập (kể cả `0` = không thu);
  - bỏ trống `feeAmount` + yêu cầu `LOST_KEY` + có thay chìa → phí mặc định = `fee.lost_key × quantity` của bộ chìa
    `Active` bị thay (đọc trước khi bộ cũ chuyển `Lost`; không có bộ chìa đang dùng → 0, không thu);
  - loại yêu cầu khác không có phí tự động;
  - mã `INV-SVC-{facility}-…`, gắn hợp đồng, hạn 7 ngày;
  - khách thanh toán qua `/api/invoices/{id}/pay`;
  - khi trả kho mà hoá đơn chưa trả thì được gộp vào quyết toán.
- **Mã khóa số:** khách tự cấp lại qua `POST /api/customer/contracts/{id}/access-code`; FS chỉ ghi kết quả.

## Route

Bearer token + `ACCOUNT_ACTIVE`; prefix quyết role (`/api/customer/**` → `CUSTOMER`, `/api/fm/**` → `FM`,
`/api/staff/**` → `FS`, sai role → 403). Lỗi nghiệp vụ trả `{message, timestamp}` qua `TenancyExceptionHandler`.

| Method | Path | Ai gọi | Lỗi chính |
|---|---|---|---|
| GET | `/api/customer/support-requests` | khách — yêu cầu của mình, mới nhất trước | — |
| POST | `/api/customer/support-requests` `{contractId?, facilityId?, category, title, description, photos?}` | khách | 400 thiếu/quá dài `title` (255) / `description` (4000), `category` sai, > 10 ảnh, không chọn hợp đồng lẫn cơ sở hợp lệ, `LOST_KEY` không gắn hợp đồng; 404 hợp đồng không phải của khách |
| POST | `/api/customer/support-requests/{id}/cancel` | khách chủ yêu cầu | 404 (không có / của khách khác); 409 "Yêu cầu hỗ trợ đã đóng" |
| GET | `/api/fm/support-requests?facilityId=&includeClosed=` | FM có `rental_order.read` tại cơ sở; mặc định chỉ `Open`/`Assigned` | 403 thiếu quyền / ngoài cơ sở |
| POST | `/api/fm/support-requests/{id}/assign` `{staffId}` | FM có `appointment.assign` tại cơ sở của yêu cầu | 400 FS không thuộc cơ sở / không hoạt động; 403; 404; 409 đã đóng |
| POST | `/api/fm/support-requests/{id}/resolve` `{resolution, newKeyQuantity?, feeAmount?}` | FM có `appointment.assign` tại cơ sở | như route FS bên dưới |
| GET | `/api/staff/support-requests` | FS — yêu cầu `Assigned` giao cho mình | — |
| POST | `/api/staff/support-requests/{id}/resolve` `{resolution, newKeyQuantity?, feeAmount?}` | FS được giao, có `unit_access_key.issue` tại cơ sở (người khác phải có `appointment.assign`) | 400 thiếu/quá dài `resolution` (2000), `newKeyQuantity` ngoài 0–10, `feeAmount` âm hoặc có phần lẻ (VND nguyên); 403; 404; 409 đã đóng, thay chìa / thu phí khi yêu cầu không gắn hợp đồng hoặc hợp đồng không `Active` |

## Test

`backoffice/SupportBackofficeIntegrationTest.java`, `backoffice/controller/BackofficeControllerTest.java`.
