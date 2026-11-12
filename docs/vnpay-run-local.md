# Chạy thử thanh toán VNPay trên máy local — các bước

Checklist để chạy luồng đặt cọc với cổng VNPay sandbox thật, trên máy dev. Làm theo từ trên
xuống, không nhảy cóc. Lần đầu mất ~20 phút, các lần sau ~5 phút (chỉ phải làm lại phần B).

Chi tiết kỹ thuật (mã lỗi, danh sách thẻ đầy đủ, API kiểm terminal): xem `vnpay-testing.md`.

---

## Hiểu nhanh luồng này đang làm gì

Khách bấm trả tiền → app sinh một link thanh toán có **chữ ký** → khách trả tiền trên trang
VNPay → VNPay báo kết quả về **hai đường khác nhau**:

| Đường | Ai gọi | Dùng để làm gì |
|---|---|---|
| Return URL | Trình duyệt khách bị đẩy về | Chỉ đưa khách về trang mình. **Không ghi DB** — khách sửa URL được nên không tin được |
| IPN | Server VNPay gọi thẳng vào server mình | **Nguồn sự thật.** Ghi `Paid` vào DB |
| `payment-check` | Trang kết quả FE gọi khi khách quay về | Hỏi lại VNPay (`querydr`, có chữ ký) rồi chốt như IPN — dự phòng khi IPN chưa về |

Server mình chạy ở `localhost`, VNPay ngoài internet không với tới được. Nên phải dựng **tunnel**:
mượn một địa chỉ https công khai, ai gõ vào đó thì chuyển về `localhost:8080`.

Và VNPay chỉ gọi IPN vào địa chỉ **đã khai sẵn trên portal merchant**, không phải địa chỉ gửi kèm
trong link thanh toán. Nên đổi tunnel là phải khai lại portal.

---

## A. Chuẩn bị — làm một lần

### A1. Credential VNPay

Nằm trong email VNPay gửi lúc đăng ký sandbox (tìm từ khoá `vnpayment`). Chưa có tài khoản thì
đăng ký ở <https://sandbox.vnpayment.vn/devreg/>, vài phút sau mail về.

### A2. File `.env`

File `.env` ở **gốc repo** (cùng cấp `docker-compose.yml`), không phải trong `backend/`. Chưa có
thì `cp .env.example .env`.

Các dòng cần có giá trị:

```
VNPAY_TMN_CODE=<mã trong email>
VNPAY_HASH_SECRET=<chuỗi bí mật trong email>
VNPAY_PAY_URL=https://sandbox.vnpayment.vn/paymentv2/vpcpay.html
VNPAY_RETURN_URL=http://localhost:8080/api/payments/vnpay/return

CLOUDINARY_NAME=local-dev
CLOUDINARY_KEY=local-dev
CLOUDINARY_SECRET=local-dev
```

Ba dòng `CLOUDINARY_*` **không phải lấy từ đâu cả** — gõ đúng chuỗi `local-dev` là được. App chỉ
kiểm "không rỗng" (`@NotBlank`), và luồng thanh toán không đụng tới upload. Chỉ khi làm tính năng
upload ảnh mới cần tài khoản thật ở cloudinary.com (free, không cần thẻ).

`VNPAY_RETURN_URL` ở đây trỏ về route backend (JSON kiểm chữ ký) vì checklist này gọi `/pay` qua Swagger, không chạy
FE. `.env.example` mặc định trỏ trang kết quả FE `http://localhost:5173/payments/result` — dùng giá trị đó khi chạy
cả FE: khách bấm thanh toán trên web, trả xong quay về trang kết quả, trang gọi `payment-check` để chốt hoá đơn.

`VNPAY_IPN_URL` trong `.env.example` **để trống cũng được** — app không đọc biến này. IPN khai ở
portal, không khai ở `.env`.

### A3. Kiểm terminal trên portal

Đăng nhập <https://sandbox.vnpayment.vn/merchantv2/>, mở
<https://sandbox.vnpayment.vn/merchantv2/Account/TerminalEdit.htm>, xác nhận terminal đang ở
**2.1.0 / HMACSHA512**. Để MD5 là mọi chữ ký bị từ chối, mở link thanh toán ra `Error.html?code=70`.

### A4. Cài cloudflared

```bash
brew install cloudflared
```

---

## B. Mỗi lần chạy

### B1. Bật hạ tầng

```bash
cd ~/Documents/code/self-storage
docker compose up -d
```

