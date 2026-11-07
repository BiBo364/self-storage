---
purpose: Kho đang thuê sau bàn giao (Flow 3) và quá hạn (Flow 6) as-built — tiền thuê hằng tháng, gia hạn, yêu cầu trả kho, phí quá hạn, tạm khoá truy cập.
---
# Kho đang thuê, tiền thuê, gia hạn, quá hạn

Spec Flow 3 và Flow 6 chỉ có tiêu đề; trang này là hành vi as-built của hai flow. Muốn đổi thì mở `behavior-gap`.
Trang này cũng là contract của các route bên dưới (không có file riêng trong `routes/`); field chi tiết nằm ở
Swagger trên `tenancy/controller/TenancyController.java` và `overdue/controller/OverdueController.java`.

Code:
- `tenancy/application/TenancyService.java` — kho đang thuê, gia hạn, yêu cầu trả kho, FM phân công;
- `overdue/application/OverdueService.java` + `OverdueJob.java` — nhắc sắp hết hạn, phí quá hạn, tạm khoá / mở lại
  truy cập;
- `overdue/application/RentBillingService.java` — hoá đơn tiền thuê hằng tháng.

Hoá đơn đi qua `booking/application/ContractInvoiceService.java`, hợp đồng qua
`handover/application/RentalContractService.java`.

## Route

Mọi route cần bearer token + `ACCOUNT_ACTIVE`; prefix quyết role (`/api/customer/**` → `CUSTOMER`,
`/api/fm/**` → `FM`, `/api/staff/**` → `FS`, sai role → 403). Lỗi nghiệp vụ trả `{message, timestamp}` qua
`TenancyExceptionHandler`: 400 dữ liệu sai, 404 không thấy (kể cả tài nguyên của khách khác), 409 xung đột trạng thái.

| Method | Path | Ai gọi | Lỗi chính |
|---|---|---|---|
| GET | `/api/customer/rentals` | khách — hợp đồng `Active` của chính mình | — (không có thì `data: []`) |
| GET | `/api/customer/rentals/{contractId}` | khách chủ hợp đồng (mọi trạng thái hợp đồng) | 404 "Không tìm thấy hợp đồng thuê" (không có / của khách khác) |
| POST | `/api/customer/rentals/{contractId}/extensions` body `{months}` | khách chủ hợp đồng | 400 thiếu `months` / ngoài 1–`extension.max_months`; 404; 409 hợp đồng không `Active`, đang có yêu cầu trả kho mở, đã có hoá đơn gia hạn `Unpaid` |
| POST | `/api/customer/rentals/{contractId}/return-request` body `{preferredDate, note?}` | khách chủ hợp đồng | 400 `preferredDate` trước hôm nay / quá 30 ngày tới, `note` > 1000 ký tự; 404; 409 hợp đồng không `Active`, đã có yêu cầu đang xử lý |
| GET | `/api/customer/return-requests` | khách — mọi yêu cầu trả kho của mình, mới nhất trước | — |
| POST | `/api/customer/return-requests/{id}/cancel` | khách chủ yêu cầu | 404 "Không tìm thấy yêu cầu trả kho"; 409 yêu cầu đã đóng, FS đã xác nhận khách đến |
| GET | `/api/fm/return-requests?facilityId=` | FM có `rental_order.read` tại cơ sở — yêu cầu `Pending`/`Assigned`, cũ nhất trước | 403 thiếu quyền / ngoài phạm vi cơ sở |
| POST | `/api/fm/return-requests/{id}/assign` body `{staffId}` | FM có `appointment.assign` tại cơ sở của yêu cầu | 400 FS không thuộc cơ sở hoặc không hoạt động; 403; 404; 409 yêu cầu đã đóng, buổi trả kho đã bắt đầu, không còn khung trống trong 14 ngày |
| GET | `/api/staff/return-requests` | FS — yêu cầu `Assigned` phân cho chính mình | — |
| GET | `/api/fm/overdue-contracts?facilityId=` | FM có `rental_order.read` tại cơ sở | 403 thiếu quyền / ngoài phạm vi cơ sở |

