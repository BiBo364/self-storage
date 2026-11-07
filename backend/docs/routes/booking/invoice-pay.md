---
auth-model: ownership
purpose: Khách bấm thanh toán một hoá đơn — kiểm 5 nhánh spec 1.4 rồi trả URL cổng thanh toán (payOS mặc định).
---
# POST /api/invoices/{invoiceId}/pay

Khách bấm "Tiến hành thanh toán" trên hoá đơn `Unpaid` của mình (cọc, cọc bổ sung, tháng đầu).

- **Actor / quyền:** bearer + `ACCOUNT_ACTIVE` + ownership (hoá đơn của người khác → 404).
- **Contract chi tiết:** annotation tại `booking/controller/InvoicePaymentController.java`; Swagger UI —
  `http://localhost:8080/swagger-ui/index.html`.

## Luồng / hành vi

- Một transaction, khoá theo thứ tự **đơn → hoá đơn → khoang** (cùng thứ tự với đồng ý đề xuất và cascade).
- Hoá đơn không `Unpaid` → 409; quá `due_date` → 409; đơn đã đóng → 409.
- Hoá đơn gắn đơn (cọc / cọc bổ sung), kiểm đúng thứ tự spec 1.4:
  1. đơn quá `expires_at` → 409 "Đơn thuê kho đã hết hạn…", không đổi dữ liệu;
  2. khoang `Reserved`/`Rented` → hoá đơn `Canceled` (audit `INVOICE_CANCELED`), FM nhận
     `PROPOSAL_REPROPOSAL_REQUIRED`, đơn giữ nguyên, 409 kèm `invoiceCanceled = true` — thay đổi **vẫn commit**;
  3. khoang `Maintenance` → như trên, message bảo trì;
  4. khoang đang có phiên thanh toán khác (còn giao dịch `Pending` của bất kỳ hoá đơn cọc `Unpaid` nào trỏ khoang
     đó — giữ tới khi cổng xác nhận phiên đóng) → 409; phiên mở là của chính hoá đơn này → trước khi khoá đã đối
     soát + huỷ phiên cũ ở cổng; cổng không huỷ được → 409 "Hoá đơn đang có phiên thanh toán chưa kết thúc…";
  5. còn lại → ghi `PaymentTransaction(Pending)` rồi trả `paymentUrl`.
- Khoá suy từ dữ liệu (giao dịch `Pending`) — không Redis, sống qua restart, nhả khi cổng đóng phiên. Khoá row
  khoang trước khi đọc phiên mở nên hai khách bấm cùng lúc không cùng mở được phiên.
- Hoá đơn gắn hợp đồng (tháng đầu) bỏ qua bước 1–3, chỉ chặn phiên mở của chính nó.
- Mã giao dịch do cổng sinh (payOS: `orderCode` số; VNPay: `INV{id}T…`); mốc tạo phiên = `created_at` của giao
  dịch (cắt giây). Xem [payment.md](../../payment.md).

## Ghi chú nghiệp vụ

- Spec 1.4 › 5 trường hợp. Khoá phiên suy từ giao dịch `Pending` và 3 bản ghi audit nhánh thành công theo chốt của
  luồng đặt cọc VNPay.

## Liên quan

- Xem thêm: [routes.md](../../routes.md) · [index.md](../../index.md) · [payment.md](../../payment.md) · [deposit-payment.md](../../booking/deposit-payment.md)
