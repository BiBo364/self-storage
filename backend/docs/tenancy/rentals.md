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
| POST | `/api/customer/rentals/{contractId}/return-request` body `{preferredDate, note?}` | khách chủ hợp đồng | 400 `preferredDate` trước hôm nay / quá 30 ngày tới, `note` > 1000 ký tự; 404; 409 hợp đồng không `Active`, đã có yêu cầu đang xử lý, còn hoá đơn gia hạn `Unpaid` |
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
- `daysLeft`, `overdue` (hợp đồng `Active` đã qua `end_date`; hợp đồng đã kết thúc luôn `false`);
- yêu cầu trả kho (`ReturnRequestView`) có `appointmentStart` và `appointmentArrivedAt` (mốc FS xác nhận khách đến
  buổi trả kho; `null` = chưa đến / chưa có lịch);
- `canExtend`: hợp đồng `Active`, không có yêu cầu trả kho mở, không có hoá đơn gia hạn chờ trả;
- `canRequestReturn`: hợp đồng `Active`, không có yêu cầu trả kho mở, không có hoá đơn gia hạn chờ trả.

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
   - `period += months`, `end_date = start_date + period tháng − 1 ngày` (tính lại từ `start_date`, không cộng tháng
     vào `end_date` cũ — end_date cuối tháng cộng dồn bị cắt ngày: 31/1 → 28/2 → 28/3);
   - audit `CONTRACT_EXTENDED`; khách nhận `CONTRACT_EXTENDED`.
