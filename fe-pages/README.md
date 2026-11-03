# Danh sách Page FE — Self Storage System

Inventory này là checklist bàn giao cho frontend. Mỗi page spec chi tiết vẫn nằm trong thư mục tương ứng và mô tả Route, Actor, dữ liệu, Actions, States, API và Edge case/UX.

## Quy ước đọc inventory

- **UI/UX objective** là mục tiêu thiết kế chính của surface đó. Nó không thay thế page spec.
- Customer routes dùng ngôn ngữ sản phẩm, không đưa role vào URL:
  - `/my-storage`
  - `/my-storage/:id`
  - `/proposals`
  - `/invoices`
  - `/invoices/:id`
  - `/appointments`
  - `/appointments/new?orderId=:id`
  - `/support`
  - `/notifications`
- Staff routes dùng prefix `/staff` vì đây là workspace vận hành:
  - `/staff/schedule`
  - `/staff/appointments/:id/handover`
  - `/staff/appointments/:id/return`
  - `/staff/support-requests`
  - `/staff/incidents/new`
- Ba surface customer là panel/modal trên `/my-storage/:id`, không phải route riêng:
  - Gia hạn hợp đồng.
  - Trả kho.
  - Báo sự cố.
- Các route trong page spec chi tiết vẫn là evidence nghiệp vụ cũ. Khi khác với quy ước ở đây, dùng route mới trong inventory này cho UI prototype và cập nhật spec chi tiết trong task riêng.

## Customer — 10 page/surface

| # | Page | Route dùng cho UI | UI/UX objective |
|---|---|---|---|
| 1 | [Xem kho có sẵn](customer/01-browse-units.md) | `/` hoặc `/units` | Giúp khách hiểu lựa chọn, so sánh nhanh diện tích/giá và biết bước tiếp theo mà không cần đăng nhập. |
| 2 | [Form yêu cầu đặt kho](customer/02-rental-request-form.md) | `/rental-requests/new` | Giảm số bước và lo lắng khi gửi nhu cầu; làm rõ đây là request, chưa phải đặt kho thành công. |
| 3 | [Kiểm tra kho của tôi / duyệt đề xuất](customer/03-my-proposals.md) | `/proposals` | Giúp khách hiểu đề xuất đang chờ và quyết định đồng ý/từ chối với đủ context. |
| 4 | [Hóa đơn & thanh toán](customer/04-invoices-payment.md) | `/invoices`, `/invoices/:id` | Cho khách biết khoản nào cần trả, bao nhiêu, hạn nào và vì lý do gì. |
| 5 | [Chọn lịch hẹn check-in](customer/05-checkin-appointment-booking.md) | `/appointments/new?orderId=:id` | Giúp khách chọn ngày/giờ đúng, thấy rõ địa điểm và xác nhận trước khi gửi. |
| 6 | [Kho của tôi](customer/06-my-contracts-dashboard.md) | `/my-storage` | Giúp khách scan kho đang thuê, thời hạn và việc cần làm tiếp theo. |
| 7 | [Chi tiết khoang chứa & hợp đồng](customer/07-contract-detail.md) | `/my-storage/:id` | Gom thông tin kho, hợp đồng, thanh toán và các action liên quan vào một surface rõ ràng. |
| 8 | [Panel yêu cầu gia hạn](customer/08-extend-request-panel.md) | Panel trên `/my-storage/:id` | Giúp khách gửi số tháng gia hạn và hiểu trạng thái duyệt/thanh toán tiếp theo. |
| 9 | [Panel yêu cầu trả kho](customer/09-return-request-panel.md) | Panel trên `/my-storage/:id` | Làm rõ ngày trả, đây mới là request và các bước xử lý sau đó. |
| 10 | [Form báo sự cố](customer/10-support-request-form.md) | Panel trên `/my-storage/:id` | Giúp khách báo đúng loại sự cố và biết request đã được tiếp nhận, đang xử lý hay hoàn tất. |

## Shared / Auth — 3 page/surface

| # | Page | Route | UI/UX objective |
|---|---|---|---|
| 1 | [Đăng nhập](shared/01-login.md) | `/login` | Làm cổng vào rõ ràng cho mọi role, có feedback loading/error và không gây nhầm luồng customer với staff. |
| 2 | [Đăng ký & xác minh email](shared/02-register-verify.md) | `/register`, `/verify-email?token=...` | Giúp customer hiểu account đang ở trạng thái nào và cần làm gì để hoàn tất xác minh. |
| 3 | [Trung tâm thông báo](shared/03-notifications-center.md) | `/notifications` | Biến notification thành thông tin hoặc việc cần làm có thể scan, lọc và mở tới resource liên quan. |

