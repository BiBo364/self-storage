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
| `booking` | `INVOICE_CREATED`, `INVOICE_CANCELED` | `Invoice` | `booking/application/DepositInvoiceIssuer`, `ProposalService.cancelUnpaidDepositInvoices`, `RentalOrderCancellation` và `InvoiceVoidingService.voidForUnavailableUnit` — actor = customer/FM |
| `booking` | `INVOICE_REFUND_REQUIRED` | `Invoice` | `booking/application/DepositPaymentListener.onPaymentSucceeded` — tiền đã vào nhưng unit không còn giữ được; invoice `Paid` + refund `Required` |
| `booking` | `RENTAL_ORDER_DEPOSITED` | `RentalOrder` | `booking/application/DepositPaymentListener.onPaymentSucceeded` — cọc đầu; actor = System (IPN) |
| `booking` | `STORAGE_UNIT_RESERVED` | `StorageUnit` | `booking/application/DepositPaymentListener.onPaymentSucceeded` — cọc đầu và cọc bổ sung; actor = System (IPN) |
| `payment` | `PAYMENT_SUCCEEDED`, `PAYMENT_FAILED` | `PaymentTransaction` | `payment/application/PaymentService.handleIpn`/`applySuccess`/`applyFailure` — IPN hoặc manual; `newValue` của Success ghi `gatewayTransactionNo` + `paidAt`; manual actor email nằm trong `reason`, `actorAccountId` null |
| `payment` | `PAYMENT_DUPLICATE` | `PaymentTransaction` | `payment/application/PaymentService.applyDuplicate` — nguồn xác nhận Success thứ hai cho cùng invoice; IPN hoặc manual; `newValue` ghi `gatewayTransactionNo` + `paidAt`; không phát `PaymentSucceeded` |
| `booking` | `RENTAL_ORDER_CANCELED` | `RentalOrder`, `ProposalFeedback`, `StorageUnit` | `booking/application/RentalOrderCancellation.cancel` — một bản ghi mỗi entity, cùng lý do huỷ |
| `booking` | `DEPOSIT_REFUND_REQUIRED` | `RentalOrder` | `booking/application/ProposalService.issueDeposit` — đổi khoang sau khi cọc dư; actor = customer, FM nhận notification hoàn tiền thủ công — hoàn tự động thuộc Flow 4, chưa có trong code |
| `booking` | `UNIT_CHANGE_RELEASED` | `StorageUnit` | `booking/application/ProposalService.releaseReplacedUnit` — nhả khoang cũ khi khách đồng ý khoang mới sau khi đã cọc; actor = customer |
| `booking` | `FS_ASSIGNED` | `Appointment`, `RentalOrder` | `booking/application/AppointmentService.assignStaff` — hai bản ghi: `staffId` (Appointment) + `Scheduled → InProgress` (RentalOrder); actor = FM |
| `booking` | `APPOINTMENT_CANCELED` | `Appointment` | `booking/application/RentalOrderCancellation.cancel` — lịch `Pending` bị huỷ theo cancellation cascade; actor = System |
| `booking` | `APPOINTMENT_CANCELED` | `Appointment` | `booking/application/StaffAppointmentActionService.cancel` — FS chủ động huỷ lịch `Pending`; actor = FS |
| `booking` | `APPOINTMENT_CANCELED_NO_SHOW` | `Appointment` | `booking/application/AppointmentNoShowService.cancelIfDue` — cron no-show (check-in Pending quá `end_at`); actor = System |
| `booking` | `APPOINTMENT_ARRIVED` | `Appointment` | `booking/application/StaffAppointmentActionService.arrive` — FS xác nhận khách đến, `Pending → Done`; actor = FS |

## Xem thêm

- Catalog + constraint + quy tắc "một bản ghi mỗi entity": `specs/db-table-draft.md` › AuditLog.
- API + known limitation: [index.md](index.md#ghi-audit-module-audit).
- Việc còn lại (AOP interceptor): [follow-up.md](follow-up.md).
