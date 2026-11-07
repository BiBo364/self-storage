---
purpose: Kho đang thuê sau bàn giao (Flow 3) và quá hạn (Flow 6) as-built — gia hạn, yêu cầu trả kho, phí quá hạn.
---
# Kho đang thuê, gia hạn, quá hạn

Spec Flow 3 và Flow 6 chỉ có tiêu đề. Trang này ghi thiết kế đã code trên nhánh full-demo; muốn đổi thì mở
`behavior-gap`.

Code:
- `tenancy/application/TenancyService.java` — kho đang thuê, gia hạn, yêu cầu trả kho, FM phân công;
- `overdue/application/OverdueService.java` + `OverdueJob.java` — nhắc sắp hết hạn, phí quá hạn.

Hoá đơn đi qua `booking/application/ContractInvoiceService.java`, hợp đồng qua
`handover/application/RentalContractService.java`.

## Kho đang thuê

`GET /api/customer/rentals` trả mọi hợp đồng `Active` của khách, gồm:
- hoá đơn của hợp đồng, quyền truy cập đã cấp, yêu cầu trả kho đang mở;
- `daysLeft`, `overdue` (đã qua `end_date`);
- `canExtend`: hợp đồng `Active`, không có yêu cầu trả kho mở, không có hoá đơn gia hạn chờ trả;
- `canRequestReturn`: hợp đồng `Active`, không có yêu cầu trả kho mở.

## Gia hạn

1. Khách chọn `months` (1 – `extension.max_months` = 12). Bị chặn khi đã có yêu cầu trả kho mở hoặc còn hoá đơn
   gia hạn `Unpaid` (409).
2. Hệ thống tạo hoá đơn `Extension`:
   - mã `INV-EXT-{facility}-{YYMMDD}-{rand}`;
   - `amount = monthly_price × months`, giá chốt lúc ký nên không đổi;
   - hạn `now + extension.invoice_due_days` (3 ngày);
   - gắn `contract_id`.
3. Khách thanh toán qua `/api/invoices/{id}/pay`. Hoá đơn gắn hợp đồng của đơn đã `Done` bỏ qua các kiểm tra trạng
   thái / hạn của đơn (xem [deposit-payment.md](../booking/deposit-payment.md)).
4. Cổng xác nhận → `ContractInvoicePaid(type = Extension)` → `ExtensionPaidListener`, cùng transaction chốt thanh
   toán:
   - `months = amount / monthly_price`;
   - `end_date += months`, `period += months`;
   - audit `CONTRACT_EXTENDED`; khách nhận `CONTRACT_EXTENDED`.
5. Hoá đơn gia hạn quá hạn → cron booking chuyển `Expired`; hợp đồng không đổi, khách tạo lại.

## Yêu cầu trả kho

| Bước | Ai | Kết quả |
|---|---|---|
| Gửi yêu cầu | Khách, hợp đồng `Active` | `ReturnRequest` `Pending`, `preferred_date` trong 30 ngày tới; FM nhận `RETURN_REQUESTED`; audit `RETURN_REQUESTED` |
| Phân công | FM (`appointment.assign` + facility scope), FS đang hoạt động của cơ sở | lịch `RETURN` (`Pending`, có `staff_id`) ở khung trống đầu tiên từ `preferred_date`, tìm tối đa 14 ngày; `Assigned`; khách + FS nhận `RETURN_SCHEDULED`; audit `RETURN_ASSIGNED` |
| Phân công lại | FM | lịch cũ chưa có khách đến → `Canceled`, tạo lịch mới |
| Huỷ | Khách | chỉ khi FS chưa xác nhận khách đến; lịch `Pending` → `Canceled`; audit `RETURN_CANCELED` |
| Hoàn tất | checkout | `Completed` + `completed_at`; audit `RETURN_COMPLETED` |

- Lịch `RETURN` dùng chung ba khung cố định với `CHECKIN`: một lịch mỗi khung mỗi cơ sở, tính cả hai loại.
- Mỗi hợp đồng chỉ có một yêu cầu `Pending`/`Assigned` (partial unique index V20).
- Thứ tự khoá: đơn → hợp đồng → yêu cầu trả kho.

Trả kho tại cơ sở: xem [checkout/return.md](../checkout/return.md).

## Quá hạn (Flow 6)

`OverdueJob` chạy mỗi giờ (`overdue.job.fixed-delay-ms`), mỗi hợp đồng một transaction, khoá đơn → hợp đồng → hoá
đơn.

| Điều kiện | Kết quả |
|---|---|
| `end_date` trong `contract.expiry_reminder_days` (7) ngày tới | khách nhận `CONTRACT_EXPIRING_SOON`, mỗi đơn một lần |
| Hợp đồng `Active`, `end_date < hôm nay` | lần đầu: khách + FM nhận `CONTRACT_OVERDUE` (mỗi đơn một lần) |
| Như trên, sau `overdue.grace_days` (3) ngày | phí = `ngày tính phí × overdue.fee_per_day` (20.000đ), trừ phần phí quá hạn đã `Paid` |

Phí cộng dồn nằm trên đúng một hoá đơn `Penalty` mã `INV-OVD-…` còn `Unpaid`:
- **Tạo / cập nhật:** cron tạo mới hoặc cập nhật số tiền (audit `OVERDUE_FEE_ACCRUED`), hạn `now + 7 ngày`.
- **Ngày tính phí** = `(ngày dừng − end_date) − grace_days`. Ngày dừng là hôm nay, hoặc ngày khách gửi yêu cầu trả
  kho nếu có yêu cầu đang mở.
- **Gia hạn phủ kỳ quá hạn** (`end_date` mới ≥ hôm nay): còn phải thu ≤ 0 nên hoá đơn đang mở bị `Canceled`. Cron
  quét cả các hợp đồng đang có hoá đơn `INV-OVD` mở để bắt trường hợp này.
- **Đang có phiên thanh toán mở:** không đổi số tiền, vì cổng đã nhận số cũ.
- **Khách trả phí quá hạn rồi vẫn tiếp tục quá hạn:** hoá đơn mới chỉ thu phần chênh.
- **Khi trả kho:** hoá đơn quá hạn còn `Unpaid` được gộp vào quyết toán.

FM xem danh sách hợp đồng quá hạn của cơ sở (`GET /api/fm/overdue-contracts?facilityId=`), gồm:
- số ngày quá hạn và số ngày tính phí;
- tổng phí đã cộng dồn và hoá đơn đang mở;
- hợp đồng đã có yêu cầu trả kho chưa.

## Policy (V20)

| Key | Mặc định |
|---|---:|
| `extension.max_months` | 12 |
| `extension.invoice_due_days` | 3 |
| `contract.expiry_reminder_days` | 7 |
| `overdue.grace_days` | 3 |
| `overdue.fee_per_day` | 20000 |

## Test

`tenancy/PostRentalIntegrationTest.java` (Postgres) và `tenancy/controller/PostRentalControllerTest.java` (HTTP).