## Facility Manager — 12 page

| # | Page | Route | UI/UX objective |
|---|---|---|---|
| 1 | [Hàng chờ yêu cầu đặt kho](fm/01-rental-request-queue.md) | `/fm/rental-requests` | Giúp FM xử lý queue nhanh, thấy nhu cầu chính và action ngay tại entry. |
| 2 | [Đề xuất lại khoang](fm/02-proposal-redo.md) | `/fm/rental-orders/:id/re-propose` | Giúp FM chọn phương án thay thế mà không mất lịch sử proposal và lý do trước đó. |
| 3 | [Lịch hẹn & phân công FS](fm/03-appointment-schedule.md) | `/fm/appointments` | Giúp FM thấy tải công việc theo ngày và phân công đúng FS. |
| 4 | [Hàng chờ yêu cầu trả kho](fm/04-return-request-queue.md) | `/fm/return-requests` | Giúp FM không bỏ sót preferred date và assign đúng người xử lý. |
| 5 | [Hàng chờ yêu cầu gia hạn](fm/05-extend-request-queue.md) | `/fm/extend-requests` | Giúp FM so sánh thời hạn hiện tại với yêu cầu mới và quyết định rõ ràng. |
| 6 | [Hàng chờ yêu cầu hỗ trợ](fm/06-support-request-queue.md) | `/fm/support-requests` | Giúp FM phân loại, ưu tiên và giao sự cố cho FS với đủ context. |
| 7 | [Quản lý khoang chứa](fm/07-storage-unit-management.md) | `/fm/storage-units` | Giúp FM nhìn chính xác trạng thái vật lý và khả dụng của từng khoang. |
| 8 | [Danh sách nhân viên FS](fm/08-staff-list.md) | `/fm/staff` | Giúp FM biết ai thuộc facility và workload hiện tại của từng FS. |
| 9 | [Báo cáo cơ sở](fm/09-facility-report-dashboard.md) | `/fm/reports` | Giúp FM phát hiện nhanh vấn đề vận hành trong facility được giao. |
| 10 | [Quản lý hóa đơn cơ sở](fm/10-invoice-management.md) | `/fm/invoices` | Giúp FM theo dõi công nợ theo facility mà không nhầm với thao tác thanh toán của customer. |
| 11 | [Theo dõi khách hàng & hợp đồng](fm/11-customer-contract-overview.md) | `/fm/contracts` | Giúp FM chủ động theo dõi customer, thời hạn và payment status trong facility. |
| 12 | [Theo dõi đơn thuê kho](fm/12-rental-order-list.md) | `/fm/rental-orders` | Giúp FM theo dõi toàn bộ đơn thuê, trạng thái xử lý và mở re-propose khi cần. |

## Facility Staff — 5 page

| # | Page | Route | UI/UX objective |
|---|---|---|---|
| 1 | [Lịch làm việc trong ngày](fs/01-daily-schedule.md) | `/staff/schedule` | Là màn hình bắt đầu ca; FS phải biết việc tiếp theo và mở đúng checklist. |
| 2 | [Checklist bàn giao on-site](fs/02-onsite-handover-checklist.md) | `/staff/appointments/:id/handover` | Dẫn FS qua checklist tuần tự, không bỏ bước và không ghi nhận nhầm. |
| 3 | [Checklist trả kho on-site](fs/03-onsite-return-checklist.md) | `/staff/appointments/:id/return` | Giúp FS đối chiếu hiện trạng trước/sau và xử lý phí phát sinh chính xác. |
| 4 | [Xử lý yêu cầu hỗ trợ được phân công](fs/04-support-request-handling.md) | `/staff/support-requests` | Giúp FS xử lý request tại hiện trường với action nhanh và context đầy đủ. |
| 5 | [Form tự ghi nhận sự cố](fs/05-incident-report-form.md) | `/staff/incidents/new` | Tách rõ việc tạo incident chủ động khỏi xử lý support request đã assign. |

## Business Operation Manager — 8 page

