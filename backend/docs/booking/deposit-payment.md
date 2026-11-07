---
purpose: Luồng thanh toán hoá đơn (cọc, cọc bổ sung, hoá đơn gắn hợp đồng) từ nút "Thanh toán" tới khi hoá đơn Paid — as-built.
---
# Thanh toán hoá đơn & chốt cọc (Flow 1.4)

Code: `booking/application/InvoicePaymentService.java` (bấm thanh toán), `InvoicePaymentSettlement.java` (chốt khi
cổng xác nhận), `payment/application/PaymentService.java` (giao dịch, IPN, `querydr`).

## Sơ đồ

```mermaid
sequenceDiagram
    actor K as Khách
    participant B as booking (InvoicePaymentService)
    participant P as payment (PaymentService)
    participant V as Cổng (payOS)
    participant S as booking (InvoicePaymentSettlement)
    K->>B: POST /api/invoices/{id}/pay
    B->>B: khoá đơn → hoá đơn → khoang, kiểm 5 nhánh spec 1.4
    B->>P: startPayment (ghi PaymentTransaction Pending)
    P-->>K: paymentUrl
    K->>V: thanh toán
    alt Webhook về
        V->>P: POST /api/payments/payos/webhook
    else Khách quay về, webhook chưa tới
        K->>B: POST /api/invoices/{id}/payment-check
        B->>P: reconcileLatest → tra cứu link
    end
    P->>P: Success + PaymentSucceeded (commit)
    P-->>S: AFTER_COMMIT, REQUIRES_NEW
    S->>S: hoá đơn Paid; Deposit → khoang Reserved + đơn Deposited
```

## Trạng thái

| Hoá đơn | Khi | Hệ quả |
|---|---|---|
| `Deposit` `Unpaid → Paid` | cổng xác nhận | khoang `Available → Reserved`, đơn `Pending → Deposited`, khách nhận `DEPOSIT_PAYMENT_SUCCEEDED` |
| `DepositSupplement` `Unpaid → Paid` | cổng xác nhận | reserve khoang mới; đơn không đổi trạng thái, không gửi lại "Đặt cọc thành công" |
| gắn hợp đồng `Unpaid → Paid` | cổng xác nhận | phát `ContractInvoicePaid(invoiceId, contractId, type, paidAt)` cho module sở hữu hợp đồng |
| gắn hợp đồng, đã đóng (`Canceled`/`Expired`) mà tiền vẫn vào | cổng xác nhận muộn | giao dịch `Success`, log ERROR, FM nhận `FIRST_MONTH_REFUND_REQUIRED` |