5. Hoá đơn gia hạn quá hạn → cron booking chuyển `Expired`; hợp đồng không đổi, khách tạo lại.
6. Gia hạn và trả kho loại trừ nhau theo cả hai chiều: đang có yêu cầu trả kho mở thì không tạo được hoá đơn gia
   hạn, còn hoá đơn gia hạn `Unpaid` thì không gửi được yêu cầu trả kho (409 "Đang có hoá đơn gia hạn chờ thanh
   toán…"). Khách thanh toán hoá đơn hoặc đợi nó `Expired` rồi mới gửi yêu cầu trả kho, nên hợp đồng không bị cộng kỳ
   trong lúc đang trả kho.

## Yêu cầu trả kho

| Bước | Ai | Kết quả |
|---|---|---|
| Gửi yêu cầu | Khách, hợp đồng `Active` | `ReturnRequest` `Pending`, `preferred_date` trong 30 ngày tới; FM nhận `RETURN_REQUESTED`; audit `RETURN_REQUESTED` |
| Phân công | FM (`appointment.assign` + facility scope), FS đang hoạt động của cơ sở | lịch `RETURN` (`Pending`, có `staff_id`) ở khung trống đầu tiên từ `preferred_date`, tìm tối đa 14 ngày; `Assigned`; khách + FS nhận `RETURN_SCHEDULED`; ngày khách chọn đã kín và lịch bị dời sang ngày khác → khách nhận thêm `RETURN_DATE_SHIFTED` (ngày giờ mới); audit `RETURN_ASSIGNED` |
| Phân công lại | FM | lịch cũ chưa có khách đến → `Canceled`, tạo lịch mới |
| Huỷ | Khách | chỉ khi FS chưa xác nhận khách đến; lịch `Pending` → `Canceled`; audit `RETURN_CANCELED` |
| Khách không đến | cron `ReturnNoShowJob` | [bên dưới](#khách-không-đến-buổi-trả-kho) |
| Hoàn tất | checkout | `Completed` + `completed_at`; audit `RETURN_COMPLETED` |

- Lịch `RETURN` dùng chung ba khung cố định với `CHECKIN`: một lịch mỗi khung mỗi cơ sở, tính cả hai loại.
- Khách đến trễ sau `end_at` nhưng trong `return.no_show_grace_minutes` (120) FS vẫn xác nhận đến được; quá mốc đó cron
  no-show huỷ lịch. FS không huỷ được lịch `RETURN` qua route huỷ lịch (409) — FM phân công lại hoặc khách huỷ yêu cầu
  ([staff-appointment-arrive.md](../routes/booking/staff-appointment-arrive.md)).
- Mỗi hợp đồng chỉ có một yêu cầu `Pending`/`Assigned` (partial unique index V25).
- Thứ tự khoá: đơn → hợp đồng → yêu cầu trả kho.

### Khách không đến buổi trả kho

`ReturnNoShowJob` chạy mỗi 10 phút (`tenancy.return-no-show.fixed-delay-ms`), mỗi lịch một transaction, khoá đơn → hợp
đồng → yêu cầu → lịch. Lịch `RETURN` còn `Pending` (FS chưa xác nhận khách đến) đã quá
`end_at + return.no_show_grace_minutes` và đang gắn với yêu cầu `Assigned`:
- lịch → `Canceled` lý do "Khách không đến buổi trả kho", audit `APPOINTMENT_CANCELED_NO_SHOW` (actor = System);
- `no_show_count += 1`, bỏ `appointment_id` / `assigned_staff_id`;
- chưa chạm `return.max_no_shows` (2) → yêu cầu về `Pending` — FM phân công lại như lần đầu;
- chạm ngưỡng → yêu cầu `Canceled` (`cancel_reason` "Khách không đến buổi trả kho 2 lần"); hợp đồng không còn yêu cầu
  trả kho mở nên quá hạn kéo dài lại bị tạm khoá theo điều kiện thường;
- audit `RETURN_NO_SHOW` (một bản ghi cho yêu cầu); khách + FM nhận `RETURN_NO_SHOW` (nội dung nêu rõ chờ phân công
  lại hay đã huỷ).

Phí quá hạn không cần xử lý thêm: khách không đến thì ngày dừng vẫn là hôm nay. Idempotent — lịch đã huỷ / đã có khách
đến thì bỏ qua.

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
  FM nhận `RENT_UNPAID` để liên hệ khách; nợ giữ nguyên trên hoá đơn `Expired`, không lập lại — lúc FS chốt trả kho
  hoá đơn này được gộp vào quyết toán (trừ vào cọc) như hoá đơn `Unpaid`. Hoá đơn tháng đầu quá
  hạn xảy ra khi hợp đồng còn `Signed` nên không vào nhánh này (handover đóng phiên bàn giao).
- Hoá đơn tiền thuê còn `Unpaid` hoặc đã `Expired` lúc FS chốt trả kho được gộp vào quyết toán.

## Quá hạn (Flow 6)

`OverdueJob` chạy mỗi giờ (`overdue.job.fixed-delay-ms`), mỗi hợp đồng một transaction, khoá đơn → hợp đồng → hoá
đơn. Mỗi lượt chạy lần lượt: nhắc sắp hết hạn → đồng bộ phí quá hạn (kèm tạm khoá truy cập) → mở lại truy cập →
lập hoá đơn tiền thuê ([trên](#thu-tiền-thuê-hằng-tháng)).

| Điều kiện | Kết quả |
|---|---|
| `end_date` trong `contract.expiry_reminder_days` (7) ngày tới | khách nhận `CONTRACT_EXPIRING_SOON`, một lần mỗi kỳ |
| Hợp đồng `Active`, `end_date < hôm nay` | lần đầu: khách + FM nhận `CONTRACT_OVERDUE` (một lần mỗi kỳ) |
| Như trên, sau `overdue.grace_days` (3) ngày | phí = `ngày tính phí × overdue.fee_per_day` (20.000đ), trừ phần phí quá hạn đã `Paid` **của đợt quá hạn này** (hoá đơn tạo từ `end_date + 1` trở đi) |
| Quá hạn ≥ `overdue.escalation_days` (30) ngày, chưa có yêu cầu trả kho | tạm khoá truy cập — [bên dưới](#quá-hạn-kéo-dài--tạm-khoá-truy-cập) |

`CONTRACT_EXPIRING_SOON` và `CONTRACT_OVERDUE` gửi **một lần mỗi kỳ** — kỳ xác định bằng `end_date` hiện tại (dedup
qua `OrderNotification` theo đơn + type, chỉ tính notification tạo từ đầu cửa sổ của kỳ):
- `CONTRACT_EXPIRING_SOON`: đã có nhắc tạo từ ngày `end_date − contract.expiry_reminder_days` thì bỏ qua;
- `CONTRACT_OVERDUE`: đã có báo tạo từ ngày `end_date + 1` thì bỏ qua.

Gia hạn dời `end_date` nên kỳ mới có cửa sổ mới: khách gia hạn rồi lại sắp hết hạn / lại quá hạn thì nhận nhắc lại một
lần. Gia hạn không phủ hết kỳ quá hạn (`end_date` mới vẫn trong quá khứ) cũng là kỳ mới → báo quá hạn lại một lần.

Phí cộng dồn nằm trên đúng một hoá đơn `Penalty` mã `INV-OVD-…` còn `Unpaid`:
- **Tạo / cập nhật:** cron tạo mới hoặc cập nhật số tiền (audit `OVERDUE_FEE_ACCRUED`), hạn `now + 7 ngày`.
- **Ngày tính phí** = `(ngày dừng − end_date) − grace_days`. Ngày dừng:
  - mặc định là hôm nay — kể cả khi khách đã gửi yêu cầu trả kho (gửi yêu cầu không dừng phí);
  - yêu cầu trả kho đang mở có lịch `RETURN` mà FS **đã xác nhận khách đến** (`appointmentArrivedAt`) → ngày bắt đầu
    của buổi đó (`appointmentStart`), kể cả khi biên bản còn chờ (khoang còn đồ / chờ trả phần thiếu);
  - lịch chưa tới hoặc khách không đến → vẫn hôm nay; ngày dừng không bao giờ vượt hôm nay.
- **Gia hạn phủ kỳ quá hạn** (`end_date` mới ≥ hôm nay): phí **ngừng cộng thêm** nhưng hoá đơn đã phát sinh **giữ
  nguyên `Unpaid`** — đó là công nợ của những ngày đã thực sự quá hạn, thu khi khách trả hoá đơn hoặc trừ vào cọc
  lúc trả kho. Không huỷ: huỷ sẽ cho phép khách chậm rồi gia hạn để xoá sạch phí. Cron vẫn quét hợp đồng đang có
  `INV-OVD` mở (kể cả đã gia hạn) để đồng bộ số tiền khi còn quá hạn.
- **Còn giao dịch `Pending`:** không đổi số tiền, vì cổng đã nhận số cũ — kể cả giao dịch đã quá cửa sổ 15' (không
  còn tính là phiên mở) nhưng chưa được chốt; xác nhận muộn sẽ chốt `Paid` đúng số tiền đã thu.
- **Khách trả phí quá hạn rồi vẫn tiếp tục quá hạn:** hoá đơn mới chỉ thu phần chênh.
- **Gia hạn rồi lại quá hạn (đợt mới):** phí đã trả của đợt trước không bù cho đợt mới — đợt mới tính từ `end_date`
  mới.
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

**Mở lại** (`OverdueService.restoreIfCleared`, cùng lượt cron, quét hợp đồng `Active` đang khoá). Điều kiện chung:
không còn hoá đơn `INV-OVD` `Unpaid` và chưa chốt quyết toán trả kho (chưa có hoá đơn `INV-CLN/DMG/KEY/PEN` khác
`Canceled` — FS đã thu hồi chìa/mã lúc chốt, không mở lại); thêm **một trong hai**:
- hợp đồng không còn quá hạn (`end_date` ≥ hôm nay — gia hạn đã phủ kỳ quá hạn) **và** không còn `INV-OVD`
  `Unpaid` của đợt trước: gia hạn chỉ dừng cộng thêm, khoản phí đã phát sinh vẫn phải trả;
- hợp đồng còn quá hạn nhưng có yêu cầu trả kho đang mở — mở để khách dọn đồ. Phí vẫn tính tiếp tới buổi trả kho
  khách đến (hoá đơn `INV-OVD` mới chỉ thu phần chênh) và gộp vào quyết toán; `escalate` bỏ qua hợp đồng có yêu cầu
  trả kho mở nên không khoá lại. Khách huỷ yêu cầu trả kho thì lượt cron sau khoá lại theo điều kiện thường.

Kết quả: xoá `access_suspended_at`, audit `CONTRACT_ACCESS_RESTORED`, khách nhận `CONTRACT_ACCESS_RESTORED` (nội dung
nêu rõ "không còn quá hạn" hay "mở lại để dọn đồ trả kho"). Mã cũ đã thu hồi nên khách tự lấy mã mới trong trang kho
đang thuê.

FM xem danh sách hợp đồng quá hạn của cơ sở (`GET /api/fm/overdue-contracts?facilityId=`), gồm:
- số ngày quá hạn và số ngày tính phí;
- tổng phí đã cộng dồn và hoá đơn đang mở;
- hợp đồng đã có yêu cầu trả kho chưa.

## Policy

| Key | Mặc định | Migration |
|---|---:|---|
| `extension.max_months` | 12 | V25 |
| `extension.invoice_due_days` | 3 | V25 |
| `contract.expiry_reminder_days` | 7 | V25 |
| `overdue.grace_days` | 3 | V25 |
| `overdue.fee_per_day` | 20000 | V25 |
| `rent.invoice_lead_days` | 5 | V27 |
| `overdue.escalation_days` | 30 | V27 |
| `return.no_show_grace_minutes` | 120 | V28 |
| `return.max_no_shows` | 2 | V28 |

## Test

`tenancy/PostRentalIntegrationTest.java` (Postgres) và `tenancy/controller/PostRentalControllerTest.java` (HTTP).