| # | Page | Route | UI/UX objective |
|---|---|---|---|
| 1 | [Quản lý business rules / policy](bom/01-business-rules-policy.md) | `/bom/policies` | Giúp BOM hiểu policy active, thời điểm áp dụng và tác động của thay đổi. |
| 2 | [Quản lý các loại phí](bom/02-fee-management.md) | `/bom/fees` | Giúp BOM quản lý fee catalog và cấu hình cách tính mà không tạo fee mơ hồ. |
| 3 | [Quản lý discount](bom/03-discount-management.md) | `/bom/discounts` | Giúp BOM kiểm soát code, phạm vi, giá trị và thời hạn discount. **Provisional / có thể cắt khỏi MVP.** |
| 4 | [Dashboard doanh thu](bom/04-revenue-dashboard.md) | `/bom/revenue` | Giúp BOM biết doanh thu tăng/giảm ở đâu và có breakdown đủ để giải thích. |
| 5 | [Quản lý cơ sở](bom/05-facility-management.md) | `/bom/facilities` | Giúp BOM quản lý lifecycle facility và người phụ trách với ít thao tác nhầm. |
| 6 | [Chỉ định nhân sự / Role Request](bom/06-staff-role-request.md) | `/bom/staff-requests` | Tách rõ quyết định nghiệp vụ của BOM khỏi thao tác account kỹ thuật của Admin. |
| 7 | [Quản lý loại kho & giá thuê](bom/07-unit-type-pricing.md) | `/bom/unit-types` | Giúp BOM quản lý catalog và giá — nguồn giá duy nhất của hệ thống. |
| 8 | [Báo cáo toàn hệ thống](bom/08-system-wide-report.md) | `/bom/reports` | Giúp BOM so sánh hiệu suất giữa facility, khác mục tiêu với revenue dashboard. **Provisional / mở rộng.** |

## System Administrator — 5 page

| # | Page | Route | UI/UX objective |
|---|---|---|---|
| 1 | [Quản lý tài khoản](admin/01-account-management.md) | `/admin/accounts` | Giúp Admin quản lý account an toàn, có confirmation và audit context. |
| 2 | [Hàng chờ Account Role Request](admin/02-account-role-request-queue.md) | `/admin/staff-requests` | Giúp Admin thực thi chỉ định của BOM mà không biến nó thành business approval. |
| 3 | [Quản lý Role & Permission](admin/03-role-permission-management.md) | `/admin/rbac` | Giúp Admin đọc và chỉnh permission matrix với diff, warning và confirmation rõ. |
| 4 | [Lịch sử đăng nhập](admin/04-login-history.md) | `/admin/login-history` | Giúp Admin điều tra login failure và hoạt động bất thường bằng filter rõ. |
| 5 | [Audit Log](admin/05-audit-log.md) | `/admin/audit-log` | Giúp Admin truy vết ai đổi gì, khi nào và từ trạng thái nào sang trạng thái nào. |

## Shared surfaces không phải business page riêng

Các surface này cần được xây như pattern dùng chung, không nhân bản riêng trong từng page:

### Authenticated customer shell

- Sidebar customer với nhãn hướng theo nhu cầu khách hàng.
- Active tab dùng left rule 3px và nền tint nhẹ.
- Topbar account/theme.
- Profile menu và logout.
- Mobile navigation.
- Cảnh báo demo auth tập trung trong prototype.

### Detail panels và modals

- Proposal detail.
- Invoice detail.
- Extend request.
- Return request.
- Support request.
- Unit/contract detail.

### Required UI states

Mỗi page cần thiết kế các state phù hợp, không chỉ happy path:

- Loading.
- Empty.
- Error.
- Forbidden.
- Session expired.
- Not found.
- Pending mutation.
- Success.
- Destructive confirmation.

## Provisional scope

- Flow 6 (quá hạn/gia hạn) chưa có page analysis riêng. Các page gia hạn và status liên quan cần giữ provisional.
- Flow 7 (hỗ trợ/sự cố) chưa có page analysis riêng. Các support queue/handling surface cần giữ provisional.
- Một số cross-flow page được suy ra từ actor description, chưa có UI flow đầy đủ: FM invoice, FM customer contract overview, FS support handling, BOM discount và BOM system-wide report.
- Status, API contract, business rule và copy chỉ xuất hiện ở branch chưa merge vào `main` không được xem là product truth đã chốt.

**Tổng: 43 business page specs** — 10 Customer, 3 Shared/Auth, 12 FM, 5 FS, 8 BOM và 5 Admin.