Route trả kho tại cơ sở (`…/checkout`) thuộc [checkout/return.md](../checkout/return.md).

## Kho đang thuê

`GET /api/customer/rentals` trả mọi hợp đồng `Active` của khách, gồm:
- hoá đơn của hợp đồng, quyền truy cập đã cấp, yêu cầu trả kho đang mở;
- `daysLeft`, `overdue` (đã qua `end_date`);
- yêu cầu trả kho (`ReturnRequestView`) có `appointmentStart` và `appointmentArrivedAt` (mốc FS xác nhận khách đến
  buổi trả kho; `null` = chưa đến / chưa có lịch);
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
6. Hoá đơn gia hạn đã tạo trước khi khách gửi yêu cầu trả kho **vẫn thanh toán được** sau khi yêu cầu được gửi:
   `onExtensionPaid` chỉ kiểm hợp đồng còn `Active`, nên trả xong thì `end_date` vẫn được cộng. Còn `Unpaid` lúc FS
   chốt trả kho thì hoá đơn bị gộp vào quyết toán ([checkout/return.md](../checkout/return.md)).

## Yêu cầu trả kho

| Bước | Ai | Kết quả |
|---|---|---|
| Gửi yêu cầu | Khách, hợp đồng `Active` | `ReturnRequest` `Pending`, `preferred_date` trong 30 ngày tới; FM nhận `RETURN_REQUESTED`; audit `RETURN_REQUESTED` |
| Phân công | FM (`appointment.assign` + facility scope), FS đang hoạt động của cơ sở | lịch `RETURN` (`Pending`, có `staff_id`) ở khung trống đầu tiên từ `preferred_date`, tìm tối đa 14 ngày; `Assigned`; khách + FS nhận `RETURN_SCHEDULED`; ngày khách chọn đã kín và lịch bị dời sang ngày khác → khách nhận thêm `RETURN_DATE_SHIFTED` (ngày giờ mới); audit `RETURN_ASSIGNED` |
| Phân công lại | FM | lịch cũ chưa có khách đến → `Canceled`, tạo lịch mới |
| Huỷ | Khách | chỉ khi FS chưa xác nhận khách đến; lịch `Pending` → `Canceled`; audit `RETURN_CANCELED` |
| Hoàn tất | checkout | `Completed` + `completed_at`; audit `RETURN_COMPLETED` |

- Lịch `RETURN` dùng chung ba khung cố định với `CHECKIN`: một lịch mỗi khung mỗi cơ sở, tính cả hai loại.
- Lịch `RETURN` không có no-show: khách đến trễ sau `end_at` FS vẫn xác nhận đến được; FS không huỷ được lịch `RETURN`
  qua route huỷ lịch (409) — FM phân công lại hoặc khách huỷ yêu cầu
  ([staff-appointment-arrive.md](../routes/booking/staff-appointment-arrive.md)).
- Mỗi hợp đồng chỉ có một yêu cầu `Pending`/`Assigned` (partial unique index V20).
- Thứ tự khoá: đơn → hợp đồng → yêu cầu trả kho.

Trả kho tại cơ sở: xem [checkout/return.md](../checkout/return.md).

## Thu tiền thuê hằng tháng

Hoá đơn tháng đầu (`contract.prepaid_months` tháng) thu lúc bàn giao
([handover/records.md](../handover/records.md)). Các tháng sau của kỳ thuê do `RentBillingService.bill` lập, chạy
trong `OverdueJob` (mỗi giờ) trên mọi hợp đồng `Active`, mỗi hợp đồng một transaction, khoá đơn → hợp đồng:

- **Tháng cần lập** = `period` − số tháng của hoá đơn `Extension` đã `Paid` (gia hạn trả trước và nối vào cuối kỳ).
- **Tháng đã lập** = tổng số tháng của mọi hoá đơn `Rental` **không** `Canceled` của hợp đồng — kể cả tháng đầu và
  hoá đơn `Expired` (nợ vẫn tính). Số tháng một hoá đơn = `amount / monthly_price` làm tròn, tối thiểu 1.
