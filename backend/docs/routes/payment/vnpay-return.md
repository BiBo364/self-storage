# GET /api/payments/vnpay/return

Kiểm chữ ký của tham số VNPay gửi kèm khi đẩy trình duyệt khách về. **Chỉ để xem kết quả, không ghi gì.**

Dùng làm `VNPAY_RETURN_URL` khi test backend không chạy FE (gọi `/pay` qua Swagger). Chạy cả app thì
`VNPAY_RETURN_URL` trỏ trang kết quả FE `<FRONTEND_URL>/payments/result` (giá trị trong `.env.example`) — trang đó gọi
[`payment-check`](../booking/invoice-payment-check.md) để chốt hoá đơn, không gọi route này.

- **Actor / quyền:** public. Đây là redirect từ VNPay nên request không mang header
  `Authorization` — không thể yêu cầu token.
- **Contract chi tiết:** annotation tại `VnPayCallbackController.java`; Swagger UI —
  `http://localhost:8080/swagger-ui/index.html`.

## Luồng / hành vi

- Kiểm chữ ký rồi trả lại `validSignature`, `responseCode`, `txnRef` để FE hiển thị.
- **Không ghi dữ liệu.** Không đổi trạng thái giao dịch, không bắn event, không ghi AuditLog.
- Luôn trả 200 kể cả khi chữ ký sai — kết quả nằm trong `validSignature`.

## Ghi chú nghiệp vụ

- **Tuyệt đối không dùng route này để ghi nhận đã thanh toán.** Tham số nằm trên thanh địa chỉ và
  do khách kiểm soát; khách có thể sửa tay hoặc đóng trình duyệt giữa chừng khiến route không bao
  giờ được gọi. Nguồn xác nhận duy nhất là [IPN](vnpay-ipn.md).
- Khách đóng trình duyệt trước khi về đến đây không ảnh hưởng gì: IPN vẫn chạy độc lập.
- Route này trả JSON trần (`validSignature`/`responseCode`/`txnRef`) chứ chưa dùng `ApiEnvelope`.
  Khi FE làm trang kết quả thanh toán thì cân nhắc đổi sang envelope cho đồng bộ — đổi được vì
  đây là contract nội bộ với FE, khác với IPN là contract của VNPay.

## Ví dụ

```bash
curl -s 'http://localhost:8080/api/payments/vnpay/return?vnp_TxnRef=TEST1&vnp_ResponseCode=00&vnp_SecureHash=deadbeef'
{"responseCode":"00","validSignature":false,"txnRef":"TEST1"}
```

Chữ ký sai vẫn trả 200 — kết quả nằm ở `validSignature`. Thứ tự field không cố định vì response
dựng bằng `Map.of`; FE đừng phụ thuộc vào thứ tự.

## Liên quan

- [vnpay-ipn.md](vnpay-ipn.md) · [routes.md](../../routes.md) · [index.md](../../index.md)
