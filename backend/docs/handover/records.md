---
purpose: HandoverRecord + RentalContract as-built — mapping, domain guard, due_at Flow 2.1, huỷ phiên bàn giao.
---
# Bàn giao — HandoverRecord & RentalContract

Trang này ghi phần **đang có trong code** của module `handover`: data layer, listener Flow 2.1 và
luật huỷ. Chưa có HTTP, cron, tạo record, tạo hợp đồng hay xác minh danh tính/hiện trạng.

## HandoverRecord

Bảng `handover_records` (V1). Entity `handover/domain/HandoverRecord.java`.

- `appointment_id` **unique** — một lịch hẹn chỉ có một record.
- Mỗi đơn tối đa một record đang mở: partial unique index `uq_handover_records_open_per_order`
  (`result = 'IN_PROGRESS'`).
- `result`: `IN_PROGRESS | COMPLETED | REJECTED | CANCELED`.
- `identity_status`, `inspection_status` map dạng chuỗi theo CHECK của V1; behavior Flow 2.2 chưa nối.
- Bảng **không có** `arrived_at`. Mốc khách đến đọc qua `appointments.arrived_at`, nối bằng
  `handover_records.appointment_id`.
- `cancel(reason)`: chỉ từ `IN_PROGRESS` → `CANCELED`, ghi `reject_reason`.
- `setDueAt(instant)`: chỉ từ `IN_PROGRESS` và chỉ một lần — gọi lần hai là lỗi lập trình.

## RentalContract

Bảng `rental_contracts` (V1). Entity `handover/domain/RentalContract.java`.

- `status`: `Draft | Signed | Active | Ended | Canceled`.
- `cancel()`: chỉ `Draft`/`Signed` → `Canceled`; `Active`/`Ended` giữ nguyên lịch sử.
- Bảng không có `created_at`/`updated_at`; entity không map hai cột đó.

## Flow 2.1 — khách đến và `due_at`

1. FS bấm xác nhận khách đến: `StaffAppointmentActionService.arrive` (booking) chuyển
   `Appointment` `Pending → Done`, ghi `arrived_at`, audit `APPOINTMENT_ARRIVED`, phát
   `AppointmentArrived(appointmentId, arrivedAt)`.
2. `AppointmentArrivedListener` (handover) chạy `BEFORE_COMMIT`, trong transaction của arrive:
   - tìm record theo `appointment_id` và khoá row;
   - không có record → bỏ qua. Flow 1 tạo record khi đặt lịch; luồng đó chưa có code nên hiện tại
     arrive vẫn chạy mà không set `due_at`;
   - record không `IN_PROGRESS` hoặc đã có `due_at` → bỏ qua;
   - ngược lại set `due_at = min(arrived_at + handover.due_days, RentalOrder.expires_at)`.
3. Công thức:
   - `arrived_at` lấy từ chính `Appointment` qua event, không đọc lại đồng hồ ở listener;
   - `handover.due_days = 1`, seed bằng V16, đọc qua `PricingQuery.intPolicy`; giá trị `< 1`
     bị từ chối (`IllegalStateException`) để không tạo hạn dưới 24 giờ;
   - `RentalOrder.expires_at` null → không có trần, `due_at = arrived_at + 1 ngày`;
   - `expires_at` sớm hơn mốc trên → lấy `expires_at` (trần của đơn luôn thắng, kể cả dưới 2 giờ);
   - `due_at` chỉ set một lần; đổi policy sau đó không hồi tố các phiên đã mở.
4. Không ghi audit riêng cho `due_at`: `APPOINTMENT_ARRIVED` đã bao thao tác, bản ghi
   `HANDOVER_DUE_SET` không tồn tại trong catalog.

## Huỷ phiên bàn giao

`handover/application/HandoverService.java`:

- `cancelByAppointmentId(appointmentId, reason)` — record `IN_PROGRESS` hoặc đã `CANCELED` thì áp
  luật theo đơn (record đã `CANCELED` chỉ dọn contract còn sót). Record không tồn tại,
  `COMPLETED` hoặc `REJECTED` → no-op.
- `cancelByOrderId(orderId, reason)` — áp luật trực tiếp theo đơn.
- Một luật duy nhất cho cả hai entry point:
  - record đang mở → `CANCELED`, ghi `reject_reason`;
  - mọi hợp đồng `Draft`/`Signed` của đơn → `Canceled`.
- Audit một bản ghi cho mỗi entity: `HANDOVER_CANCELED` (`HandoverRecord`) và
  `CONTRACT_CANCELED` (`RentalContract`); actor `null` (System), `reason` = lý do huỷ.
- Idempotent theo trạng thái: record đã đóng không còn `IN_PROGRESS`; contract đã `Canceled` không
  nằm trong `Draft`/`Signed`. Gọi lại không tạo audit mới.

Hai hàm chưa được nối vào HTTP, cron hay cancellation cascade của booking — caller thuộc luồng quá
`due_at` và no-show.

## Chưa có trong code

- Flow 1 tạo `HandoverRecord` khi đặt lịch; hiện record phải có sẵn thì listener/huỷ mới có tác dụng.
- HTTP API Flow 2.1/2.2.
- Cron huỷ record quá `due_at`; mở rộng cron no-show sang handover/order.
- Sinh `RentalContract` (Flow 2.3), xác minh danh tính và hiện trạng khoang (Flow 2.2).
- Notification cho các nhánh trên.

## Test

`backend/src/test/java/vn/lemar/selfstorage/handover/HandoverFoundationIntegrationTest.java` chạy trên
Postgres (Testcontainers): round-trip mapping hai entity, công thức `due_at`, listener idempotent và
thiếu record, huỷ theo lịch/đơn, guard trạng thái hợp đồng, audit một bản ghi mỗi entity.
