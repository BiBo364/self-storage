# VNPay sandbox — setup & test

Hướng dẫn vận hành để dev tích hợp Cổng thanh toán VNPay (module `payment`): lấy credential,
cấu hình terminal, dựng IPN URL, test end-to-end và các lỗi thường gặp.

## 1. Setup

### 1.1 Credential

1. Đăng ký tài khoản sandbox tại <https://sandbox.vnpayment.vn/devreg/>. VNPay gửi về email đăng ký:
   `vnp_TmnCode`, `vnp_HashSecret` và thông tin đăng nhập portal merchant.
2. Điền vào `.env` (file bị `.gitignore`, mỗi người tự đăng ký tài khoản riêng):

   ```bash
   VNPAY_TMN_CODE=<mã VNPay gửi>
   VNPAY_HASH_SECRET=<chuỗi bí mật VNPay gửi>
   VNPAY_PAY_URL=https://sandbox.vnpayment.vn/paymentv2/vpcpay.html
   VNPAY_RETURN_URL=http://localhost:5173/payments/result
   ```

   Return URL là trang kết quả của FE: khách quay về, FE gọi `POST /api/invoices/{id}/payment-check` (`querydr`) để
   chốt hoá đơn. Chỉ test backend (không chạy FE) thì đặt `http://localhost:8080/api/payments/vnpay/return` — route
   này trả JSON kiểm chữ ký, không ghi dữ liệu.

   App không đọc biến IPN URL — IPN URL khai trên portal merchant (mục 1.3).

   Bốn biến `VNPAY_*` để `@NotBlank` — thiếu là app không khởi động được.

### 1.2 Terminal: thuật toán & kích hoạt

- Terminal phải ở **phiên bản 2.1.0 / HMACSHA512**. Portal để **MD5** (legacy) là sai: mọi chữ ký
  bị cổng từ chối, trang thanh toán trả `Error.html?code=70`.
  Chỉnh trong **TerminalEdit** (`https://sandbox.vnpayment.vn/merchantv2/Account/TerminalEdit.htm`)
  hoặc liên hệ hỗ trợ.
- **Kiểm tra terminal không cần portal** — gọi API `querydr`
  (`POST https://sandbox.vnpayment.vn/merchant_webapi/api/transaction`) với một `vnp_TxnRef` bất kỳ
  và đọc `vnp_ResponseCode`:

  | Mã | Nghĩa |
  |---|---|
  | `91` | Terminal tồn tại — credential và chữ ký HMACSHA512 hợp lệ |
  | `02` | TmnCode không hợp lệ / terminal chưa kích hoạt |
  | `97` | Sai chữ ký (HashSecret lệch) |

### 1.3 IPN URL cho dev local

- **IPN URL phải là HTTPS công khai** — VNPay gọi từ internet, không tới được `localhost`:

  ```bash
  cloudflared tunnel --url http://localhost:8080
  # hoặc: ngrok http 8080
  ```

  Lấy URL https in ra, ghép `/api/payments/vnpay/ipn` và khai vào **cấu hình tài khoản merchant**
  trên portal (không truyền kèm trong link thanh toán).
- **Return URL không cần public** — là redirect trình duyệt khách, dev dùng `http://localhost:...` bình thường
  (mục 1.1).
- Không dựng tunnel thì IPN không về; khi đó hoá đơn được chốt lúc khách về trang kết quả FE (`payment-check` hỏi
  `querydr`). Khách đóng tab trước khi về trang kết quả thì giao dịch nằm `Pending` cho tới khi khách mở lại trang
  hoặc ADMIN đối soát.
- Quick tunnel đổi domain mỗi lần chạy lại → tunnel mới là phải khai lại portal; test nhiều ngày
  nên dùng named tunnel (cloudflared) hoặc ngrok reserved domain.

## 2. Test flow (end-to-end)

1. Tạo một `PaymentTransaction(Pending)` với `vnp_TxnRef` và `amount` sẽ dùng để thanh toán.
2. Sinh payment URL bằng `VnPayGateway` (phiên bản 2.1.0, HMACSHA512).
3. Mở URL bằng **browser thật** và thanh toán bằng **thẻ test** (mục 2.1).
4. VNPay redirect về Return URL và gọi IPN. Kiểm tra:
   - Return: trang kết quả FE báo thanh toán thành công; Return URL đặt về route backend thì thấy
     `{"validSignature": true, "responseCode": "00"}` — chữ ký VNPay verify đúng.
   - DB: `payment_transactions.status = Success`, có `gateway_transaction_no` và `paid_at`.
     Trạng thái được ghi qua IPN hoặc `payment-check` (`querydr`, khi khách về trang kết quả FE) — Return URL tự nó
     không ghi dữ liệu.

### 2.1 Thẻ test

Chỉ dùng thẻ trong danh sách chính thức. **Ưu tiên thẻ nội địa NCB** (form ngắn, ổn định nhất):

| Trường | Giá trị |
|---|---|
| Số thẻ | `9704198526191432198` |
| Tên chủ thẻ | `NGUYEN VAN A` |
| Ngày phát hành | `07/15` |
| Mật khẩu OTP | `123456` |

Thẻ quốc tế (tab "Thẻ quốc tế") — điền đủ trường billing: VISA No 3DS `4456530000001005`,
CVC `123`, hạn `12/26`, email `test@gmail.com`, địa chỉ `22 Lang Ha`, thành phố `Ha Noi`.

> Một số terminal sandbox từ chối thẻ quốc tế ở bước xử lý ("Có lỗi xảy ra trong quá trình xử lý.
> Vui lòng thông báo lại đơn vị chấp nhận thanh toán") — khi đó dùng thẻ nội địa NCB.

Danh sách đầy đủ (NCB đủ loại lỗi, VISA/MasterCard/JCB 3DS và No-3DS):
<https://sandbox.vnpayment.vn/apis/vnpay-demo/>

## 3. Lỗi có thể gặp phải

| Hiện tượng | Nguyên nhân | Xử lý |
|---|---|---|
| `Error.html?code=70` khi mở URL thanh toán | Checksum không hợp lệ — terminal khác thuật toán (MD5) hoặc `HashSecret` lệch | Kiểm terminal theo 1.2; đưa về 2.1.0/HMACSHA512 |
| Test bằng `curl` nhận `Error.html?backtoken=...` | Client không phải browser bị cổng bounce | Test bằng browser thật |
| Trang "An error occurred. Please try again later." + **Mã tra cứu** | Lỗi hệ thống phía VNPay (không có trong bảng mã lỗi) | Giữ mã tra cứu, gửi hỗ trợ kỹ thuật kèm TmnCode + thời điểm |
| Console browser: `Error gen info VPP init, code=99` | Trang thanh toán không khởi tạo được (trạng thái/cấu hình merchant) | Kiểm kích hoạt terminal (1.2); nếu vẫn lỗi → hỗ trợ kỹ thuật |
| Thanh toán xong nhưng DB vẫn `Pending` | IPN không tới: IPN URL chưa khai, khai sai, hoặc không public | Khai lại IPN URL đúng tunnel hiện tại (1.3); VNPay retry 10 lần × 5 phút |
| IPN trả `97 - Invalid signature` trong log | `HashSecret` sai hoặc terminal khác thuật toán | Kiểm `.env` + terminal (1.2) |

## 4. Liên hệ

- Hỗ trợ kỹ thuật: `kythuatctt@vnpay.vn` · Tổng đài `1900 55 55 77`
- Docs: <https://sandbox.vnpayment.vn/apis/docs/thanh-toan-pay/pay.html>
