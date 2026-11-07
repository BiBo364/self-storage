---
purpose: Yêu cầu hỗ trợ / sự cố (Flow 7) as-built — khách gửi, FM giao FS, FS xử lý, thay chìa, phí dịch vụ.
---
# Yêu cầu hỗ trợ / sự cố

Spec Flow 7 chỉ có tiêu đề; đây là thiết kế đã code trên nhánh full-demo. Code:
`support/application/SupportService.java`, `support/controller/SupportController.java`, bảng `support_requests` (V21).

## Trạng thái

`Open` → `Assigned` → `Resolved`. Khách huỷ được khi còn `Open` / `Assigned` (`Canceled`).

| Bước | Ai | Điều kiện | Kết quả |
|---|---|---|---|
| Gửi | Khách | gắn `contractId` (hợp đồng của mình) hoặc chọn `facilityId`; `LOST_KEY` bắt buộc hợp đồng; tiêu đề ≤ 255, mô tả ≤ 4000, ảnh ≤ 10 | `Open`; FM của cơ sở nhận `SUPPORT_REQUEST_CREATED`; audit `SUPPORT_REQUEST_CREATED` |
| Giao | FM (`appointment.assign` + facility scope), FS đang hoạt động của cơ sở | yêu cầu còn mở (giao lại được) | `Assigned`; FS và khách nhận `SUPPORT_ASSIGNED`; audit `SUPPORT_REQUEST_ASSIGNED` |
| Xử lý | FS được giao (`unit_access_key.issue`) hoặc FM của cơ sở | còn mở; kết quả ≤ 2000 ký tự | `Resolved`; khách nhận `SUPPORT_RESOLVED`; audit `SUPPORT_REQUEST_RESOLVED` |
| Huỷ | Khách | còn mở | `Canceled`; audit `SUPPORT_REQUEST_CANCELED` |

Loại: `LOST_KEY`, `ACCESS_CODE`, `UNIT_ISSUE`, `BILLING`, `OTHER`.

## Xử lý kèm thao tác

Áp dụng khi yêu cầu gắn hợp đồng `Active`. Thứ tự khoá: đơn → hợp đồng → yêu cầu.

- **Thay chìa:** `newKeyQuantity` (1–10) → chìa đang `Active` chuyển `Lost`, cấp bộ chìa mới
  (`RentalContractService.replacePhysicalKey`). Audit `ACCESS_KEY_REVOKED` + `ACCESS_KEY_ISSUED`.
- **Thu phí:** `feeAmount` > 0 → hoá đơn `Service`:
  - mã `INV-SVC-{facility}-…`, gắn hợp đồng, hạn 7 ngày;
  - khách thanh toán qua `/api/invoices/{id}/pay`;
  - khi trả kho mà hoá đơn chưa trả thì được gộp vào quyết toán.
- **Mã khóa số:** khách tự cấp lại qua `POST /api/customer/contracts/{id}/access-code`; FS chỉ ghi kết quả.

## Route

| Method | Path | Quyền |
|---|---|---|
| GET / POST | `/api/customer/support-requests` | role `CUSTOMER` |
| POST | `/api/customer/support-requests/{id}/cancel` | role `CUSTOMER`, chủ yêu cầu |
| GET | `/api/fm/support-requests?facilityId=&includeClosed=` | role `FM`, `rental_order.read` + facility scope |
| POST | `/api/fm/support-requests/{id}/assign` `{staffId}` | role `FM`, `appointment.assign` + facility scope |
| POST | `/api/fm/support-requests/{id}/resolve` | role `FM`, `appointment.assign` + facility scope |
| GET | `/api/staff/support-requests` | role `FS` — yêu cầu `Assigned` giao cho mình |
| POST | `/api/staff/support-requests/{id}/resolve` `{resolution, newKeyQuantity?, feeAmount?}` | role `FS`, đúng FS + `unit_access_key.issue` |

## Test

`backoffice/SupportBackofficeIntegrationTest.java`, `backoffice/controller/BackofficeControllerTest.java`.