- Còn tháng chưa lập (tháng thứ `k = đã lập + 1`, bắt đầu `monthStart = start_date + (k − 1) tháng`) và hôm nay ≥
  `monthStart − rent.invoice_lead_days` (5) → lập **một** hoá đơn mỗi lượt:
  - `type = Rental`, mã `INV-RNT-{facility}-{YYMMDD}-{rand}`, `amount = monthly_price`;
  - title "Tiền thuê tháng k/period — hợp đồng …", mô tả kỳ `monthStart` – `monthStart + 1 tháng − 1 ngày`;
  - `due_date` = `monthStart`; cron lập muộn (tháng đã bắt đầu) thì tối thiểu hôm nay + 3 ngày;
  - audit `INVOICE_CREATED`; khách nhận `RENT_INVOICE_ISSUED` (kỳ, số tiền, hạn, link `/invoices`).
- Không lập tháng mới khi hợp đồng đang có yêu cầu trả kho mở.
- Hoá đơn `INV-RNT` quá hạn (cron booking `Expired`, `ContractInvoiceExpired` loại `Rental`) trên hợp đồng `Active` →
  FM nhận `RENT_UNPAID` để liên hệ khách; nợ giữ nguyên trên hoá đơn `Expired`, không lập lại. Hoá đơn tháng đầu quá
  hạn xảy ra khi hợp đồng còn `Signed` nên không vào nhánh này (handover đóng phiên bàn giao).
- Hoá đơn tiền thuê còn `Unpaid` lúc FS chốt trả kho được gộp vào quyết toán.

## Quá hạn (Flow 6)

