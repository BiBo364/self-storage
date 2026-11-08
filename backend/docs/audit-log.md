---
purpose: Hợp đồng tích hợp AuditLog cho module gọi — gọi ở đâu, truyền gì, không ghi gì; bảng call site hiện có.
---
# Ghi AuditLog — hợp đồng cho module gọi

Module `audit` (`backend/src/main/java/vn/lemar/selfstorage/audit/`) là hạ tầng ghi vết;
module nghiệp vụ chịu trách nhiệm gọi đúng chỗ. Trang này là checklist khi nối audit ở PR của
flow. Catalog action/entity đầy đủ để tham khảo flow ở `specs/db-table-draft.md` › AuditLog;
trang này ghi các action **đã nối trong code** ở bảng call site bên dưới.

## Module gọi làm gì

1. Khai `allowedDependencies = {"audit::application"}` trong `package-info` của module mình.
2. Inject `vn.lemar.selfstorage.audit.application.AuditRecorder` vào service.
3. Gọi **trong cùng transaction** với thay đổi nghiệp vụ, tại service — không ở controller.
   Propagation `MANDATORY`: gọi ngoài transaction là lỗi wiring (`IllegalTransactionStateException`,
   fail-fast). Rollback nghiệp vụ thì bản ghi audit rollback theo.
4. Một entity bị thay đổi = một lần gọi `record(...)`. Thao tác đổi N entity → N lần gọi
   trong cùng transaction (ví dụ `RENTAL_ORDER_CANCELED` ghi 4 entity).
5. Chọn `AuditAction` đúng catalog và `AuditEntityType` khớp action — sai cặp là
   `IllegalStateException` (lỗi lập trình, 500), đã có unit test.
6. `entityId`: truyền id dạng chuỗi, không rỗng — `String.valueOf(id)`.
7. `actorAccountId`: account thực hiện; `null` khi do hệ thống/cron/IPN.
8. `reason`: bắt buộc với thao tác override (nhập lý do); các case khác để null.
9. `oldValue`/`newValue`: Map/POJO trạng thái trước–sau (serialize thành JSONB); null khi
   không có giá trị tương ứng (ví dụ tạo mới).
10. Không đưa vào audit: mật khẩu, secret, số thẻ, raw payment payload; không audit thao tác
    đọc; xác minh email không ghi audit — dùng `Account.email_verified_at`.

Hạ tầng append-only: không có API sửa/xóa bản ghi; module gọi chỉ ghi thêm.

## Gọi trông như thế nào

```java
// Tạo tài khoản — actor là chính account vừa được tạo
auditRecorder.record(new AuditEntry(AuditAction.ACCOUNT_REGISTERED, AuditEntityType.ACCOUNT,
        String.valueOf(account.getId()), null, null, null, account.getId()));

// Thao tác nhiều entity, có old/new + lý do + actor (ví dụ hủy đơn — Flow 6)
auditRecorder.record(new AuditEntry(AuditAction.RENTAL_ORDER_CANCELED, AuditEntityType.RENTAL_ORDER,
        String.valueOf(orderId), Map.of("status", "Pending"), Map.of("status", "Canceled"),
        "quá hạn thanh toán", actorId));
```

## Khi nối thì test gì

- Integration (Testcontainers) assert bản ghi tạo đúng action/entity/actor — mẫu:
  `backend/src/test/java/vn/lemar/selfstorage/audit/AuditRecorderIntegrationTest.java`.
- `actor_account_id` có FK → accounts: test phải dùng account có thật trong DB (id seed),
  không dùng id tùy ý.

## Call site hiện có