Chờ Postgres và Redis `healthy`:

```bash
docker compose ps
```

### B2. Bật tunnel — để terminal này chạy suốt buổi

```bash
cloudflared tunnel --url http://localhost:8080
```

Nó in ra một khung kẻ ô, bên trong có đúng một dòng https:

```
+--------------------------------------------------------------------------+
|  Your quick Tunnel has been created! Visit it at ...                     |
|  https://pink-table-motor-grace.trycloudflare.com                        |
+--------------------------------------------------------------------------+
```

Copy dòng đó. **Đừng đóng terminal này** — đóng là tunnel chết, IPN không về được.

### B3. Khai IPN URL lên portal

Mở <https://sandbox.vnpayment.vn/merchantv2/Account/TerminalEdit.htm>, tìm ô **IPN URL**
(nhãn kiểu "URL nhận kết quả thanh toán"), dán:

```
https://<domain vừa copy>/api/payments/vnpay/ipn
```

Giữ nguyên đuôi `/api/payments/vnpay/ipn`. Bấm Lưu.

> Quick tunnel đổi domain **mỗi lần** chạy lại `cloudflared`. Mỗi lần đổi là phải quay lại bước
> này. Không khai lại thì trả tiền xong hoá đơn vẫn `Unpaid`.

### B4. Chạy backend

```bash
cd ~/Documents/code/self-storage/backend
set -a; source ../.env; set +a
./mvnw spring-boot:run
```

Dòng `set -a; source ../.env; set +a` **bắt buộc**: dự án không có thư viện dotenv nào, Spring
không tự đọc `.env`. Thiếu dòng đó là app chết ngay lúc khởi động.

Chạy xong thấy dòng này là được:

```
Tomcat started on port 8080 (http) with context path '/'
Started SelfStorageApplication in 1.9 seconds
```

Kiểm thêm:

```bash
curl http://localhost:8080/api/health
```

---

## C. Chạy một lượt thanh toán

### C1. Cần một hoá đơn `Deposit` + `Unpaid`

Kiểm xem có sẵn chưa:

```bash
docker compose exec -T postgres psql -U self_storage -d self_storage \
  -c "select i.id, i.code, i.type, i.status, i.amount, a.email
      from invoices i join accounts a on a.id = i.customer_id
      where i.status = 'Unpaid' and i.type = 'Deposit';"
```

Không có dòng nào thì tạo (khoang `A-02`, khách `customer1@lemar.vn`, 10.000đ):

```bash
docker compose exec -T postgres psql -U self_storage -d self_storage <<'SQL'
insert into rental_requests (facility_id, customer_name, normalized_customer_email,
        customer_phone, unit_type_id, start_date, period, unit_id, status)
values (1, 'Nguyen Van A', 'customer1@lemar.vn', '0900000000', 1, current_date, 1, 2, 'Approved');

insert into rental_orders (request_id, customer_id, unit_id, status, expires_at)
values ((select max(id) from rental_requests), 6, 2, 'Pending', now() + interval '7 days');

insert into invoices (order_id, customer_id, code, title, type, status, amount, due_date)
values ((select max(id) from rental_orders), 6, 'INV-DEP-DEMO1', 'Dat coc khoang A-02',
        'Deposit', 'Unpaid', 10000.00, now() + interval '7 days')
returning id;
SQL
```

Số `id` trả về là `invoiceId` dùng ở bước sau. Khoang phải đang `Available` — đổi `unit_id` nếu
`A-02` đã bị giữ:

```bash
docker compose exec -T postgres psql -U self_storage -d self_storage \
  -c "select id, code, status from storage_units order by id;"
```

### C2. Chụp trạng thái DB **trước** khi trả tiền

Có cái này mới chứng minh được IPN thực sự ghi dữ liệu, chứ không phải nó vốn đã vậy.

```bash
docker compose exec -T postgres psql -U self_storage -d self_storage \
  -c "select id, code, status from invoices where id = <invoiceId>;" \
  -c "select id, status, unit_id from rental_orders;" \
  -c "select id, code, status from storage_units where id = 2;" \
  -c "select id, status, gateway_transaction_no, paid_at from payment_transactions;"
```

### C3. Lấy link thanh toán

Mở <http://localhost:8080/swagger-ui/index.html>.

1. `POST /api/auth/login` → **Try it out** → body:

   ```json
   { "email": "customer1@lemar.vn", "password": "Test@1234" }
   ```

   → **Execute** → copy `accessToken` trong response.
