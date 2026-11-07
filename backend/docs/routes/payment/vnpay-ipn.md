# GET /api/payments/vnpay/ipn

Server VNPay gọi vào để xác nhận kết quả thanh toán. Đây là **nguồn xác nhận thanh toán duy nhất**
— Return URL chỉ để hiển thị cho khách.

- **Actor / quyền:** public. VNPay gọi từ server của họ và không có JWT, nên route này không thể
  yêu cầu token. Thứ thay cho token là chữ ký HMAC-SHA512 trong `vnp_SecureHash`.
- **Contract chi tiết:** annotation tại `VnPayCallbackController.java`; Swagger UI —
  `http://localhost:8080/swagger-ui/index.html`.

## Luồng / hành vi

Thứ tự kiểm, dừng ngay ở bước đầu tiên không đạt:

1. Chữ ký HMAC-SHA512 — sai → `97`
2. Tìm giao dịch theo `vnp_TxnRef` — không có → `01`
3. `vnp_TmnCode` khớp merchant — không khớp → `01`
4. `vnp_Amount` khớp số tiền (VNPay gửi đã nhân 100) — lệch → `04`
5. Giao dịch còn `Pending` — không còn → `02`, **không ghi gì thêm**
6. Ghi `Success`/`Failed`, bắn `PaymentSucceeded`/`PaymentFailed` → `00`

**Idempotent theo `vnp_TxnRef`.** Bước 5 là chốt chặn. Row được khoá `PESSIMISTIC_WRITE` khi đọc,
nên hai IPN đến song song cũng chỉ một lần được ghi nhận — cái còn lại đợi, đọc lại thấy `Success`
và trả `02`. Unique constraint không thay thế được khoá này vì cả hai cùng update một row.

Lỗi ngoài dự kiến trả `99` thay vì HTTP 500, bắt ở controller nên transaction vẫn rollback.

## Ghi chú nghiệp vụ

- **Không dùng `ApiEnvelope`.** Mọi route khác trả `{message, data}`, route này thì không: VNPay đọc
  phản hồi theo đúng hai key `RspCode`/`Message` ở mức gốc. Bọc thêm một lớp là VNPay không parse
  được, coi như merchant chưa xác nhận và retry đủ 10 lần. Đây là contract của bên thứ ba.
- **VNPay retry tối đa 10 lần, cách nhau 5 phút** khi nhận `01`, `04`, `97`, `99` hoặc khi timeout.
  `00` và `02` là chốt.
- **`PaymentSucceeded`/`PaymentFailed` bắn trong transaction IPN.** Module nghe phải dùng
  `@TransactionalEventListener(phase = AFTER_COMMIT)` (hoặc `REQUIRES_NEW`) — listener đồng bộ mặc
  định thấy state chưa commit, và exception từ listener sẽ rollback IPN khiến VNPay retry 10 lần.
- IPN URL khai trong **cấu hình tài khoản merchant** trên portal VNPay, không gửi kèm lúc tạo URL
  thanh toán. Dev local cần tunnel HTTPS — xem [docs/vnpay-testing.md](../../../../docs/vnpay-testing.md).
- `vnp_Amount` đối chiếu với `PaymentTransaction.amount`; giá trị đó booking lấy từ `invoice.amount` trong DB lúc
  mở phiên (`InvoicePaymentService` → `PaymentService.startInvoicePayment`).

## Ví dụ

Gọi tay, không kèm `vnp_SecureHash` hợp lệ:

```bash
curl -s 'http://localhost:8080/api/payments/vnpay/ipn?vnp_TxnRef=TEST1&vnp_SecureHash=deadbeef'
{"RspCode":"97","Message":"Invalid signature"}
```

`97` ở đây là đúng chứ không phải lỗi: không có chữ ký hợp lệ thì không chứng minh được request
đến từ VNPay. Đáng chú ý là nó trả **200 chứ không phải 401** — route này public, đúng như thiết kế.

Hai key viết hoa `RspCode`/`Message` cũng thấy rõ trong chính response này.

## Liên quan

- [vnpay-return.md](vnpay-return.md) · [routes.md](../../routes.md) · [index.md](../../index.md)
- Hướng dẫn lấy credential sandbox + dựng IPN URL: [docs/vnpay-testing.md](../../../../docs/vnpay-testing.md)