Hoá đơn gắn hợp đồng sau bàn giao (gia hạn, phí quá hạn, quyết toán trả kho) có đơn ở `Done`. Bấm thanh toán bỏ qua
các kiểm tra trạng thái / hạn của đơn và chỉ chặn khi hoá đơn còn phiên mở. Xem
[tenancy/rentals.md](../tenancy/rentals.md).
| `Unpaid → Canceled` | bấm thanh toán khi khoang `Reserved`/`Rented`/`Maintenance` | FM nhận `PROPOSAL_REPROPOSAL_REQUIRED`, đơn giữ nguyên |
| `Unpaid → Expired` | cron quá `due_date` | xem [Cron Flow 1](#cron-flow-1) |

Audit nhánh thành công (actor = hệ thống): `PAYMENT_SUCCEEDED` (PaymentTransaction), `INVOICE_PAID` (Invoice),
`RENTAL_ORDER_DEPOSITED` (RentalOrder), `STORAGE_UNIT_RESERVED` (StorageUnit). Cọc bổ sung chỉ ghi 3 bản đầu và
`STORAGE_UNIT_RESERVED`.

## Bất biến

- **Khoá phiên suy từ dữ liệu, giữ tới khi cổng đóng phiên:** khoang bận khi còn giao dịch `Pending` thuộc hoá đơn
  cọc `Unpaid` của bất kỳ đơn nào trỏ khoang — không tự hết sau 15' mà đợi cổng xác nhận đã trả / huỷ / hết hạn
  (webhook, `payment-check`, job đối soát). Khoá row khoang trước khi đọc → hai request song song không cùng mở được
  phiên.
- **Bấm thanh toán lại hoá đơn đang có phiên:** trước khi khoá, đối soát phiên cũ (đã trả → chốt luôn, request sau
  nhận 409 "không còn chờ thanh toán"); chưa trả → huỷ phiên cũ ở cổng (payOS cancel) rồi mở phiên mới; cổng không huỷ
  được → 409 "Hoá đơn đang có phiên thanh toán chưa kết thúc…".
- **Khoang vừa có người trả cọc → chặn ngay các hoá đơn khác của khoang** (spec 1.1 "đã có người khác đặt cọc"):
  sau commit bước chốt cọc, `CompetingInvoiceCanceller` (transaction riêng, khoá đơn → hoá đơn) huỷ mọi hoá đơn cọc
  `Unpaid` khác trỏ khoang (audit `INVOICE_CANCELED` lý do "Khoang đã có người đặt cọc trước"), FM nhận
  `PROPOSAL_REPROPOSAL_REQUIRED`, khách nhận `PROPOSAL_UNIT_TAKEN` ("Khoang [mã] đã có người đặt cọc trước. Cơ sở
  đang tìm khoang khác phù hợp cho bạn."); đơn không huỷ (đơn đã cọc thì bật `needs_reproposal`); phiên còn mở của
  các hoá đơn đó bị huỷ ở cổng.
- **Thứ tự khoá:** đơn → hoá đơn → khoang, ở cả bấm thanh toán và chốt thanh toán — cùng thứ tự với đồng ý đề xuất
  và Cancellation Cascade, không deadlock ngược chiều. Hoá đơn gắn hợp đồng (tháng đầu) không có `order_id` (CHECK
  V1: đúng một nguồn) — đơn được tìm qua `rental_contracts.order_id` (`InvoiceRepository.findOrderIdById`) để vẫn
  khoá đơn trước; các kiểm tra đơn đã đóng / quá `expires_at` áp dụng như hoá đơn cọc, bỏ qua kiểm khoang.
- **Listener chốt chạy `AFTER_COMMIT` + `REQUIRES_NEW`:** lỗi ở bước chốt không rollback giao dịch của cổng (tiền
  đã vào, VNPay không retry vô ích).
- **Xác nhận muộn (lưới an toàn cuối):** với khoá phiên giữ tới khi cổng đóng phiên + huỷ phiên khi huỷ hoá đơn,
  nhánh này chỉ còn khi khách tự chuyển khoản tay theo QR cũ sau khi link đã huỷ. Khi đó vẫn ghi
  `PAYMENT_SUCCEEDED` (hoặc webhook trả `ALREADY_SETTLED` nếu giao dịch đã `Failed`), log ERROR, FM nhận
  `PROPOSAL_REPROPOSAL_REQUIRED` để hoàn tiền thủ công. Không tự mở lại đơn.
- **Đối soát với cổng là nguồn dự phòng duy nhất ngoài webhook/IPN:** dùng ở `payment-check` (khách quay về) và job
  đối soát (`PaymentReconcileJob`, 5'); return URL không bao giờ ghi dữ liệu.

## Cron Flow 1

`BookingExpiryJob` (5', `booking.expiry.fixed-delay-ms`) gọi `BookingExpiryService`, mỗi bản ghi một transaction:

| Bước | Điều kiện | Kết quả |
|---|---|---|
| Request hết hạn | `RentalRequest` `Pending`/`Approved`, `expires_at < now` | `Expired` (update có điều kiện), audit `RENTAL_REQUEST_EXPIRED` |
| Hoá đơn quá hạn | `Unpaid`, `due_date < now`, **không** còn phiên thanh toán mở | `Expired` + audit `INVOICE_EXPIRED`; `Deposit` → cascade "Quá hạn thanh toán cọc"; `DepositSupplement` → bật `needs_reproposal` + FM nhận `PROPOSAL_REPROPOSAL_REQUIRED`; gắn hợp đồng → phát `ContractInvoiceExpired` |
| Catch-all đơn | `expires_at < now`, chưa `Canceled`/`Done`, không còn phiên mở | cascade, lý do: `Pending` + proposal chưa `Agreed` → "Quá hạn xác nhận đề xuất"; `Pending` + `Agreed` → "Quá hạn thanh toán cọc"; `Deposited` → "Quá hạn đặt lịch check-in"; `Scheduled`/`InProgress` → "Không đến nhận kho quá hạn giữ" (chưa đến) / "Chưa hoàn tất bàn giao trong hạn" (đã đến) |
| Nhắc sắp hết hạn | `expires_at` trong `order.expiry_reminder_days` (3) tới, chưa đóng | `RENTAL_ORDER_EXPIRING_SOON` cho khách, mỗi đơn một lần (dedup qua `OrderNotification` theo đơn + type) |

`PaymentReconcileJob` (5', `payment.reconcile.fixed-delay-ms`): giao dịch `Pending` tạo quá 20' (cửa sổ 15' + đệm
5') → hỏi cổng; payOS báo `EXPIRED`/`CANCELLED` (hoặc VNPay không có giao dịch) → `Failed`, khoá phiên nhả.