| Module | Action | Entity | Nối ở |
|---|---|---|---|
| `identity` | `ACCOUNT_REGISTERED` | `Account` | `identity/application/RegistrationService.verifyEmail` — tạo account sau khi xác minh |
| `booking` | `RENTAL_REQUEST_APPROVED`, `RENTAL_REQUEST_REJECTED` | `RentalRequest` | `booking/application/RentalRequestService.approve/reject` |
| `booking` | `RENTAL_REQUEST_CLAIMED` | `RentalRequest` | `booking/application/RentalRequestConversionService.claimForVerifiedAccount` — actor = customer vừa xác minh; claim cũng ghi `RENTAL_ORDER_CREATED` + `PROPOSAL_CREATED` (System) |
| `booking` | `RENTAL_REQUEST_CONVERTED`, `RENTAL_ORDER_CREATED`, `PROPOSAL_CREATED` | `RentalRequest`, `RentalOrder`, `ProposalFeedback` | `booking/application/RentalRequestConversionService.convert` — trigger duyệt (`CONVERTED` chỉ nhánh duyệt); order/proposal tạo ở cả hai trigger; actor = System |
| `booking` | `PROPOSAL_AGREED`, `PROPOSAL_REJECTED`, `PROPOSAL_REPROPOSED`, `PROPOSAL_CANCELED` | `ProposalFeedback` | `booking/application/ProposalService.agree/reject/repropose` — actor = customer/FM, một bản ghi mỗi lần đổi trạng thái |
| `booking` | `INVOICE_CREATED`, `INVOICE_CANCELED` | `Invoice` | `booking/application/DepositInvoiceIssuer`, `ProposalService.cancelUnpaidDepositInvoices` và `RentalOrderCancellation` — actor = customer/FM |
| `booking` | `RENTAL_ORDER_CANCELED` | `RentalOrder`, `ProposalFeedback`, `StorageUnit` | `booking/application/RentalOrderCancellation.cancel` — một bản ghi mỗi entity, cùng lý do huỷ |
| `booking` | `DEPOSIT_REFUND_REQUIRED` | `RentalOrder` | `booking/application/ProposalService.issueDeposit` — đổi khoang sau khi cọc dư; actor = customer, FM nhận notification hoàn tiền thủ công |
| `booking` | `RENTAL_ORDER_DEPOSITED` | `RentalOrder` | `ProposalService.issueDeposit` (`markDepositedIfPending`) — đơn `Pending` đã trả cọc nhưng khoang bị giữ trước, khách đồng ý khoang thay thế và cọc đã đủ; `reason` "Cọc đã thu, giữ khoang thay thế"; actor = customer |
| `booking` | `MANUAL_REFUND_RECORDED` | `PaymentTransaction` | `booking/application/ManualRefundService.record` — FM ghi nhận đã hoàn tiền cho khách (`new_value`: `invoiceId`, `invoiceCode`, `amount`; `reason` = ghi chú); actor = FM |
| `booking` | `UNIT_CHANGE_RELEASED` | `StorageUnit` | `booking/application/ProposalService.releaseReplacedUnit` — nhả khoang cũ khi khách đồng ý khoang mới sau khi đã cọc; actor = customer |
| `booking` | `FS_ASSIGNED` | `Appointment`, `RentalOrder` | `booking/application/AppointmentService.assignStaff` — hai bản ghi: `staffId` (Appointment) + `Scheduled → InProgress` (RentalOrder); actor = FM |
| `booking` | `APPOINTMENT_CANCELED` | `Appointment` | `booking/application/RentalOrderCancellation.cancel` — lịch `Pending` bị huỷ theo cancellation cascade; actor = System |
| `booking` | `APPOINTMENT_CANCELED` | `Appointment` | `booking/application/StaffAppointmentActionService.cancel` — FS chủ động huỷ lịch `Pending`; actor = FS |
| `booking` | `APPOINTMENT_CANCELED_NO_SHOW` | `Appointment` | `booking/application/AppointmentNoShowService.cancelIfDue` — cron no-show (check-in Pending quá `end_at`); actor = System |
| `booking` | `APPOINTMENT_ARRIVED` | `Appointment` | `booking/application/StaffAppointmentActionService.arrive` — FS xác nhận khách đến, `Pending → Done`; actor = FS |
| `booking` | `PAYMENT_SUCCEEDED`, `INVOICE_PAID`, `RENTAL_ORDER_DEPOSITED`, `STORAGE_UNIT_RESERVED` | `PaymentTransaction`, `Invoice`, `RentalOrder`, `StorageUnit` | `booking/application/InvoicePaymentSettlement.onPaymentSucceeded` — cổng xác nhận thu tiền (IPN/querydr); cọc bổ sung trên đơn đã giữ khoang không ghi `RENTAL_ORDER_DEPOSITED`, trên đơn còn `Pending` (cọc đã trả, khoang cũ bị giữ trước) thì có; actor = System |
| `booking` | `INVOICE_CANCELED` | `Invoice` | `booking/application/InvoicePaymentService.start` — khoang đã có người cọc / bảo trì lúc bấm thanh toán; actor = customer |
| `booking` | `PAYMENT_FAILED` | `PaymentTransaction` | `booking/application/PaymentFailureAudit` — giao dịch đóng `Failed` (cổng báo lỗi, phiên hết hạn, phiên bị huỷ); actor = System |
| `booking` | `INVOICE_CANCELED` | `Invoice` | `booking/application/CompetingInvoiceCanceller` — khoang vừa có người trả cọc, hoá đơn cọc khác của khoang bị chặn; actor = System |
| `booking` | `INVOICE_EXPIRED`, `RENTAL_REQUEST_EXPIRED` | `Invoice`, `RentalRequest` | `booking/application/BookingExpiryService` — cron Flow 1; actor = System |
| `booking` | `APPOINTMENT_CREATED` | `Appointment`, `RentalOrder` | `booking/application/AppointmentBookingService.book` — hai bản ghi (lịch mới + đơn `Deposited → Scheduled`); actor = customer |
| `handover` | `HANDOVER_RECORD_CREATED` | `HandoverRecord` | `handover/application/CheckinAppointmentCreatedListener` — mở biên bản khi khách đặt lịch; actor = System |
| `handover` | `HANDOVER_CANCELED`, `CONTRACT_CANCELED` | `HandoverRecord`, `RentalContract` | `handover/application/HandoverService.cancelByAppointmentId`/`cancelByOrderId` — một bản ghi mỗi entity; actor = System |
| `handover` | `IDENTITY_VERIFIED`, `START_DATE_OVERRIDE_REQUESTED`, `CONTRACT_SIGNED` | `HandoverRecord`, `RentalContract` | `handover/application/HandoverWorkflowService` — FS xác minh danh tính (cả `Failed`), đề nghị đổi mốc, ký hợp đồng; actor = FS |
| `handover` | `UNIT_INSPECTED`, `HANDOVER_REJECTED` | `HandoverRecord` | `HandoverWorkflowService.agreeInspection` / `reject` — khách đồng ý / từ chối khoang; actor = customer |
| `handover` | `INSPECTION_CORRECTED` | `HandoverRecord` | `HandoverWorkflowService.correctInspection` — FS sửa hiện trạng sau khi khách đã đồng ý (old/new `inspectionNotes` + `photoCount`, `reason` bắt buộc); actor = FS |
| `handover` | `START_DATE_OVERRIDE_APPROVED`, `START_DATE_OVERRIDE_REJECTED` | `RentalContract` | `HandoverWorkflowService.reviewStartDateOverride`; actor = FM |
| `handover` | `ACCESS_KEY_ISSUED`, `STORAGE_UNIT_RENTED`, `CONTRACT_ACTIVATED`, `HANDOVER_COMPLETED` | `UnitAccessKey`, `StorageUnit`, `RentalContract`, `HandoverRecord` | `handover/application/HandoverCompletion.complete` — chốt bàn giao; actor = khách (mã số) hoặc FS (giao chìa) |
| `handover` | `ACCESS_KEY_REVOKED`, `ACCESS_KEY_ISSUED` | `UnitAccessKey` | `HandoverCompletion.rotateCode` — khách lấy mã khóa số mới; actor = customer |
| `booking` | `RENTAL_ORDER_DONE` | `RentalOrder` | `booking/application/HandoverOrderService.complete` — gọi từ chốt bàn giao, cùng actor |
| `booking` | `INVOICE_CREATED` | `Invoice` | `DepositInvoiceIssuer.issueFirstMonth` — hoá đơn tháng đầu `INV-RNT` khi FS ký hợp đồng; actor = FS |
| `booking` | `INVOICE_CANCELED` | `Invoice` | `HandoverOrderService.closeContractInvoices` — hợp đồng `Signed` bị huỷ, hoá đơn tháng đầu `Unpaid`; actor = System |
| `booking` | `STORAGE_UNIT_RESERVED` | `StorageUnit` | `ProposalService.agree` — đổi khoang sau khi cọc, cọc đã đủ; actor = customer |
| `handover` | `CONTRACT_EXTENDED` | `RentalContract` | `RentalContractService.extend` — hoá đơn gia hạn `Paid`; actor = System |
| `handover` | `CONTRACT_ENDED`, `ACCESS_KEY_REVOKED` | `RentalContract`, `UnitAccessKey` | `RentalContractService.end` / `revokeAll` — trả kho; thiếu chìa ghi `status = Lost` trong `new_value`; actor = FS (hoặc System khi hoàn tất do hoá đơn phần thiếu `Paid` / `Expired`) |
| `handover` | `CONTRACT_ACCESS_SUSPENDED`, `ACCESS_KEY_REVOKED` | `RentalContract`, `UnitAccessKey` | `RentalContractService.suspendAccess` — cron quá hạn kéo dài (≥ `overdue.escalation_days`): thu hồi mã khoá số (`reason` "Tạm khoá do quá hạn"), `new_value` có `accessSuspendedAt`, `daysOverdue`; actor = System |
| `handover` | `CONTRACT_ACCESS_RESTORED` | `RentalContract` | `RentalContractService.restoreAccess` — cron thấy không còn `INV-OVD` `Unpaid` và hợp đồng hết quá hạn hoặc có yêu cầu trả kho mở; actor = System |
| `tenancy` | `RETURN_REQUESTED`, `RETURN_CANCELED` | `ReturnRequest` | `tenancy/application/TenancyService` — khách gửi / huỷ; actor = customer |
| `tenancy` | `RETURN_ASSIGNED`, `RETURN_COMPLETED` | `ReturnRequest` | `TenancyService.assign` (actor = FM) / `completeReturn` (actor = System) |
| `tenancy` | `RETURN_NO_SHOW` | `ReturnRequest` | `TenancyService.handleReturnNoShow` — cron no-show trả kho; `new_value` có `status` (`Pending` / `Canceled`), `noShowCount`, `appointmentId`; actor = System |
| `booking` | `APPOINTMENT_CREATED`, `APPOINTMENT_CANCELED` | `Appointment` | `ReturnAppointmentService` — lịch `RETURN` khi FM phân công / huỷ khi khách huỷ hoặc phân công lại |
| `booking` | `INVOICE_CREATED`, `INVOICE_CANCELED`, `INVOICE_PAID`, `OVERDUE_FEE_ACCRUED` | `Invoice` | `ContractInvoiceService` — hoá đơn tiền thuê tháng / gia hạn / phí quá hạn / phí dịch vụ / quyết toán trả kho (`INV-CLN`/`DMG`/`KEY`/`PEN`; `INVOICE_PAID` với `settledBy = deposit`, `reason` "Đối trừ tiền cọc" khi đối trừ cọc) |
| `checkout` | `CHECKOUT_INSPECTED`, `CHECKOUT_COMPLETED` | `CheckoutRecord` | `checkout/application/CheckoutService` — FS kiểm tra / hoàn tất trả kho; hoá đơn phần thiếu `Expired` (`onShortfallExpired`) cũng hoàn tất bằng đúng một `CHECKOUT_COMPLETED` (actor = System) — khoản nợ nằm ở hoá đơn `Expired` |
| `checkout` | `STORAGE_UNIT_MAINTENANCE`, `STORAGE_UNIT_AVAILABLE` | `StorageUnit` | `CheckoutService` (trả kho xong; `new_value.reason` "Bảo trì sau trả kho", hoặc "Bảo trì sau trả kho — thay khoá do mất chìa" khi thiếu chìa — bảo trì thủ công) / `CheckoutJobs.reopenUnits` (hết bảo trì); actor = FS / System |
| `support` | `SUPPORT_REQUEST_CREATED`, `_ASSIGNED`, `_RESOLVED`, `_CANCELED` | `SupportRequest` | `support/application/SupportService` — actor = khách / FM / FS |
| `backoffice` | `FACILITY_CREATED`, `FACILITY_UPDATED` | `Facility` | `BackofficeService` — BOM tạo / sửa / gán FM, FM/BOM đổi hình thức truy cập |
| `backoffice` | `STORAGE_UNIT_CREATED`, `STORAGE_UNIT_UPDATED`, `STORAGE_UNIT_MAINTENANCE`, `STORAGE_UNIT_AVAILABLE` | `StorageUnit` | `BackofficeService` — FM/BOM thêm / sửa khoang, bảo trì tay; `holdMaintenance` ("Giữ bảo trì") ghi `STORAGE_UNIT_MAINTENANCE` với `new_value.reason` "FM giữ bảo trì" và old/new `maintenanceStartedAt` |
| `backoffice` | `POLICY_UPDATED`, `UNIT_TYPE_UPDATED` | `Policy`, `UnitType` | `BackofficeService` — BOM đổi policy / giá loại khoang |
| `identity` | `ACCOUNT_CREATED`, `ACCOUNT_UPDATED`, `ROLE_PERMISSIONS_UPDATED` | `Account`, `Role` | `identity/application/AccountAdminService` — Admin |

## Xem thêm

- Catalog + constraint + quy tắc "một bản ghi mỗi entity": `specs/db-table-draft.md` › AuditLog.
- API + known limitation: [index.md](index.md#ghi-audit-module-audit).
- Audit ghi tường minh từ call site (không dùng AOP interceptor): interceptor không tự biết `entity_id` /
  `old_value` / `new_value` khi một thao tác đổi nhiều entity.