2. Bấm nút **Authorize** (góc trên bên phải) → dán `accessToken` → Authorize → Close.
3. `POST /api/invoices/{invoiceId}/pay` → **Try it out** → điền `invoiceId` → **Execute**.
4. Trong response lấy `data.paymentUrl`.

Không bấm Authorize thì dính 401. Bấm Execute rồi mà vẫn thấy chuỗi `vnp_Amount=1000000&...`
dạng rút gọn thì đó là phần ví dụ trong tài liệu, không phải kết quả thật — kéo xuống khối
**Server response** mới là kết quả.

### C4. Trả tiền

Mở `paymentUrl` bằng **trình duyệt thật** (không `curl` — cổng chặn). Chọn **Ngân hàng NCB**,
điền thẻ test:

| Trường | Giá trị |
|---|---|
| Số thẻ | `9704198526191432198` |
| Tên chủ thẻ | `NGUYEN VAN A` |
| Ngày phát hành | `07/15` |
| Mật khẩu OTP | `123456` |

Trả xong trình duyệt nhảy về Return URL, hiện JSON `{"validSignature":true,"responseCode":"00"}`.
Chữ ký đúng. **Nhưng chưa xong** — trạng thái thật do IPN quyết, và IPN có thể về sau vài giây.

### C5. Đối chiếu sau khi trả

Chạy lại đúng khối lệnh ở C2 rồi so với ảnh chụp trước. Phải thấy:

| Bảng | Trước | Sau |
|---|---|---|
| `payment_transactions` | chưa có dòng mới | `Success`, có `gateway_transaction_no` và `paid_at` |
| `invoices` | `Unpaid` | `Paid` |
| `rental_orders` | `Pending` | `Deposited` |
| `storage_units` | `Available` | `Reserved` |

Kiểm thêm thông báo và audit:

```bash
docker compose exec -T postgres psql -U self_storage -d self_storage \
  -c "select id, type, account_id, created_at from notifications order by id desc limit 5;" \
  -c "select id, action, entity_type, entity_id, created_at from audit_logs order by id desc limit 5;"
```

Và đọc lại hoá đơn qua API — đây là đường FE sẽ gọi sau redirect:

```
GET /api/invoices/{invoiceId}
```

Phải trả `status: "Paid"`.

---

## D. Khi nào thì hỏng — và vì sao

| Hiện tượng | Nguyên nhân | Xử lý |
|---|---|---|
| App chết lúc boot, log `must not be blank` | Thiếu biến trong `.env`, hoặc quên `set -a; source ../.env` | Xem A2 và B4 |
| Mở `paymentUrl` ra `Error.html?code=70` | Chữ ký sai: `HASH_SECRET` lệch hoặc terminal còn MD5 | Xem A3 |
| Trả tiền xong, Return URL đúng, nhưng DB vẫn `Unpaid` | IPN không về: tunnel chết, hoặc portal khai domain cũ | Xem B2, B3. VNPay retry 10 lần × 5 phút nên khai lại kịp thì vẫn ăn. Hoặc gọi `POST /api/invoices/{invoiceId}/payment-check` (như trang kết quả FE) để hỏi `querydr` và chốt ngay |
| Trả tiền xong, trình duyệt dừng ở trang JSON `{"validSignature":true,…}` thay vì về web | `VNPAY_RETURN_URL` đang trỏ route backend | Chạy cả FE thì đặt `http://localhost:5173/payments/result` rồi khởi động lại backend |
| Gọi `/pay` trả 401 | Chưa bấm Authorize trên Swagger | Xem C3 bước 2 |
| Gọi `/pay` trả "Hóa đơn này không ở trạng thái chờ thanh toán cọc" | Hoá đơn đã `Paid`/`Canceled`, hoặc không phải loại `Deposit` | Tạo hoá đơn mới, xem C1 |
| Gọi `/pay` trả "Khoang chứa đã được đặt cọc bởi khách hàng khác" | Khoang trong đơn không còn `Available` | Đổi `unit_id`, xem C1 |
| Gọi `/pay` trả "đang trong quá trình xử lý thanh toán" | Còn phiên cũ mở trong 15 phút | Chờ hết 15 phút, hoặc xoá key phiên trong Redis |

---

## E. Dọn dẹp

`Ctrl+C` ở terminal chạy app và terminal chạy `cloudflared`. Hạ tầng cứ để đó, hoặc
`docker compose down` nếu muốn tắt hẳn (dữ liệu vẫn còn trong volume).
