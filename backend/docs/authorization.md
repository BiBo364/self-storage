---
purpose: Mô hình phân quyền — authorities, ba kiểu ràng buộc quyền, 401 vs 403; chi tiết từng route xem routes/.
---
# Authorization Model

Phân quyền backend: **ai được làm gì** sau khi đã xác thực. Phần danh tính/token nằm ở
[authentication.md](authentication.md).

## Authorities

`AccountJwtAuthenticationConverter` cấp authorities từ DB cho mỗi request:

| Authority | Nguồn | Ý nghĩa |
|---|---|---|
| `ROLE_<role>` | `Account.role.name` | `ADMIN` · `BOM` · `FM` · `FS` · `CUSTOMER` |
| `ACCOUNT_ACTIVE` | `status = ACTIVE` + email đã xác minh | account hoạt động và đã xác minh email |
| `ACCOUNT_UNVERIFIED` (marker) | `status = ACTIVE` + email chưa xác minh | không cấp quyền — chỉ để 403 trả đúng message |

Account `BANNED` không được cấp authority nào; account `ACTIVE` chưa xác minh chỉ có marker → mọi
route yêu cầu quyền đều 403 (message phân biệt hai ca — xem bảng 403 bên dưới).

## Models — ba kiểu ràng buộc quyền

Mỗi chức năng chọn đúng **một** trong ba model; mỗi model một công cụ:

| Model | Câu hỏi kiểm | Công cụ | Ví dụ |
|---|---|---|---|
| **Role-bound** | Chức năng thuộc về role nào? | Route namespace trong `SecurityConfig` (`/api/admin/**` → `hasRole("ADMIN")`) | Quản trị tài khoản / role / permission |
| **Permission-bound** | Role này có capability X? | `Access.can(permission[, facilityId])` + dữ liệu `role_permissions` | Duyệt yêu cầu; tạo notification `OTHER` |
| **Ownership** | Actor có phải chủ tài nguyên? | Check ở service, không thuộc RBAC | Khách xem/hủy đơn của mình |

Quy tắc chọn:

- Gán cho role khác là **vô nghĩa hoặc nguy hiểm** (quản trị hệ thống, tự nâng quyền) → role-bound.
  Không tạo permission; guard nằm một chỗ duy nhất ở `SecurityConfig`.
- Gán cho role khác **có thể có lý do nghiệp vụ** (FM/BOM cùng đọc, BOM tạo notification…) → permission.
- Phụ thuộc vào **instance dữ liệu** → ownership.

### Role-bound — guard ở route namespace

Chức năng mà gán cho role khác là vô nghĩa hoặc nguy hiểm. Guard nằm **một chỗ duy nhất** ở
`SecurityConfig`; controller/service không tự check role.

| Route | Guard | Dùng cho |
|---|---|---|
| `/api/customer/**` | `hasRole("CUSTOMER")` | API của khách |
| `/api/staff/**` | `hasRole("FS")` | API của FS |
| `/api/fm/**` | `hasRole("FM")` | API của FM |
| `/api/bom/**` | `hasRole("BOM")` | API của BOM |
| `/api/admin/**` | `hasRole("ADMIN")` | Quản trị hệ thống |
| `/api/**` (còn lại) | `ACCOUNT_ACTIVE` | Route permission-bound / ownership — guard ở method/service |
| `/api/health`, `/api/auth/**`, `/swagger-ui/**`, `/v3/api-docs/**` | `permitAll` | Public (`/api/auth/me` khớp rule riêng trước, vẫn cần token) |
| `POST /api/rental-requests`, `GET /api/unit-types` | `permitAll` đúng scope method | Form đặt kho + dropdown loại khoang (đặt trước rule vét `/api/**`) |
| `/api/payments/vnpay/ipn`, `/api/payments/vnpay/return` | `permitAll` | Callback của VNPay — xem ghi chú bên dưới |

Quy tắc đặt route: dùng role-bound → đặt controller dưới prefix của role; không dùng role-bound
(permission-bound / ownership) → để trong `/api/**` chung, guard ở method/service — **không** tự
thêm `hasRole` cho route mới.

Hai route `/api/payments/vnpay/ipn` + `/api/payments/vnpay/return` buộc phải public: server VNPay
gọi IPN và **không có JWT**, còn Return URL là redirect trình duyệt nên cũng không mang
`Authorization`. Hai route này không dùng token mà xác thực bằng **chữ ký HMAC-SHA512** trong
`vnp_SecureHash`; sai chữ ký thì IPN trả `97` và không ghi gì. Return URL thì không ghi dữ liệu
trong mọi trường hợp.