`OverdueJob` chạy mỗi giờ (`overdue.job.fixed-delay-ms`), mỗi hợp đồng một transaction, khoá đơn → hợp đồng → hoá
đơn. Mỗi lượt chạy lần lượt: nhắc sắp hết hạn → đồng bộ phí quá hạn (kèm tạm khoá truy cập) → mở lại truy cập →
lập hoá đơn tiền thuê ([trên](#thu-tiền-thuê-hằng-tháng)).

| Điều kiện | Kết quả |
|---|---|
| `end_date` trong `contract.expiry_reminder_days` (7) ngày tới | khách nhận `CONTRACT_EXPIRING_SOON`, mỗi đơn một lần |
| Hợp đồng `Active`, `end_date < hôm nay` | lần đầu: khách + FM nhận `CONTRACT_OVERDUE` (mỗi đơn một lần) |
| Như trên, sau `overdue.grace_days` (3) ngày | phí = `ngày tính phí × overdue.fee_per_day` (20.000đ), trừ phần phí quá hạn đã `Paid` |
| Quá hạn ≥ `overdue.escalation_days` (30) ngày, chưa có yêu cầu trả kho | tạm khoá truy cập — [bên dưới](#quá-hạn-kéo-dài--tạm-khoá-truy-cập) |

`CONTRACT_EXPIRING_SOON` và `CONTRACT_OVERDUE` gửi **một lần mỗi đơn** (dedup qua `OrderNotification` theo đơn + type):
khách gia hạn rồi lại sắp hết hạn / lại quá hạn thì không nhận nhắc lần nữa.

Phí cộng dồn nằm trên đúng một hoá đơn `Penalty` mã `INV-OVD-…` còn `Unpaid`:
- **Tạo / cập nhật:** cron tạo mới hoặc cập nhật số tiền (audit `OVERDUE_FEE_ACCRUED`), hạn `now + 7 ngày`.
- **Ngày tính phí** = `(ngày dừng − end_date) − grace_days`. Ngày dừng:
  - mặc định là hôm nay — kể cả khi khách đã gửi yêu cầu trả kho (gửi yêu cầu không dừng phí);
  - yêu cầu trả kho đang mở có lịch `RETURN` mà FS **đã xác nhận khách đến** (`appointmentArrivedAt`) → ngày bắt đầu
    của buổi đó (`appointmentStart`), kể cả khi biên bản còn chờ (khoang còn đồ / chờ trả phần thiếu);
  - lịch chưa tới hoặc khách không đến → vẫn hôm nay; ngày dừng không bao giờ vượt hôm nay.
- **Gia hạn phủ kỳ quá hạn** (`end_date` mới ≥ hôm nay): còn phải thu ≤ 0 nên hoá đơn đang mở bị `Canceled`. Cron
  quét cả các hợp đồng đang có hoá đơn `INV-OVD` mở để bắt trường hợp này.
- **Đang có phiên thanh toán mở:** không đổi số tiền, vì cổng đã nhận số cũ.
- **Khách trả phí quá hạn rồi vẫn tiếp tục quá hạn:** hoá đơn mới chỉ thu phần chênh.
- **Khi trả kho:** hoá đơn quá hạn còn `Unpaid` được gộp vào quyết toán. Đã có hoá đơn quyết toán (`INV-CLN` /
  `INV-DMG` / `INV-KEY` / `INV-PEN` chưa `Canceled`) thì cron không phát hành lại `INV-OVD` cho hợp đồng.

### Quá hạn kéo dài — tạm khoá truy cập

Trong bước đồng bộ phí (`OverdueService.accrue` → `escalate`): hợp đồng `Active`, số ngày quá hạn (tính từ
`end_date` tới hôm nay) ≥ `overdue.escalation_days` (30) và **chưa** có yêu cầu trả kho mở →
`RentalContractService.suspendAccess`:
- mã khoá số `Active` → `Revoked` (audit `ACCESS_KEY_REVOKED`, `reason` "Tạm khoá do quá hạn");
- ghi `rental_contracts.access_suspended_at`; audit `CONTRACT_ACCESS_SUSPENDED` (`accessSuspendedAt`, `daysOverdue`);
- khách + FM nhận `CONTRACT_ACCESS_SUSPENDED`; FM được nhắc liên hệ khách, không phản hồi thì xử lý thu hồi theo quy
  trình của cơ sở;
- idempotent: hợp đồng đã khoá thì không làm lại, không gửi lại.

Chìa cơ (`PhysicalKey`) không thu hồi từ xa được — FM xử lý tại chỗ. Trong lúc khoá, khách lấy mã mới
(`POST /api/customer/contracts/{id}/access-code`) bị 409.

**Mở lại** (`OverdueService.restoreIfCleared`, cùng lượt cron, quét hợp đồng `Active` đang khoá): hợp đồng không còn
quá hạn (`end_date` ≥ hôm nay — gia hạn đã phủ kỳ quá hạn) **và** không còn hoá đơn `INV-OVD` `Unpaid` → xoá
`access_suspended_at`, audit `CONTRACT_ACCESS_RESTORED`, khách nhận `CONTRACT_ACCESS_RESTORED`. Mã cũ đã thu hồi nên
khách tự lấy mã mới trong trang kho đang thuê.

FM xem danh sách hợp đồng quá hạn của cơ sở (`GET /api/fm/overdue-contracts?facilityId=`), gồm:
- số ngày quá hạn và số ngày tính phí;
- tổng phí đã cộng dồn và hoá đơn đang mở;
- hợp đồng đã có yêu cầu trả kho chưa.

## Policy

| Key | Mặc định | Migration |
|---|---:|---|
| `extension.max_months` | 12 | V20 |
| `extension.invoice_due_days` | 3 | V20 |
| `contract.expiry_reminder_days` | 7 | V20 |
| `overdue.grace_days` | 3 | V20 |
| `overdue.fee_per_day` | 20000 | V20 |
| `rent.invoice_lead_days` | 5 | V22 |
| `overdue.escalation_days` | 30 | V22 |

## Test

`tenancy/PostRentalIntegrationTest.java` (Postgres) và `tenancy/controller/PostRentalControllerTest.java` (HTTP).
