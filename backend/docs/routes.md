---
purpose: Index toàn bộ HTTP route đang cung cấp — method, path, quyền, link chi tiết.
---
# Route Index

Toàn bộ HTTP route backend đang cung cấp. Cột "Chi tiết" trỏ tới file route trong `routes/` hoặc tới doc module sở
hữu route (xem quy ước ở cuối trang).

| Method | Path | Quyền | Mô tả | Chi tiết |
|---|---|---|---|---|
| GET | `/api/health` | Public | Kiểm tra ứng dụng còn sống | [routes/health.md](routes/health.md) |
| POST | `/api/auth/login` | Public | Đăng nhập, nhận access token (JWT); sai quá nhiều lần → 429 | [routes/auth/login.md](routes/auth/login.md) |
| POST | `/api/auth/register` | Public | Đăng ký tài khoản CUSTOMER + gửi mã xác minh qua email | [routes/auth/register.md](routes/auth/register.md) |
| POST | `/api/auth/verify-email` | Public | Xác minh email bằng mã 6 số | [routes/auth/verify-email.md](routes/auth/verify-email.md) |
| POST | `/api/auth/resend-verification` | Public | Gửi lại mã xác minh (cooldown 60 giây) | [routes/auth/resend-verification.md](routes/auth/resend-verification.md) |
| GET | `/api/auth/me` | Bearer + `ACCOUNT_ACTIVE` | Xác minh session hiện tại | [routes/auth/me.md](routes/auth/me.md) |
| GET | `/api/facility-access/ping/{facilityId}` | Bearer + `ACCOUNT_ACTIVE`; `facility.access` + facility scope | Endpoint demo kiểm tra RBAC và quyền theo cơ sở | [routes/identity/facility-access-ping.md](routes/identity/facility-access-ping.md) |
| GET | `/api/notifications` | Bearer + `ACCOUNT_ACTIVE` | Danh sách thông báo của tôi (lọc `is_read`, phân trang) | [routes/notification/list.md](routes/notification/list.md) |
| GET | `/api/notifications/{notificationId}` | Chủ tài nguyên | Chi tiết thông báo | [routes/notification/detail.md](routes/notification/detail.md) |
| GET | `/api/notifications/unread-count` | Bearer + `ACCOUNT_ACTIVE` | Số thông báo chưa đọc | [routes/notification/unread-count.md](routes/notification/unread-count.md) |
| POST | `/api/notifications` | Bearer + `ACCOUNT_ACTIVE`; `notification.create_other` (ADMIN/BOM) | Tạo thông báo `OTHER` thủ công | [routes/notification/create-other.md](routes/notification/create-other.md) |
| POST | `/api/rental-requests` | Public | Gửi yêu cầu đặt kho (form public) | [routes/booking/rental-request-create.md](routes/booking/rental-request-create.md) |
| GET | `/api/fm/rental-requests` | Bearer + role `FM`; `rental_request.read` + facility scope | Hàng đợi yêu cầu đặt kho | [routes/booking/fm-rental-request-queue.md](routes/booking/fm-rental-request-queue.md) |
| GET | `/api/unit-types` | Public | Loại khoang cơ sở cung cấp cho form | [routes/pricing/unit-types.md](routes/pricing/unit-types.md) |
| GET | `/api/facilities` | Public | Catalog cơ sở đang hoạt động + loại khoang mỗi cơ sở cung cấp | [routes/facility/public-list.md](routes/facility/public-list.md) |
| GET | `/api/fm/facility` | Bearer + role `FM` | Cơ sở FM hiện tại phụ trách | [routes/booking/fm-facility.md](routes/booking/fm-facility.md) |
| GET | `/api/fm/storage-units` | Bearer + role `FM`; `facility.access` + facility scope | Khoang của cơ sở, lọc `status`/`unitTypeId` | [routes/booking/fm-storage-units.md](routes/booking/fm-storage-units.md) |
| GET | `/api/fm/invoices` | Bearer + role `FM`; `invoice.read` + facility scope | Hoá đơn cơ sở kèm trạng thái thanh toán; highlight Pending quá 15 phút | [routes/booking/fm-invoice-list.md](routes/booking/fm-invoice-list.md) |
| POST | `/api/fm/rental-requests/{id}/approve` | Bearer + role `FM`; `rental_request.approve` + facility scope | Duyệt yêu cầu đặt kho, gán khoang; email đã xác minh thì convert ngay (response vẫn `Approved`) | [routes/booking/fm-rental-request-approve.md](routes/booking/fm-rental-request-approve.md) |
| POST | `/api/fm/rental-requests/{id}/reject` | Bearer + role `FM`; `rental_request.reject` + facility scope | Từ chối yêu cầu đặt kho + mail (dừng ở Rejected) | [routes/booking/fm-rental-request-reject.md](routes/booking/fm-rental-request-reject.md) |
| GET | `/api/fm/appointments` | Bearer + role `FM`; `appointment.read` + facility scope | Lịch check-in của cơ sở theo ngày | [routes/booking/fm-appointment-day-list.md](routes/booking/fm-appointment-day-list.md) |
| GET | `/api/fm/facilities/{facilityId}/staff` | Bearer + role `FM`; `appointment.assign` + facility scope | FS đang hoạt động của cơ sở, kèm ca / nghỉ phép / việc còn mở của ngày `date` | [routes/booking/fm-facility-staff.md](routes/booking/fm-facility-staff.md) |
| POST | `/api/fm/appointments/{appointmentId}/staff` | Bearer + role `FM`; `appointment.assign` + facility scope | Phân công FS cho lịch check-in, đơn `Scheduled → InProgress` | [routes/booking/fm-appointment-assign-staff.md](routes/booking/fm-appointment-assign-staff.md) |
| PUT | `/api/fm/appointments/{appointmentId}/staff` | Bearer + role `FM`; `appointment.assign` + facility scope | Đổi FS của lịch check-in đã phân công, khách chưa đến | [routes/booking/fm-appointment-assign-staff.md](routes/booking/fm-appointment-assign-staff.md#put--đổi-fs) |
| GET | `/api/staff/appointments` | Bearer + role `FS`; `appointment.read` + đúng FS | Lịch làm việc của FS theo ngày | [routes/booking/staff-appointment-day-list.md](routes/booking/staff-appointment-day-list.md) |
| POST | `/api/staff/appointments/{appointmentId}/arrive` | Bearer + role `FS`; `appointment.checkin` + đúng FS | FS xác nhận khách đến, lịch `Pending → Done` (check-in: đơn đủ điều kiện bàn giao, chưa quá giờ; trả kho: nhận cả khi trễ) | [routes/booking/staff-appointment-arrive.md](routes/booking/staff-appointment-arrive.md) |
| POST | `/api/staff/appointments/{appointmentId}/cancel` | Bearer + role `FS`; `appointment.checkin` + đúng FS | FS chủ động huỷ lịch check-in `Pending` chưa quá giờ (lịch trả kho → 409) | [routes/booking/staff-appointment-cancel.md](routes/booking/staff-appointment-cancel.md) |
| GET | `/api/staff/handovers` | Bearer + role `FS` | Đơn đang bàn giao của FS (phiên mở, khách đã đến) | [routes/handover/staff-handovers.md](routes/handover/staff-handovers.md) |
| GET | `/api/staff/handovers/{id}` | Bearer + role `FS`; `rental_order.read` + facility scope | Chi tiết phiên bàn giao | [routes/handover/staff-handovers.md](routes/handover/staff-handovers.md) |
| POST | `/api/staff/handovers/{id}/identity` | Bearer + role `FS`; `handover_record.verify_identity` + đúng FS | Xác minh danh tính (2.2) | [routes/handover/staff-handovers.md](routes/handover/staff-handovers.md) |
| PUT | `/api/staff/handovers/{id}/inspection` | Bearer + role `FS`; `handover_record.inspect` + đúng FS | Ghi hiện trạng khoang (2.2) | [routes/handover/staff-handovers.md](routes/handover/staff-handovers.md) |
| POST | `/api/staff/handovers/{id}/inspection-correction` | Bearer + role `FS`; `handover_record.inspect` + đúng FS | Sửa hiện trạng sau khi khách đã đồng ý (lý do bắt buộc, audit `INSPECTION_CORRECTED`) | [routes/handover/staff-handovers.md](routes/handover/staff-handovers.md) |
| POST | `/api/staff/contracts/{contractId}/start-date-override` | Bearer + role `FS`; `rental_contract.start_date_override.request` + đúng FS | Đề nghị đổi mốc tính tiền (2.3) | [routes/handover/staff-handovers.md](routes/handover/staff-handovers.md) |
| POST | `/api/staff/contracts/{contractId}/sign` | Bearer + role `FS`; `rental_contract.sign` + đúng FS | Upload hợp đồng đã ký, tạo hoá đơn tháng đầu (2.3) | [routes/handover/staff-handovers.md](routes/handover/staff-handovers.md) |
| POST | `/api/staff/handovers/{id}/not-signed` | Bearer + role `FS`; `rental_contract.sign` + đúng FS | Khách không ký hợp đồng → Cascade (2.5) | [routes/handover/staff-handovers.md](routes/handover/staff-handovers.md) |
| POST | `/api/staff/handovers/{id}/keys` | Bearer + role `FS`; `unit_access_key.issue` + đúng FS | Giao chìa, chốt bàn giao (2.4) | [routes/handover/staff-handovers.md](routes/handover/staff-handovers.md) |
| GET | `/api/customer/handovers` | Bearer + role `CUSTOMER`; chủ tài nguyên | Phiên bàn giao của tôi | [routes/handover/customer-handovers.md](routes/handover/customer-handovers.md) |
| GET | `/api/customer/handovers/{id}` | Bearer + role `CUSTOMER`; chủ tài nguyên | Chi tiết phiên bàn giao | [routes/handover/customer-handovers.md](routes/handover/customer-handovers.md) |
| POST | `/api/customer/handovers/{id}/agree` | Bearer + role `CUSTOMER`; chủ tài nguyên | Đồng ý hiện trạng, sinh hợp đồng `Draft` (2.2) | [routes/handover/customer-handovers.md](routes/handover/customer-handovers.md) |
| POST | `/api/customer/handovers/{id}/reject` | Bearer + role `CUSTOMER`; chủ tài nguyên | Từ chối khoang (2.5) | [routes/handover/customer-handovers.md](routes/handover/customer-handovers.md) |
| POST | `/api/customer/handovers/{id}/confirm` | Bearer + role `CUSTOMER`; chủ tài nguyên | Xác nhận biên bản + chọn hình thức truy cập (2.4) | [routes/handover/customer-handovers.md](routes/handover/customer-handovers.md) |
| POST | `/api/customer/contracts/{contractId}/access-code` | Bearer + role `CUSTOMER`; chủ tài nguyên | Cấp lại mã khóa số (hiện một lần) | [routes/handover/customer-handovers.md](routes/handover/customer-handovers.md) |
| GET | `/api/contracts/{contractId}/document` | Bearer; khách của hợp đồng hoặc `rental_order.read` (ADMIN/BOM mọi cơ sở, FM/FS theo facility scope) | Bản hợp đồng HTML để in (đã ký → bản chốt lúc ký) | [routes/handover/contracts.md](routes/handover/contracts.md) |
| GET | `/api/contracts/{contractId}/document.pdf` | như trên | Bản hợp đồng PDF (sinh từ đúng HTML trên) | [routes/handover/contracts.md](routes/handover/contracts.md) |
| GET | `/api/fm/contracts/start-date-overrides` | Bearer + role `FM` | Đề nghị đổi mốc tính tiền đang chờ | [routes/handover/contracts.md](routes/handover/contracts.md) |
| POST | `/api/fm/contracts/{contractId}/start-date-override/approve` | Bearer + role `FM`; `rental_contract.start_date_override.review` + facility scope | Duyệt mốc tính tiền mới | [routes/handover/contracts.md](routes/handover/contracts.md) |
| POST | `/api/fm/contracts/{contractId}/start-date-override/reject` | Bearer + role `FM`; `rental_contract.start_date_override.review` + facility scope | Từ chối đổi mốc | [routes/handover/contracts.md](routes/handover/contracts.md) |
| GET | `/api/customer/rentals` | Bearer + role `CUSTOMER`; chủ tài nguyên | Kho đang thuê (hợp đồng `Active`) | [tenancy/rentals.md](tenancy/rentals.md) |
| GET | `/api/customer/rentals/{contractId}` | Bearer + role `CUSTOMER`; chủ tài nguyên | Chi tiết kho đang thuê | [tenancy/rentals.md](tenancy/rentals.md) |
| POST | `/api/customer/rentals/{contractId}/extensions` | Bearer + role `CUSTOMER`; chủ tài nguyên | Gia hạn — tạo hoá đơn `Extension` | [tenancy/rentals.md](tenancy/rentals.md) |
| POST | `/api/customer/rentals/{contractId}/return-request` | Bearer + role `CUSTOMER`; chủ tài nguyên | Gửi yêu cầu trả kho | [tenancy/rentals.md](tenancy/rentals.md) |
| GET | `/api/customer/return-requests` | Bearer + role `CUSTOMER`; chủ tài nguyên | Yêu cầu trả kho của tôi | [tenancy/rentals.md](tenancy/rentals.md) |
| POST | `/api/customer/return-requests/{id}/cancel` | Bearer + role `CUSTOMER`; chủ tài nguyên | Huỷ yêu cầu trả kho (trước khi FS bắt đầu) | [tenancy/rentals.md](tenancy/rentals.md) |
| GET | `/api/customer/return-requests/{id}/checkout` | Bearer + role `CUSTOMER`; chủ tài nguyên | Biên bản trả kho của tôi | [checkout/return.md](checkout/return.md) |
| GET | `/api/fm/return-requests?facilityId=` | Bearer + role `FM`; `rental_order.read` + facility scope | Yêu cầu trả kho đang mở | [tenancy/rentals.md](tenancy/rentals.md) |
| POST | `/api/fm/return-requests/{id}/assign` | Bearer + role `FM`; `appointment.assign` + facility scope | Phân công FS, tạo lịch `RETURN` | [tenancy/rentals.md](tenancy/rentals.md) |
| GET | `/api/fm/overdue-contracts?facilityId=` | Bearer + role `FM`; `rental_order.read` + facility scope | Hợp đồng quá hạn + phí cộng dồn | [tenancy/rentals.md](tenancy/rentals.md) |
| GET | `/api/staff/return-requests` | Bearer + role `FS` | Buổi trả kho được phân cho tôi | [tenancy/rentals.md](tenancy/rentals.md) |
| GET | `/api/staff/return-requests/{id}/checkout` | Bearer + role `FS`; `unit_access_key.issue` + facility scope | Biên bản trả kho + phí dự kiến | [checkout/return.md](checkout/return.md) |
| PUT | `/api/staff/return-requests/{id}/checkout` | Bearer + role `FS`; `unit_access_key.issue` + đúng FS | Ghi kết quả kiểm tra khoang | [checkout/return.md](checkout/return.md) |
| POST | `/api/staff/return-requests/{id}/checkout/confirm` | Bearer + role `FS`; `unit_access_key.issue` + đúng FS | Chốt trả kho, quyết toán cọc | [checkout/return.md](checkout/return.md) |
| GET / POST | `/api/customer/support-requests` | Bearer + role `CUSTOMER` | Yêu cầu hỗ trợ của tôi / gửi yêu cầu | [support/requests.md](support/requests.md) |
| POST | `/api/customer/support-requests/{id}/cancel` | Bearer + role `CUSTOMER`; chủ tài nguyên | Huỷ yêu cầu hỗ trợ | [support/requests.md](support/requests.md) |
| GET | `/api/fm/support-requests?facilityId=` | Bearer + role `FM`; `rental_order.read` + facility scope | Hàng chờ hỗ trợ của cơ sở | [support/requests.md](support/requests.md) |
| POST | `/api/fm/support-requests/{id}/assign` | Bearer + role `FM`; `appointment.assign` + facility scope | Giao FS xử lý | [support/requests.md](support/requests.md) |
| POST | `/api/fm/support-requests/{id}/resolve` | Bearer + role `FM`; `appointment.assign` + facility scope | FM ghi kết quả xử lý | [support/requests.md](support/requests.md) |
| GET | `/api/staff/support-requests` | Bearer + role `FS` | Yêu cầu hỗ trợ được giao | [support/requests.md](support/requests.md) |
| POST | `/api/staff/support-requests/{id}/resolve` | Bearer + role `FS`; `unit_access_key.issue` + đúng FS | Xử lý (thay chìa, thu phí) | [support/requests.md](support/requests.md) |
| GET | `/api/fm/contracts`, `/api/fm/reports` | Bearer + role `FM`; `facility.access` + facility scope | Hợp đồng / báo cáo của cơ sở | [backoffice/overview.md](backoffice/overview.md) |
| POST / PATCH | `/api/fm/storage-units`, `/api/fm/storage-units/{id}`, `/api/fm/storage-units/{id}/maintenance` | Bearer + role `FM`; `facility.access` + facility scope | Thêm / sửa khoang, bảo trì tay | [backoffice/overview.md](backoffice/overview.md) |
| POST | `/api/fm/storage-units/{unitId}/maintenance-hold` | Bearer + role `FM`; `facility.access` + facility scope (cơ sở của khoang) | "Giữ bảo trì": khoang bảo trì sau trả kho thành bảo trì thủ công (cron không tự mở lại) | [backoffice/overview.md](backoffice/overview.md) |
| GET / POST | `/api/fm/invoices/{invoiceId}/refunds` | Bearer + role `FM`; `payment_transaction.refund` + facility scope (cơ sở của hoá đơn) | Lịch sử hoàn tiền / ghi nhận hoàn tiền thủ công | [routes/booking/fm-invoice-refunds.md](routes/booking/fm-invoice-refunds.md) |
| GET | `/api/fm/facilities/{id}/access-options` | Bearer + role `FM`; `facility.access` + facility scope | Cơ sở + hình thức truy cập đang bật | [backoffice/overview.md](backoffice/overview.md) |
| PUT | `/api/fm/facilities/{id}/access-options` | Bearer + role `FM`; `facility.access` + facility scope | Bật / tắt khóa cơ, mã số | [backoffice/overview.md](backoffice/overview.md) |
| GET / POST / PUT / DELETE | `/api/fm/staff-shifts`, `/api/fm/staff-shifts/{id}` | Bearer + role `FM`; `staff_shift.manage` + facility scope | Ca làm việc của FS tại cơ sở (chồng ca / nghỉ phép / lịch rơi khỏi ca → 409) | [backoffice/hr.md](backoffice/hr.md) |
| GET / POST | `/api/fm/staff-leaves`, `/api/fm/staff-leaves/{id}/approve`, `/api/fm/staff-leaves/{id}/reject` | Bearer + role `FM`; `staff_leave.review` + facility scope | Đơn nghỉ phép của FS thuộc cơ sở; duyệt (FS còn việc → 409; xoá ca trong khoảng nghỉ) / từ chối | [backoffice/hr.md](backoffice/hr.md) |
| GET | `/api/fm/staff/{id}/profile` | Bearer + role `FM`; `staff_profile.read` + facility scope | Hồ sơ FS (không có số giấy tờ) | [backoffice/hr.md](backoffice/hr.md) |
| GET | `/api/fm/reports/staff` | Bearer + role `FM`; `facility.access` + facility scope | Báo cáo nhân sự của cơ sở | [backoffice/hr.md](backoffice/hr.md) |
| GET | `/api/staff/shifts` | Bearer + role `FS`; ownership | Ca của tôi | [backoffice/hr.md](backoffice/hr.md) |
| GET / POST | `/api/staff/leaves`, `/api/staff/leaves/{id}/cancel` | Bearer + role `FS`; ownership | Đơn nghỉ phép của tôi / gửi / huỷ | [backoffice/hr.md](backoffice/hr.md) |
| GET | `/api/staff/profile` | Bearer + role `FS`; ownership | Hồ sơ của tôi (không có số giấy tờ) | [backoffice/hr.md](backoffice/hr.md) |
| GET / PUT | `/api/bom/staff/{id}/profile`, `/api/admin/accounts/{id}/profile` | Bearer + role `BOM` / `ADMIN`; `staff_profile.read` / `staff_profile.manage` | Hồ sơ nhân viên FS / FM đầy đủ (gồm số giấy tờ) | [backoffice/hr.md](backoffice/hr.md) |
| GET | `/api/bom/reports/staff` | Bearer + role `BOM` | Báo cáo nhân sự toàn chuỗi / một cơ sở | [backoffice/hr.md](backoffice/hr.md) |
| GET / POST / PATCH / PUT | `/api/bom/facilities…`, `/api/bom/policies…`, `/api/bom/unit-types…`, `/api/bom/storage-units/{id}`, `/api/bom/reports`, `/api/bom/invoices`, `/api/bom/managers`, `/api/bom/staff…` | Bearer + role `BOM` | Quản lý cơ sở, FM, gán FS vào cơ sở, policy, giá; báo cáo toàn chuỗi | [backoffice/overview.md](backoffice/overview.md) |
| GET / POST / PATCH / PUT | `/api/admin/accounts…`, `/api/admin/login-events`, `/api/admin/audit-logs`, `/api/admin/permissions`, `/api/admin/roles/{role}/permissions` | Bearer + role `ADMIN` | Tài khoản, phân quyền, lịch sử đăng nhập, nhật ký | [backoffice/overview.md](backoffice/overview.md) |
| PATCH | `/api/notifications/{notificationId}/read` | Chủ tài nguyên | Đánh dấu một thông báo đã đọc | [routes/notification/mark-read.md](routes/notification/mark-read.md) |
| PATCH | `/api/notifications/read-all` | Bearer + `ACCOUNT_ACTIVE` | Đánh dấu tất cả đã đọc | [routes/notification/read-all.md](routes/notification/read-all.md) |
| GET | `/api/proposals` | Bearer + `ACCOUNT_ACTIVE`; chủ tài nguyên | Đề xuất khoang của tôi (lọc `status`) | [routes/booking/proposals-list.md](routes/booking/proposals-list.md) |
| GET | `/api/invoices` | Bearer + `ACCOUNT_ACTIVE`; chủ tài nguyên | Hoá đơn của tôi, mới nhất trước | [routes/booking/invoice-list.md](routes/booking/invoice-list.md) |
| GET | `/api/invoices/{invoiceId}` | Bearer + `ACCOUNT_ACTIVE`; chủ tài nguyên | Một hoá đơn của mình; FE đọc lại sau khi cổng thanh toán redirect về | [routes/booking/invoice-detail.md](routes/booking/invoice-detail.md) |
| POST | `/api/proposals/{proposalId}/agree` | Bearer + `ACCOUNT_ACTIVE`; chủ tài nguyên | Đồng ý đề xuất, tạo hoá đơn đặt cọc | [routes/booking/proposal-agree.md](routes/booking/proposal-agree.md) |
| POST | `/api/proposals/{proposalId}/reject` | Bearer + `ACCOUNT_ACTIVE`; chủ tài nguyên | Từ chối đề xuất; lần thứ `max_rejection_count` huỷ đơn | [routes/booking/proposal-reject.md](routes/booking/proposal-reject.md) |
| GET | `/api/fm/rental-orders` | `FM`; `rental_order.read` + facility scope | Danh sách đơn thuê của cơ sở, lọc trạng thái, cờ đề xuất lại | [routes/booking/fm-rental-order-list.md](routes/booking/fm-rental-order-list.md) |
| GET | `/api/fm/rental-orders/{orderId}` | `FM`; `rental_order.read` + facility scope | Chi tiết đơn để đề xuất lại | [routes/booking/fm-rental-order-detail.md](routes/booking/fm-rental-order-detail.md) |
| POST | `/api/rental-orders/{orderId}/proposals` | Bearer + `ACCOUNT_ACTIVE`; `proposal_feedback.repropose` (+ `.override`) + facility scope | Đề xuất lại khoang cho đơn | [routes/booking/rental-order-proposals-create.md](routes/booking/rental-order-proposals-create.md) |
| POST | `/api/invoices/{invoiceId}/pay` | Bearer + `ACCOUNT_ACTIVE`; chủ hóa đơn + đã xác minh email | Mở phiên thanh toán hoá đơn — hoá đơn cọc theo 5 nhánh spec 1.4, hoá đơn gắn hợp đồng theo trạng thái hoá đơn/đơn; trả URL của cổng đang dùng (VNPay mặc định, payOS tuỳ chọn) | [routes/booking/invoice-pay.md](routes/booking/invoice-pay.md) |
| POST | `/api/invoices/{invoiceId}/payment-check` | Bearer + `ACCOUNT_ACTIVE`; chủ tài nguyên | Khách quay về từ cổng: hỏi cổng về giao dịch mới nhất (VNPay `querydr`, payOS tra cứu `payment-requests`), trả trạng thái | [routes/booking/invoice-payment-check.md](routes/booking/invoice-payment-check.md) |
| GET | `/api/admin/payments/reconciliation` | Bearer + role `ADMIN` | Case đối soát `view=open|history`, có pagination; `/payments/pending` là alias `open` | [routes/payment/admin-pending-payments.md](routes/payment/admin-pending-payments.md) |
| GET | `/api/fm/invoices/refunds` | Bearer + role `FM`; `payment_transaction.refund` + facility scope | Hàng đợi invoice `Required`/`Processing` của cơ sở, có pagination | [routes/booking/fm-refund-queue.md](routes/booking/fm-refund-queue.md) |
| GET | `/api/admin/payments/refunds` | Bearer + role `ADMIN` | Danh sách invoice đang chờ hoàn tiền (`Required`/`Processing`), có pagination | [routes/payment/admin-refund-required.md](routes/payment/admin-refund-required.md) |
| POST | `/api/admin/payments/{txnRef}/reconcile` | Bearer + role `ADMIN` | Chốt thủ công giao dịch Pending theo portal cổng | [routes/payment/manual-reconcile.md](routes/payment/manual-reconcile.md) |
| GET | `/api/rental-orders` | Bearer + `ACCOUNT_ACTIVE`; chủ tài nguyên | Đơn thuê của tôi + `availableActions` | [routes/booking/rental-orders-mine.md](routes/booking/rental-orders-mine.md) |
| GET | `/api/rental-orders/{orderId}` | Bearer + `ACCOUNT_ACTIVE`; chủ tài nguyên | Chi tiết đơn thuê của tôi | [routes/booking/rental-orders-mine.md](routes/booking/rental-orders-mine.md) |
| POST | `/api/rental-orders/{orderId}/cancel` | Bearer + `ACCOUNT_ACTIVE`; chủ tài nguyên | Khách huỷ đơn (Cancellation Cascade) | [routes/booking/rental-order-cancel.md](routes/booking/rental-order-cancel.md) |
| GET | `/api/rental-orders/{orderId}/appointment-slots` | Bearer + `ACCOUNT_ACTIVE`; chủ tài nguyên | Khung giờ check-in còn trống | [routes/booking/appointment-slots.md](routes/booking/appointment-slots.md) |
| POST | `/api/rental-orders/{orderId}/appointments` | Bearer + `ACCOUNT_ACTIVE`; chủ tài nguyên | Đặt lịch check-in, mở HandoverRecord | [routes/booking/appointment-book.md](routes/booking/appointment-book.md) |
| POST | `/api/fm/rental-orders/{orderId}/cancel` | Bearer + role `FM`; `rental_order.cancel` + facility scope | FM huỷ đơn thuê | [routes/booking/fm-rental-order-cancel.md](routes/booking/fm-rental-order-cancel.md) |
| POST | `/api/chat` | Public | Chatbot tư vấn (Google Gemini) — trả lời về cơ sở, loại khoang, giá, quy trình | [chat/assistant.md](chat/assistant.md) |
| POST | `/api/payments/payos/webhook` | Public (chữ ký payOS) | payOS báo kết quả chuyển khoản — nguồn xác nhận chính khi `payment.provider=payos` | [routes/payment/payos-webhook.md](routes/payment/payos-webhook.md) |
| GET | `/api/payments/vnpay/ipn` | Public (chữ ký VNPay) | VNPay xác nhận thanh toán — nguồn xác nhận chính khi dùng VNPay (mặc định) | [routes/payment/vnpay-ipn.md](routes/payment/vnpay-ipn.md) |
| GET | `/api/payments/vnpay/return` | Public (redirect VNPay) | Trang kết quả cho khách khi dùng VNPay — không ghi dữ liệu | [routes/payment/vnpay-return.md](routes/payment/vnpay-return.md) |
| POST | `/api/media/uploads` | Bearer + `ACCOUNT_ACTIVE` | Upload file (ảnh/PDF ≤ 10MB), trả URL + batchId dùng lại trong flow | [routes/media/upload.md](routes/media/upload.md) |
| DELETE | `/api/media/uploads/batch/{batchId}` | Bearer + `ACCOUNT_ACTIVE`; chủ batch | Xoá batch upload khi user bấm X trên file trong phase hiện tại | [routes/media/delete-batch.md](routes/media/delete-batch.md) |
| POST | `/api/media/uploads/batch/{batchId}/attach` | Bearer + `ACCOUNT_ACTIVE`; chủ batch | Gắn batch upload, chặn Hủy xoá asset đã dùng | [routes/media/attach-batch.md](routes/media/attach-batch.md) |

Ghi chú:

- Mọi route đều có một dòng trong bảng này. Nơi giữ phần usage / actor / luồng / lỗi chính của route:
  - **file riêng trong `routes/<module>/`** (bắt đầu từ template [templates/route.md](templates/route.md)) cho route
    của `booking`, `auth`/`identity`, `media`, `payment`, `pricing`, `facility`, `notification`, `handover` và
    `/api/health`;
  - **doc module** cho route của `tenancy` + `overdue` ([tenancy/rentals.md](tenancy/rentals.md)), `checkout`
    ([checkout/return.md](checkout/return.md)), `support` ([support/requests.md](support/requests.md)),
    `backoffice` ([backoffice/overview.md](backoffice/overview.md)), `staffing` ([backoffice/hr.md](backoffice/hr.md)) và
    `chat` ([chat/assistant.md](chat/assistant.md)).
    Doc module liệt kê từng route nó sở hữu với method + path + ai gọi được + lỗi chính.
- Thêm route mới → thêm một dòng vào bảng này và mô tả route ở đúng nơi theo module như trên.
- Quy ước chung (base URL, error body, 401 vs 403): xem
  [index.md › Quy ước data contract](index.md#quy-ước-data-contract).