Matcher khai **đúng hai path** (không dùng wildcard `/api/payments/vnpay/**` — route thêm sau này
sẽ không tự public) và phải đứng **trước** dòng vét `/api/**`, nếu không VNPay nhận 401 và toàn bộ
luồng thu tiền chết. `SecurityConfigTest` có hai case chốt chặn điều này.

### Permission-bound — guard ở method bằng `Access.can(...)`

Capability gán được cho role qua dữ liệu `role_permissions`. Code phải có trong
[permission-catalog.md](permission-catalog.md) **trước** khi wire; dạng gọi phải khớp `scope` của
mapping — gọi sai dạng là lỗi lập trình (`IllegalStateException`), không âm thầm bỏ scope.

```java
// Global (scope GLOBAL) — NotificationController.java
access.can("notification.create_other");    // 403 nếu role thiếu permission

// Theo cơ sở (scope FACILITY) — PingFacilityController.java
access.can("facility.access", facilityId);  // 403 thiếu permission → 404 facility không tồn tại → 403 ngoài scope
```

`facilityId` bắt buộc ở dạng 2 tham số; ADMIN/BOM bỏ qua scope, FM/FS kiểm
`fm_account_id` / `account_facility_assignments`.

### Ownership — guard ở service, không thuộc RBAC

Quyền gắn với **instance dữ liệu**, không theo role → không tạo permission. Kiểm ở service;
tài nguyên không thuộc actor trả `404` như "không tồn tại" (không lộ sự tồn tại).

```java
// NotificationService.java — chỉ trả notification của chính account
public Notification getForAccount(Long accountId, Long notificationId) {
    return notificationRepository.findByIdAndAccountId(notificationId, accountId)
            .orElseThrow(NotificationNotFoundException::new);
}
```

## 401 vs 403

Mọi 401/403 trả body `{message, timestamp}` (401 kèm header `WWW-Authenticate`): 401 message là mô
tả lỗi token từ Spring. 403 có các message sau:

| Nguồn | Message |
|---|---|
| Sai role cho route (SecurityConfig) | `"Bạn không có quyền truy cập"` |
| Account `BANNED` | `"Tài khoản đã bị chặn"` |
| Account `ACTIVE` chưa xác minh email | `"Tài khoản chưa xác minh email"` |
| `Access.can()` — role thiếu permission | `"Bạn không có quyền thực hiện thao tác này"` |
| `Access.can(permission, facilityId)` — ngoài phạm vi cơ sở | `"Bạn không được gán vào cơ sở này"` |

Chi tiết: [error-handling.md](error-handling.md).

## Điều kiện nghiệp vụ: xác minh email

- Xác minh email **bắt buộc trước khi tạo account**: `Account` tự đăng ký chỉ được tạo sau khi khách
  nhập đúng mã (đã xác minh). Account legacy/seed có `email_verified_at = null` bị chặn đăng nhập và
  mọi thao tác nghiệp vụ — chỉ luồng xác minh email hoạt động (register / verify-email /
  resend-verification).
- Enforce ở tầng authentication: login (`AuthService`) và mỗi request
  (`AccountJwtAuthenticationConverter` — xem
  [authentication.md › Trạng thái account](authentication.md#trạng-thái-account)).
- Spec: [db-table-draft.md › Account](../../specs/db-table-draft.md#account);
  [draft.md › Business workflow](../../specs/draft.md#business-workflow).

## Chưa có

- UI quản trị gán permission cho role (Flow 5): mapping hiện sửa qua seed/SQL.
- Luồng gán lại FM khi account FM bị `BANNED`/chưa xác minh. `facilities.fm_account_id` giữ
  nguyên, nên facility không còn account vận hành; reassign hiện thủ công hoặc thuộc Flow 5.
- Namespace `/api/fm/**` có các màn của FM: hàng đợi + duyệt/từ chối yêu cầu đặt kho, xem đơn để đề xuất
  lại (`GET /api/fm/rental-orders/{orderId}`, role FM **cộng** facility scope). Thao tác đề xuất khoang theo
  quy tắc đặt route ở trên: `/api/proposals/**` (ownership) và `/api/rental-orders/{orderId}/proposals`
  (permission `proposal_feedback.repropose` theo cơ sở) nằm trong `/api/**` chung. `/api/bom/**`… vẫn chưa có.
