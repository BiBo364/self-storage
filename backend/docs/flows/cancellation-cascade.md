---
purpose: Cancellation Cascade as-built trong booking — entity, khoang, audit, notification.
---
# Cancellation Cascade

Chạy khi `RentalOrder` chuyển `Canceled`. Hiện luồng từ chối quá số khoang cho phép là nơi gọi
cascade; các nguyên nhân khác (khách huỷ, quá hạn, cron) chưa có code. Cascade chạy trong transaction
của caller, đơn đã được caller khoá.

## Sơ đồ

```mermaid
flowchart TB
    C0["RentalOrder → Canceled<br/>+ cancel_reason"] --> C1["Proposal Pending/Agreed → Canceled<br/>audit RENTAL_ORDER_CANCELED"]
    C0 --> C2["Invoice Unpaid (mọi type) → Canceled<br/>audit INVOICE_CANCELED"]
    C0 --> C3{"Đơn đang giữ khoang?<br/>Deposited / Scheduled / InProgress"}
    C3 -- có --> C4["StorageUnit Reserved → Available<br/>audit RENTAL_ORDER_CANCELED"]
    C3 -- không --> C5["Không nhả khoang"]
    C0 --> C6["Appointment Pending → Canceled<br/>audit APPOINTMENT_CANCELED"]
    C0 --> C7["Notify khách + FM<br/>RENTAL_ORDER_CANCELED"]
```

## Các bước

1. `RentalOrder.status → Canceled`, ghi `cancel_reason`; đơn đã đóng thì ném lỗi — caller phải guard.
2. Mọi `ProposalFeedback` `Pending`/`Agreed` của đơn → `Canceled`, audit từng proposal.
3. Mọi `Invoice` `Unpaid` của đơn → `Canceled`, audit từng invoice.
4. Mọi `Appointment` `Pending` của đơn → `Canceled`, ghi `cancel_reason`; audit `APPOINTMENT_CANCELED` với
   actor = System.
5. Khoang: chỉ nhả `Reserved → Available` khi đơn bị huỷ **thực sự đang giữ khoang**
   (`Deposited`/`Scheduled`/`InProgress`). Đơn `Pending` mới gán `unit_id` chưa cọc nên không nhả.
6. Khách + FM nhận notification `RENTAL_ORDER_CANCELED`; lý do khách chịu rủi ro (quá ngưỡng từ chối)
   thì body nêu rõ "Tiền cọc không được hoàn".
7. Audit một bản ghi mỗi entity thay đổi.

## Ranh giới

- Chưa xử lý `RentalContract` (`Draft`/`Signed → Canceled`) và `HandoverRecord` (`IN_PROGRESS → CANCELED`)
  — các module đó chưa có code. `Appointment` đã nối (bước 4).
- Hoàn cọc theo `cancel_reason` thuộc Flow 4; cascade chỉ dừng ở notification + audit.
- Các nguyên nhân huỷ khác chưa có entry point: `Khách hủy đơn`, `Khách không còn nhu cầu`,
  `Quá hạn …` (cron).
