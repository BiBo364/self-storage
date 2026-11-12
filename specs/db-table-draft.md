> Tên bảng trong migration: snake_case số nhiều (accounts, storage_units, …), khớp `@Table` của entity.

# Account
- id
- email (unique, normalized)
- name (nullable) - tên hiển thị; backfill khách từ `RentalRequest.customer_name`, seed cho tài khoản hệ thống; account cũ/test có thể null, API fallback email
- phone (nullable) - số điện thoại liên hệ; backfill từ `RentalRequest.customer_phone`
- email_verified_at (nullable) - thời điểm xác minh email. Luồng đăng ký tự phục vụ chỉ tạo
  `Account` sau khi xác minh (field set ngay khi tạo); account có `null` (legacy/seed/admin tạo tay)
  bị chặn đăng nhập và mọi thao tác nghiệp vụ — chỉ luồng xác minh email hoạt động.
- role_id (N - 1: Role)
- status
  - ACTIVE: đang hoạt động — được đăng nhập/gọi API
  - BANNED: bị admin chặn — không được đăng nhập/gọi API
- created_at

# Role
**Overview:** vai trò trong hệ thống. Mỗi account được gán đúng một role qua `Account.role_id`; Admin quản lý, không hard-code trong source code.
- id
- name (unique) - mã role; seed mặc định 5 role: `ADMIN`, `BOM`, `FM`, `FS`, `CUSTOMER`
- created_at
# Permission
**Overview:** quyền/hành động cụ thể theo model RBAC, gán cho role qua `RolePermission`.
- id
- code (unique) - định danh quyền dạng `<resource>.<action>`, ví dụ `rental_request.approve`
- description
- created_at
# RolePermission
**Overview:** bảng nối N–N giữa `Role` và `Permission` — nguồn dữ liệu duy nhất cho việc role nào có quyền nào.
- role_id (N - 1: Role)
- permission_id (N - 1: Permission)
- scope (GLOBAL/FACILITY) - phạm vi áp dụng của mapping: GLOBAL = hành động toàn cục; FACILITY = bắt buộc truyền `facility_id` và bị kiểm facility scope

**CONSTRAINTS:**
- Unique `(role_id, permission_id)`.

**NOTES:**
- Catalog khởi điểm + mapping mặc định cho 5 role đã chốt (2026-10-08) — nguồn: `backend/docs/permission-catalog.md`; seed `V3__rbac_permissions_seed.sql`. Mở rộng dần khi Flow 3–7 chốt; không hard-code mapping trong source code, thêm quyền mới thì cập nhật qua dữ liệu của `Role`/`Permission`/`RolePermission`.
- `scope` thuộc mapping chứ không thuộc `Permission`: cùng một permission có thể GLOBAL ở role này và FACILITY ở role khác (vd `rental_request.read`: ADMIN/BOM global, FM/FS facility).
- Quản trị hệ thống (`account`/`role`/`permission`) là role-bound qua namespace route, không tạo permission — tránh tự nâng quyền qua dữ liệu mapping.
# Facility
- id
- code (unique)
- name
- address
- operating_hours
- status (Active/Inactive)
- fm_account_id (N - 1: Account, nullable, unique) - FM phụ trách cơ sở; quan hệ 1–1
- enabled_key_access (Boolean, default true) - cơ sở giao khóa cơ (Flow 2.4)
- enabled_code_access (Boolean, default true) - cơ sở cấp mã khóa số (Flow 2.4)

**NOTES:**
- `fm_account_id` là nguồn duy nhất lưu quan hệ FM–Facility (1–1): mỗi facility có tối đa một FM; mỗi FM phụ trách tối đa một facility. Account không lưu `facility_id` cho FM — cần biết FM phụ trách facility nào thì truy vấn ngược từ `Facility`.
- `AccountFacilityAssignment` chỉ dùng cho FS (1–n); không dùng cho FM.
# AccountFacilityAssignment
**Owner:** Flow 5
**Overview:** mapping account (chỉ dùng cho FS) với Facility, phục vụ RBAC data-scope khi một cơ sở có nhiều FS (1–n). Không dùng cho FM.
- account_id (N - 1: Account, role FacilityStaff)
- facility_id (N - 1: Facility)
- assigned_at

**NOTES:**
- Flow 2 chỉ đọc bảng này để validate: FS được gán vào `Appointment.staff_id` phải thuộc đúng cơ sở — `AccountFacilityAssignment.facility_id = Appointment.facility_id`, so trực tiếp không join.
- Định nghĩa đầy đủ và ràng buộc vòng đời thuộc Flow 5.
# UnitType
**Owner:** Flow 4 (bảng giá); Flow 1/2/5 chỉ đọc.
**Overview:** loại khoang chứa (Small/Medium/Large…) và giá thuê áp dụng toàn hệ thống (MVP).
- name (unique) - tên loại khoang
- width, depth, height, area - kích thước (mét; area m2)
- description
- monthly_price - giá thuê toàn hệ thống (MVP, không tách theo facility)
- updated_by (N - 1: Account) - người cập nhật giá lần cuối
- updated_at

**NOTES:**
- Nếu cần giá khác nhau theo chi nhánh: tách bảng riêng theo `facility_id` + `unit_type_id` (chưa hỗ trợ ở MVP).
- Đổi giá không ảnh hưởng hợp đồng đã ký — `RentalContract.monthly_price` là snapshot lúc ký.
- `StorageUnit.monthly_price` là override từng khoang; MVP để null → dùng giá UnitType.

# FacilityUnitType
**Overview:** loại khoang mà mỗi cơ sở cung cấp — form đặt kho và catalog public chỉ hiện loại khoang có ở cơ sở.
Bảng `facility_unit_type` (V4).
- facility_id (N - 1: Facility)
- unit_type_id (N - 1: UnitType)

**CONSTRAINTS:**
- Khoá chính `(facility_id, unit_type_id)`; xoá cơ sở / loại khoang thì xoá dòng nối (`ON DELETE CASCADE`).

**NOTES:**
- FM thêm khoang có loại mới tại cơ sở thì cơ sở tự "cung cấp" loại khoang đó (thêm dòng nếu chưa có).

# StorageUnit
**Overview:** thông tin và trạng thái của khoang chứa tại mỗi cơ sở.

- code - mã khoang (unique theo cơ sở)
- facility_id (N - 1: Facility) - cơ sở quản lý khoang
- unit_type_id (N - 1: UnitType)
- size - kích thước khoang
- monthly_price (Decimal, nullable) - Giá override được BOM phê duyệt
- reserved_order_id (N - 1: RentalOrder, nullable) - đơn đang giữ khoang khi `Reserved`; chỉ đơn này nhả được khoang. NULL ở trạng thái khác (và khoang `Reserved` trước V30 không xác định được đơn giữ)
- maintenance_started_at (nullable) - chỉ do Flow 2.a set khi `Maintenance` phát sinh từ trả kho, để cron tính `unit.maintenance_days`; FM chuyển `Maintenance` do sự cố thì để trống
- status:
  - Available: khoang sẵn sàng cho thuê
  - Reserved: khoang đã được giữ sau khi khách đặt cọc
  - Rented: khoang đã bàn giao và đang được thuê
  - Maintenance: khoang đang bảo trì
- created_at
- updated_at

**CONSTRAINTS:**
- Chỉ khoang có `status = Available` mới được chỉ định hoặc đặt cọc.
- Sau khi đặt cọc thành công: `Available → Reserved`.
- Khi Flow 2 hoàn tất bàn giao: `Reserved → Rented`.
- Khi đơn hàng hết hạn (`RentalOrder.expires_at`) hoặc bị no-show sau hạn đơn: `Reserved → Available`.
- Khoang `Maintenance` không được sử dụng trong quá trình đặt cọc.
- Hạn giữ kho là phần còn lại của hạn đơn (`RentalOrder.expires_at`, tính từ lúc tạo), không cần `hold_expires_at` trên `StorageUnit`.

**NOTES:**
- Nếu `StorageUnit.monthly_price` là `null`, giá hiệu lực lấy từ `UnitType.monthly_price`.
- MVP để `StorageUnit.monthly_price` là `null`; FM không tự chỉnh giá.
- Giá override chỉ áp dụng sau khi BOM phê duyệt.
# RentalRequest
**Overview:** chứa các thông tin được gửi từ form trên website.
- facility_id (N - 1: Facility)
- customer_name
- normalized_customer_email (trim + lowercase)
- customer_phone
- unit_type_id (N - 1: UnitType) - loại khoang khách chọn
- start_date
- period - số tháng thuê
- unit_id (N - 1: StorageUnit) - khoang FM chỉ định lúc duyệt; dùng để tạo ProposalFeedback đầu tiên
- status:
  - Pending: trạng thái mặc định khi tạo
  - Rejected: yêu cầu bị FM từ chối
  - Approved: yêu cầu được FM duyệt
  - Converted: request đã sinh `RentalOrder` (đã liên kết account)
  - Expired: quá expires_at mà chưa liên kết account
- reject_reason (nullable) - mã lý do từ chối
- reject_note (nullable) - ghi chú chi tiết khi cần
- created_at
- responded_at
- expires_at

**CONSTRAINTS:**
- `start_date` không được ở trước ngày hiện tại theo timezone của `Facility`.
- `period` là số nguyên dương.
- `unit_type_id` phải tồn tại (MVP: loại khoang dùng chung toàn hệ thống — ràng buộc "cung cấp tại facility" sẽ bổ sung khi Flow 4/5 chốt).
- Request `Pending` quá `Policy.request.pending_expiry_days` chuyển sang `Expired` (cron). Khi cron chưa chuyển kịp, FM không duyệt được request đã quá `expires_at` — xem Flow 1.1.
- Khi claim, chỉ request `Approved` chưa quá `expires_at` mới được chuyển sang `Converted`.
- `Rejected` phải có `reject_reason`; giá trị chuẩn gồm `UNIT_UNAVAILABLE`, `UNIT_MISMATCH`, `CUSTOMER_REQUEST`, `OTHER`. `reject_note` bắt buộc khi dùng `OTHER`.

**NOTES:**
- `normalized_customer_email` là email đã chuẩn hóa `trim` + lowercase ngay tại điểm nhập (form/đăng ký/import). Đây là **khóa định danh** để nối `RentalRequest` với `Account` (mục 1.2 Case A) và xác minh quyền sở hữu đơn — không chỉ dùng để gửi thông báo.
  - Lý do chuẩn hóa: các email provider thực tế (Gmail, Outlook, ...) xử lý phần local-part không phân biệt hoa/thường. Không chuẩn hóa thì cùng một người có thể match hụt (`John@...` trên form vs `john@...` lúc đăng ký) hoặc sinh trùng request/account. RFC 5321 yêu cầu transport giữ nguyên case — việc chuẩn hóa là policy của ứng dụng, và RFC cũng khuyến khích không khai thác case sensitivity.
  - Quy ước: chỉ `trim` + lowercase. Không bỏ dấu chấm hay `+tag` — đó là hành vi riêng của Gmail, không phải chuẩn chung.
# RentalOrder
**Overview:** chứa các thông tin đơn hàng đã được `Approved` từ FM, sử dụng cho việc hẹn lịch của FS và khách hàng để tư vấn, ký hợp đồng, xem khoang tại kho bao gồm các thông tin:
- id - dùng làm mã đơn hiển thị cho khách trong thông báo/email
- request_id (1 - 1: RentalRequest)
- customer_id (N - 1: Account)
- unit_id (N - 1: StorageUnit, null cho đến khi việc chỉ định khoang chứa giữa FM và Khách hàng hoàn thành)
- cancel_reason
- status:
  - Pending: trạng thái mặc định khi tạo đơn hàng
  - Deposited: Khách hàng đã đặt cọc
  - Scheduled: Khách đã lên lịch hẹn
  - InProgress: Đơn hàng đang được xử lý (đã có `Appointment.staff_id` được phân công)
  - Canceled: Hủy đơn hàng — khách/FM hủy hoặc quá hạn (lý do ghi trong `cancel_reason`)
  - Done: khách đã hoàn tất check-in, ký hợp đồng, thanh toán cần thiết và nhận bàn giao khoang
- expires_at (nullable)
  - Hạn hoàn thành của đơn, set một lần khi tạo đơn (`now + order.expiry_days`); quá hạn mà chưa `Done` → `Canceled`
  - Mọi hạn con gắn với đơn (hóa đơn, lịch hẹn, `due_at`) không được vượt `expires_at`
- canceled_at (Timestamptz, nullable, V28) - lúc đơn chuyển `Canceled`; mốc ghi nhận cọc bị giữ lại trong báo cáo doanh
  thu. Đơn huỷ trước V28 lấy từ audit `RENTAL_ORDER_CANCELED`
- needs_reproposal (BOOLEAN, default false): Flow 2 bật khi khách từ chối khoang tại check-in; gate đề xuất lại
  của Flow 1.3 đọc cờ này và clear khi proposal mới được tạo

## Cancellation Cascade

Mọi nhánh chuyển `RentalOrder` sang `Canceled` đều chạy các bước dưới đây. Lý do ghi vào `cancel_reason` (chuỗi hiển thị cho khách, FM, FS):

| Lý do (`cancel_reason`) | Nguồn | Title notification |
|---|---|---|
| Khách hủy đơn | Flow 2.5 | “Đơn thuê kho đã bị hủy” |
| Khách không còn nhu cầu | Flow 1.3 — FM hủy sau khi liên hệ | “Đơn thuê kho đã bị hủy” |
| Từ chối quá số khoang cho phép | Flow 1.3, Flow 2.5 | “Đơn thuê kho đã bị hủy” |
| Khách không ký hợp đồng | Flow 2.5 | “Đơn thuê kho đã bị hủy” |
| Quá hạn xác nhận đề xuất | Cron catch-all | “Đơn thuê kho đã hết hạn” |
| Quá hạn thanh toán cọc | Cron hóa đơn, catch-all | “Đơn thuê kho đã hết hạn” |
| Quá hạn đặt lịch check-in | Cron catch-all | “Đơn thuê kho đã hết hạn” |
| Không đến nhận kho quá hạn giữ | Cron no-show, catch-all | “Đơn thuê kho đã hết hạn” |
| Chưa hoàn tất bàn giao trong hạn | Cron handover, catch-all | “Đơn thuê kho đã hết hạn” |
| Quá hạn thanh toán tháng đầu | Cron hóa đơn | “Đơn thuê kho đã hết hạn” |

Các bước:
- `RentalOrder.status -> Canceled`, ghi `cancel_reason`.
- `ProposalFeedback` đang `Pending` hoặc `Agreed` chuyển sang `Canceled`.
- `RentalContract` đang `Draft`/`Signed` chuyển `Canceled`; `Invoice` `Unpaid` gắn đơn chuyển `Canceled` (trừ invoice đã `Expired` theo cron, giữ nguyên; khoản đã thu xử lý theo policy).
- Khoang được giải phóng: `StorageUnit.status -> Available`.
- `HandoverRecord` đang mở (`IN_PROGRESS`) chuyển `CANCELED`.
- `Appointment` chưa diễn ra (`Pending`) chuyển `Canceled` kèm `cancel_reason`; lịch đã `Done` giữ nguyên.
- Tiền cọc xử lý theo chính sách Flow 4.
- Ghi `AuditLog` cho từng entity bị thay đổi.
- Tạo notification `RENTAL_ORDER_CANCELED` cho khách và FM, title theo bảng trên, body có mã đơn cùng lý do.
- Không tạo proposal hoặc Appointment mới.

**NOTES:**
- Trạng thái đơn hàng:
  ```text
  Pending ──đặt cọc──> Deposited ──đặt lịch──> Scheduled
                                                │
                                      FM phân công FS
                                                ▼
                                            InProgress
                                                │
                                  hoàn tất check-in/bàn giao
                                                ▼
                                               Done
  ```
  - Các nhánh ngoại lệ (mọi chuyển sang `Canceled` đều ghi `cancel_reason`):
    - Pending → Canceled, lý do "Quá hạn thanh toán cọc": khách không thanh toán cọc đúng hạn.
    - Pending → Canceled, lý do "Quá hạn xác nhận đề xuất": khách không phản hồi proposal trong hạn đơn.
    - Deposited → Canceled, lý do "Quá hạn đặt lịch check-in": hết thời hạn giữ kho nhưng chưa đặt lịch.
    - Scheduled/InProgress → Deposited: khách no-show nhưng vẫn còn trong thời hạn giữ kho.
    - Scheduled/InProgress → Canceled, lý do "Không đến nhận kho quá hạn giữ": khách no-show và đã hết thời hạn giữ kho.
    - Pending/Deposited/Scheduled/InProgress → Canceled: đơn bị khách/FM hủy.
# RentalContract
**Overview:** hợp đồng thuê, được sinh và ký on-site ở Flow 2.3 sau khi khách xác nhận hiện trạng khoang. `Invoice.contract_id` tham chiếu tới bảng này.
- order_id (N - 1: RentalOrder)
- customer_id (N - 1: Account)
- unit_id (N - 1: StorageUnit)
- code
- terms_version - snapshot version điều khoản khách đã đồng ý (Flow 4)
- monthly_price - giá thuê chốt tại thời điểm ký
- deposit_amount - tiền cọc đã thu ở Flow 1.3
- period - số tháng thuê
- start_date - mốc bắt đầu tính tiền thuê, mặc định theo `contract.start_date_rule` của Flow 4
- start_date_override_requested (Date, nullable) - ngày FS đề nghị thay cho ngày theo chính sách
- start_date_override_reason (Text, nullable) - lý do FS thỏa thuận riêng với khách
- start_date_override_status (nullable: Pending/Approved/Rejected) - `Pending` thì chặn bước ký cho tới khi FM xử lý
- end_date
- signed_at
- signature - URL ảnh chữ ký, chỉ dùng khi ký trên thiết bị; MVP ký giấy nên để trống (chữ ký nằm trong bản scan ở `pdf_url`)
- document_url - file PDF hợp đồng được render từ template và thông tin của phiên (bản gốc dùng để đối chiếu, không ký)
- pdf_url - file hợp đồng lưu trữ đã ký
- document_html (Text, nullable, V27) - bản hợp đồng HTML render một lần lúc ký và chốt lại; endpoint in hợp đồng trả bản này sau khi ký. `NULL` = chưa ký (render động) hoặc ký trước V27
- access_suspended_at (Timestamptz, nullable, V27) - mốc tạm khoá truy cập do quá hạn kéo dài (`overdue.escalation_days`); mã khoá số đã thu hồi; `NULL` = không khoá
- status (Draft/Signed/Active/Ended/Canceled)
  - Signed: đã ký nhưng chưa bàn giao
  - Active: đã bàn giao, đang có hiệu lực

**CONSTRAINTS:**
- Một `RentalOrder` chỉ có tối đa một `RentalContract` đang xử lý (`status IN (Draft, Signed, Active)`).
- Hợp đồng `Canceled` / `Ended` giữ lại làm lịch sử, không tính vào ràng buộc.
# Invoice
**Overview:** chứa thông tin thanh toán của khách hàng (hóa đơn)
- order_id (N - 1: RentalOrder) -> null as default
- contract_id (N - 1: RentalContract) -> null as default
- customer_id (N - 1: Account)
- code
  - Cấu trúc mã hóa đề xuất: Gợi nhớ & Dễ lọc, để thuận tiện tuyệt đối khi kiểm tra, mã hóa đơn nên mang ý nghĩa phân loại theo công thức:
    ```
    INV-{service_code}-{facility_code}-{YYMMDD}-{rand}
    ```
    - Ví dụ:
      + INV-DEP-Q7-260911-A89F: Hóa đơn cọc (DEP), chi nhánh Quận 7, ngày 11/09/2026.
      + INV-RNT-TD-261001-K312: Hóa đơn tiền thuê định kỳ (RNT), chi nhánh Thủ Đức.
      + INV-EXT-Q7-260915-091B: Hóa đơn dịch vụ ngoài / sự cố khóa (EXT).
- title
- type (Deposit/DepositSupplement/Rental/Extension/Penalty/Service) - xác định nguồn gốc hóa đơn
- description
- status:
  - Unpaid: Hóa đơn chưa được thanh toán
  - Paid: Hóa đơn đã được thanh toán
  - Canceled: Hóa đơn đã bị hủy và vô hiệu hóa
  - Expired: Hóa đơn quá hạn thanh toán
- amount - snapshot của giá hiệu lực tại thời điểm tạo hóa đơn.
- created_at
- due_date
- refund_status (None/Required/Processing/Refunded) — tiền đã vào nhưng cần hoàn; tách khỏi `status`. `Processing`
  = đã ghi nhận hoàn một phần, `Refunded` = đã hoàn đủ số phải hoàn
- refund_reason (nullable) - mã lý do (`UNIT_UNAVAILABLE`, `INVOICE_CLOSED`, `UNIT_CHANGE_EXCESS`, `DEPOSIT_UNSERVED`,
  `HANDOVER_CANCELED`, `CHECKOUT_DEPOSIT`)
- refund_requested_at (nullable)
- refunded_at (nullable)
- refund_amount (Decimal, nullable, V31) - số phải hoàn khi chỉ hoàn một phần; `NULL` = cả số tiền hoá đơn

**Constraints**
- Một `RentalOrder` tối đa một hoá đơn `Deposit` còn hiệu lực (`Unpaid`/`Paid`).
- Một `RentalOrder` tối đa một hoá đơn `DepositSupplement` `Unpaid`.
- Hoá đơn bổ sung dùng type `DepositSupplement`, không dùng lại `Deposit`.

**Service Code:**
- DEP: đặt cọc khoang chứa (`Deposit`, `DepositSupplement`)
- RNT: tiền thuê — tháng đầu lúc ký hợp đồng và các tháng sau do cron lập (`Rental`)
- EXT: gia hạn hợp đồng (`Extension`)
- OVD: phí quá hạn hợp đồng (`Penalty`)
- CLN: phí vệ sinh khoang khi trả kho (`Penalty`)
- DMG: phí hư hỏng khi trả kho (`Penalty`)
- KEY: phí mất chìa khi trả kho (`Penalty`)
- PEN: quyết toán trả kho — hoá đơn chưa trả gộp vào và phần còn thiếu khi phí vượt cọc (`Penalty`)
- SVC: phí dịch vụ của yêu cầu hỗ trợ (`Service`)


**NOTES:**
- Nguồn gốc hóa đơn xác định qua FK (`order_id` / `contract_id`; bổ sung `support_request_id` khi cần): **đúng một FK được set** (CHECK constraint). `type` là loại hóa đơn.
# ProposalFeedback
**Overview:** chứa thông tin feedback từ khách hàng sau khi khoang chứa được chỉ định từ FM
- order_id (N - 1: RentalOrder)
- customer_id (N - 1: Account)
- unit_id (N - 1: StorageUnit)
- status (Pending/Agreed/Rejected/Canceled)
- note
- created_at
- agreed_at (Timestamptz, nullable, V28) - lúc khách đồng ý; mốc tính lại cửa sổ đặt lịch check-in khi đơn đã cọc phải
  đổi khoang

**Constraints**
- Mọi lần đề xuất lại đều tạo bản ghi mới cho cùng `RentalOrder`; bản ghi cũ giữ nguyên (lịch sử). Khoang hiệu lực = proposal `Agreed` mới nhất.
- 1 order có thể có nhiều proposal `Agreed` theo thời gian (khách đồng ý online nhưng từ chối khoang lúc check-in).
- Khi re-propose, mặc định không được chọn lại `unit_id` đã bị khách từ chối trong cùng order.
- FM có thể override rule này nhưng phải nhập lý do; thao tác được ghi vào `AuditLog`.

**NOTES:**
- field note dùng để khi khách từ chối và muốn chọn lại, sẽ nêu lý do vì sao từ chối, ...
# TODO: FacilityTask
# Appointment
**Overview:** lịch hẹn dùng chung cho các cuộc hẹn tại cơ sở. Trong Flow 1, lịch hẹn được tạo cho việc check-in; các flow khác có thể sử dụng cho bàn giao, trả kho hoặc xử lý sự cố.

- customer_id (N - 1: Account)
- facility_id (N - 1: Facility)
- staff_id (N - 1: Account, nullable khi chưa được phân công)
- type (CHECKIN, HANDOVER, RETURN)
- cancel_reason (no-show ghi "Quá giờ và khách không đến")
- date
- started_at (NOT NULL từ V15)
- end_at (NOT NULL từ V15)
- arrived_at
- status:
  - Pending: trạng thái mặc định, trước khi khách đến
  - Done: đã ghi nhận khách đến cơ sở; các bước check-in và bàn giao tiếp tục được theo dõi bằng `HandoverRecord`
  - Canceled: lịch hẹn bị hủy
- created_at
- updated_at

**CONSTRAINTS:**
- Không chuyển `Appointment` sang `Canceled` khi `HandoverRecord` liên kết vẫn là `IN_PROGRESS`.
- `Appointment` (type `CHECKIN`) của một `RentalOrder` phải có thời điểm hẹn ≤ `RentalOrder.expires_at` — áp cho cả lịch tạo lại (no-show, quá hạn thanh toán, re-propose).
# RentalAppointment
**Overview:** nối lịch hẹn với đơn hàng.

- order_id (N - 1: RentalOrder)
- appointment_id (1 - 1: Appointment)
# UnitAccessKey
**Overview:** quyền truy cập khoang chứa đã bàn giao cho khách.
- unit_id (N - 1: StorageUnit)
- contract_id (N - 1: RentalContract)
- access_type (PhysicalKey/AccessCode) - loại khóa đã bàn giao, theo cờ `enabledKeyAccess`/`enabledCodeAccess` của cơ sở/khoang (Flow 5)
- quantity - số chìa đã giao, dùng khi `access_type = PhysicalKey`
- code_hash - hash của mã truy cập, dùng khi `access_type = AccessCode`
- issued_at, revoked_at
- status (Active/Revoked/Lost) - trạng thái của quyền truy cập được giao, không phải của vật (chìa) hay bí mật (mã)
  - Active: quyền đang hiệu lực — khách đang giữ chìa, hoặc mã còn dùng được
  - Revoked: quyền đã đóng — chìa đã thu về cơ sở, hoặc mã đã bị vô hiệu; `revoked_at` ghi thời điểm đóng
  - Lost: quyền CHƯA đóng được — chìa không thu lại được nên người ngoài vẫn có thể mở; phải thay khóa mới đóng được. Chỉ áp cho `PhysicalKey`.

**CONSTRAINTS:**
- Mỗi hợp đồng có tối đa một dòng `Active` cho mỗi `access_type` (`PhysicalKey`, `AccessCode`).
- Cấp lại mã khóa số: tạo dòng mới và chuyển dòng cũ sang `Revoked` trong cùng thao tác; không ghi đè `code_hash`.
- Phát thêm chìa cơ: cập nhật `quantity` trên dòng `Active` hiện có; thay đổi ghi `AuditLog` kèm `old_value`/`new_value`.
- `Lost` chỉ áp cho `PhysicalKey` — nghĩa là chìa không thu lại được. Có dòng `Lost` thì khoang không được cho thuê lại cho tới khi thay khóa xong.

**NOTES:**
- Dòng `Lost` giữ nguyên làm lịch sử, không đổi trạng thái sau khi thay khóa. Việc đã thay khóa thể hiện ở chỗ khoang được mở lại `Maintenance` -> `Available`, không phải ở dòng này.
# HandoverRecord
**Overview:** theo dõi tiến trình check-in và bàn giao khoang chứa. Flow 1 tạo bản ghi khi lịch hẹn check-in được tạo; Flow 2 sử dụng và cập nhật bản ghi trong quá trình xử lý tại cơ sở.

- order_id (N - 1: RentalOrder)
- appointment_id (1 - 1: Appointment)
- unit_id (N - 1: StorageUnit)

- identity_status (Pending/Verified/Failed) - xác minh danh tính người đến check-in
- identity_verified_at (nullable)
- identity_photos - List<String> (nullable, V33), ảnh CCCD/hộ chiếu FS chụp ở lần xác minh gần nhất
- inspection_status (Pending/Agreed/Rejected) - hiện trạng kho được khách xác nhận
- unit_inspected_at (nullable)
- inspection_notes (nullable) - chỉ cho ghi chú hiện trạng
- inspection_photos - List<String> (nullable), ảnh chụp thực tế lúc check-in

- result
  - IN_PROGRESS: trạng thái mặc định
  - COMPLETED: tất cả các items trong checklist của bảng này đều đã được tick
  - REJECTED: khách từ chối khoang
  - CANCELED: biên bản bị đóng mà không nhận khoang — khách hủy đơn, khách không đến, quá hạn thanh toán hoặc quá hạn hoàn tất bàn giao.
- completed_at (nullable)
- reject_reason (nullable, Text) - lý do từ chối hoặc hủy biên bản
- created_at
- updated_at
- due_at - hạn khách phải hoàn tất bàn giao, set khi ghi nhận khách đến
- requested_access (nullable: AccessCode/PhysicalKey/Both) - hình thức nhận quyền truy cập khách chọn khi xác nhận biên bản
- customer_confirmed_at (nullable) - lúc khách xác nhận biên bản trong tài khoản

**NOTES:**
- `HandoverRecord` là checklist tiến trình bàn giao theo **thứ tự bắt buộc**: xác minh danh tính → kiểm tra hiện trạng khoang → ký hợp đồng → thanh toán tháng đầu → bàn giao.
- Để `result = COMPLETED`, tất cả các mục bắt buộc trong checklist phải được hoàn tất. Trường hợp `REJECTED` hoặc `CANCELED` có thể kết thúc khi checklist chưa hoàn tất và phải ghi nhận lý do tương ứng.
- `HandoverRecord` chỉ giữ trạng thái thuộc chính phiên bàn giao: `identity_status`, `inspection_status`.
  Trạng thái hợp đồng và thanh toán **suy từ** `RentalContract` / `Invoice`; không nhân bản vào bảng này
  để tránh nguồn sự thật thứ hai.
- Màn hình cần trạng thái tổng hợp (FM skim/filter) dùng **read model**. MVP: một query tổng hợp
  dùng chung trong code; chỉ nâng lên DB view khi có consumer thứ hai (báo cáo, BI, service khác).
- Trạng thái hiển thị của biên bản (màn FM skim/filter) ghép theo thứ tự ưu tiên:
  hủy → từ chối khoang → đã bàn giao → quá hạn (chỉ khi `result = IN_PROGRESS`)
  → chờ bàn giao khóa → chờ thanh toán → chờ ký → đang kiểm tra khoang
  → đang xác minh danh tính → chờ khách đến.

**CONSTRAINTS:**
- Chỉ chuyển bước sau khi bước trước đạt: `identity_status = Verified` → `inspection_status = Agreed` → hợp đồng `Signed` → hóa đơn tháng đầu `Paid` → bàn giao.
- `due_at` chỉ có giá trị khi `arrived_at` đã được ghi; không đổi sau khi set.
- Một `Appointment` chỉ có tối đa một `HandoverRecord`.
- Một `RentalOrder` có thể có nhiều `HandoverRecord` nếu khách từ chối khoang, no-show, quá hạn hoàn tất hoặc phải đặt lại lịch.
- Một `RentalOrder` chỉ có tối đa một `HandoverRecord` đang mở (`result = IN_PROGRESS`).
- MVP chỉ có tối đa một appointment `CHECKIN` đang hoạt động cho mỗi `RentalOrder`; reschedule tạo nhiều appointment chỉ được bật ở giai đoạn sau.
- Reschedule không thuộc MVP. Nếu được bật ở giai đoạn sau, appointment cũ phải chuyển `Canceled` trước khi tạo appointment mới.
# ReturnRequest
**Overview:** yêu cầu trả kho của khách trên một hợp đồng `Active` (Flow 3). Bảng `return_requests` (V25).
- contract_id (N - 1: RentalContract)
- customer_id (N - 1: Account)
- facility_id (N - 1: Facility)
- preferred_date - ngày khách mong muốn, trong 30 ngày tới
- note (nullable)
- status (`Pending`/`Assigned`/`Completed`/`Canceled`) - default `Pending`
- assigned_staff_id (N - 1: Account, nullable) - FS được FM phân công
- appointment_id (N - 1: Appointment, nullable) - lịch `RETURN` hiện tại
- cancel_reason (nullable)
- no_show_count (Int, default 0, V28) - số lần khách không đến buổi trả kho; chạm `return.max_no_shows` → huỷ yêu cầu
- created_at, assigned_at, completed_at, updated_at

**CONSTRAINTS:**
- Mỗi hợp đồng tối đa một yêu cầu `Pending`/`Assigned` (partial unique index `uq_return_requests_open_per_contract`).

# CheckoutRecord
**Overview:** biên bản trả kho. Tách riêng khỏi `HandoverRecord` vì `HandoverRecord` chỉ chịu trách nhiệm tới khâu bàn
giao. Bảng `checkout_records` (V25) theo dạng rút gọn của Flow 2.a (quyết định của chủ dự án) — hành vi ở
`backend/docs/checkout/return.md`.
- return_request_id (1 - 1: ReturnRequest, unique)
- contract_id (N - 1: RentalContract)
- unit_id (N - 1: StorageUnit)
- appointment_id (N - 1: Appointment, nullable) - lịch `RETURN` của buổi trả kho
- is_unit_emptied (default false), unit_emptied_at (nullable)
- cleaning_required (default false)
- damage_amount (default 0, ≥ 0) - FS nhập số tiền hư hỏng
- damages (nullable) - mô tả hư hỏng
- returned_key_quantity (nullable, ≥ 0) - số chìa thu lại
- photos - List<String>, ảnh hiện trạng lúc trả
- note (nullable)
- deposit_amount, total_charges, refund_amount (nullable) - kết quả quyết toán cọc
- shortfall_invoice_id (N - 1: Invoice, nullable) - hoá đơn phần phí vượt cọc
- result (`IN_PROGRESS`/`PENDING_ITEMS`/`AWAITING_PAYMENT`/`COMPLETED`) - default `IN_PROGRESS`
- inspected_at, access_revoked_at, completed_at (nullable)
- created_at, updated_at

**NOTES:**
- **Không có `staff_id`**, giống `HandoverRecord`: FS lấy qua `ReturnRequest.assigned_staff_id` / `appointment_id`.
- `PENDING_ITEMS`: khoang còn tài sản, chưa chốt được, **chưa thu hồi `UnitAccessKey`** vì khách còn cần vào lấy đồ.
  Khách quay lại dọn thì FS kiểm lại trên **cùng một bản ghi** (`PENDING_ITEMS -> IN_PROGRESS`), không tạo bản ghi
  hay lịch `RETURN` mới.
- `AWAITING_PAYMENT`: tổng phí vượt cọc, chờ khách trả `shortfall_invoice_id`; trả xong thì `COMPLETED`.
- `COMPLETED`: quyền truy cập đã thu hồi, cọc đã quyết toán; khoang chuyển `Maintenance`.
- Khác bản nháp 2.a: không có checklist cờ `is_inspected`/`is_access_revoked`/`is_fee_settled`/`is_deposit_settled`
  (trạng thái suy ra từ `result`, `access_revoked_at`, `shortfall_invoice_id`); không có `cleanliness` (thay bằng
  `cleaning_required`) và `customer_signature`; liên kết qua `return_request_id` thay vì `order_id`.
# PaymentTransaction
- invoice_id (N - 1: Invoice)
- gateway_transaction_no (unique) - Mã giao dịch định danh từ cổng thanh toán/ngân hàng trả về (ví dụ mã vnpay_TransactionNo, payOS reference code, ...) -> Dùng để tra cứu, đối soát khi có khiếu nại
- transaction_content
- response_payload: JSON / TEXT, nullable -> Lưu toàn bộ log raw webhook/IPN để đối soát
- vnp_txn_ref - mã tham chiếu merchant gửi sang VNPay, không trùng trong ngày -> dùng để tra cứu IPN và chống xử lý trùng (idempotent)
- amount
- direction (PAY/REFUND)
- failure_reason
- paid_at
- created_at
- status (Pending/Failed/Success/Duplicate)
# PaymentReconciliationCase
- transaction_id (1 - 1: PaymentTransaction)
- invoice_id
- status (Open/Resolved)
- detected_at
- notified_at
- resolved_at
- resolved_by_email
- resolution (Success/Failed/Duplicate)
- gateway_transaction_no
- paid_at
- note
- evidence_url
- created_at
- updated_at
# AuditLog
**Overview:** lưu lịch sử các thao tác nghiệp vụ nhạy cảm để đối chiếu khi có tranh chấp hoặc lỗi.

- id
- actor_account_id (nullable; null nếu do hệ thống, cron hoặc IPN)
- action (các loại action ghi ở phần NOTES)
- entity_type (String, controlled value) - loại entity bị tác động
- entity_id (String/Text) - ID của entity, lưu dưới dạng chuỗi
- old_value (JSON, nullable)
- new_value (JSON, nullable)
- reason (nullable)
- created_at

**CONSTRAINTS:**
- AuditLog là append-only.
- `entity_type` và `entity_id` do backend tạo, không nhận trực tiếp từ client.
- `action` phải khớp với loại entity trong Audit action catalog.
- Không ghi mật khẩu, secret key, số thẻ hoặc raw payment payload.
- Không ghi thao tác đọc dữ liệu hoặc click giao diện.

**NOTES:**
- `AuditLog` lưu trong database để theo dõi các thao tác nghiệp vụ nhạy cảm. Application log dùng cho lỗi kỹ thuật và không thay thế `AuditLog`. Mọi thao tác làm thay đổi trạng thái, quyền sở hữu hoặc tiền phải tạo một `AuditLog` theo catalog dưới đây. Nếu một thao tác làm thay đổi nhiều entity, tạo một bản ghi cho mỗi entity bị thay đổi.
- Audit action catalog:

| Action | Khi nào ghi | Entity | Actor |
|---|---|---|---|
| `RENTAL_REQUEST_APPROVED` | FM duyệt yêu cầu | `RentalRequest` | FM |
| `RENTAL_REQUEST_REJECTED` | FM từ chối yêu cầu | `RentalRequest` | FM |
| `RENTAL_REQUEST_CLAIMED` | Khách claim đơn sau khi verify email | `RentalRequest` | Customer/System |
| `PROPOSAL_AGREED` | Khách đồng ý proposal | `ProposalFeedback` | Customer |
| `PROPOSAL_REJECTED` | Khách từ chối proposal | `ProposalFeedback` | Customer |
| `PROPOSAL_REPROPOSED` | FM đề xuất khoang khác | `ProposalFeedback` | FM |
| `PROPOSAL_CANCELED` | Proposal bị huỷ do khoang hết khả dụng lúc khách đồng ý hoặc đơn bị huỷ | `ProposalFeedback` | Customer/System |
| `INVOICE_CREATED` | Hệ thống tạo hóa đơn cọc | `Invoice` | Customer/FM |
| `INVOICE_CANCELED` | Hóa đơn bị vô hiệu hóa | `Invoice` | Customer/FM |
| `INVOICE_PAID` | Hóa đơn được cổng xác nhận đã thanh toán | `Invoice` | System |
| `INVOICE_EXPIRED` | Hóa đơn quá `due_date` chưa thanh toán (cron) | `Invoice` | System |
| `INVOICE_REFUND_REQUIRED` | Tiền đã vào phải trả lại khách (unit mất, hoá đơn đã đóng, cọc dư, hoàn cọc khi trả kho…); `refund_status = Required` | `Invoice` | System/Customer/FM/FS |
| `PAYMENT_SUCCEEDED` | Thanh toán được xác nhận | `PaymentTransaction` | System/IPN/Admin |
| `PAYMENT_FAILED` | Thanh toán thất bại | `PaymentTransaction` | System/IPN/Admin |
| `PAYMENT_DUPLICATE` | Giao dịch Success thứ hai cho cùng invoice; không ghi Success lần hai | `PaymentTransaction` | System/IPN/Admin |
| `RENTAL_ORDER_DEPOSITED` | Đơn chuyển sang đã đặt cọc | `RentalOrder` | System |
| `STORAGE_UNIT_RESERVED` | Khoang chuyển sang Reserved | `StorageUnit` | System |
| `MANUAL_REFUND_RECORDED` | FM ghi nhận hoàn tiền thủ công (`POST /api/fm/invoices/{id}/refunds`) | `PaymentTransaction` | FM |
| `DEPOSIT_REFUND_REQUIRED` | Đổi khoang sau khi cọc dư — cần hoàn tiền | `RentalOrder` | Customer |
| `APPOINTMENT_CREATED` | Tạo lịch hẹn check-in / trả kho | `Appointment`, `RentalOrder` | Customer/FM/System |
| `FS_ASSIGNED` | FM phân công FS | `Appointment`, `RentalOrder` | FM |
| `APPOINTMENT_RESCHEDULED` | Lịch hẹn được đặt lại | `Appointment` | Customer/FM |
| `APPOINTMENT_CANCELED_NO_SHOW` | Cron xử lý khách không đến | `Appointment` | System |
| `APPOINTMENT_CANCELED` | Hủy lịch Pending (cancellation cascade hoặc FS chủ động) | `Appointment` | System, FS |
| `APPOINTMENT_ARRIVED` | FS xác nhận khách đến (Pending → Done) | `Appointment` | FS |
| `IDENTITY_VERIFIED` | FS xác minh danh tính người đến | `HandoverRecord` | FS |
| `UNIT_INSPECTED` | Khách xác nhận hiện trạng khoang | `HandoverRecord` | FS |
| `INSPECTION_CORRECTED` | FS sửa hiện trạng sau khi khách đã đồng ý (lý do bắt buộc) | `HandoverRecord` | FS |
| `HANDOVER_REJECTED` | Khách từ chối khoang tại chỗ | `HandoverRecord` | FS |
| `RENTAL_ORDER_CANCELED` | Hủy đơn do khách/FM/hệ thống: quá ngưỡng từ chối, quá hạn hoặc khách không còn nhu cầu | `RentalOrder`, `StorageUnit`, `ProposalFeedback`, `RentalContract` | Customer/FM/System |
| `HANDOVER_COMPLETED` | Hoàn tất bàn giao, đủ các cờ bắt buộc | `HandoverRecord` | FS/System |
| `START_DATE_OVERRIDE_REQUESTED` | FS đề nghị đổi mốc tính tiền | `RentalContract` | FS |
| `START_DATE_OVERRIDE_APPROVED` | FM duyệt đổi mốc tính tiền | `RentalContract` | FM |
| `START_DATE_OVERRIDE_REJECTED` | FM từ chối đổi mốc tính tiền | `RentalContract` | FM |
| `CONTRACT_SIGNED` | FS ghi nhận khách đã ký hợp đồng | `RentalContract` | FS |
| `CONTRACT_ACTIVATED` | Hợp đồng có hiệu lực sau bàn giao | `RentalContract` | System |
| `CONTRACT_CANCELED` | Hủy hợp đồng do quá hạn thanh toán, khách từ chối ký hoặc theo cancellation cascade | `RentalContract` | System |
| `HANDOVER_CANCELED` | Đóng biên bản do quá hạn thanh toán, khách hủy, no-show hoặc theo cancellation cascade | `HandoverRecord` | System |
| `ACCESS_KEY_ISSUED` | Bàn giao chìa hoặc mã truy cập | `UnitAccessKey` | FS/System |
| `STORAGE_UNIT_RENTED` | Khoang chuyển `Rented` sau bàn giao | `StorageUnit` | System |
| `RENTAL_ORDER_DONE` | Đơn chuyển `Done` sau bàn giao | `RentalOrder` | System |
| `HANDOVER_RECORD_CREATED` | Tạo hồ sơ bàn giao | `HandoverRecord` | System |
| `CHECKOUT_INSPECTED` | FS chốt kiểm tra hiện trạng lúc trả | `CheckoutRecord` | FS |
| `CHECKOUT_COMPLETED` | Hoàn tất biên bản trả kho | `CheckoutRecord` | FS |
| `ACCESS_KEY_REVOKED` | Thu hồi chìa hoặc vô hiệu hóa mã | `UnitAccessKey` | FS |
| `CONTRACT_ENDED` | Hợp đồng đóng sau khi trả kho | `RentalContract` | System |
| `STORAGE_UNIT_MAINTENANCE` | Khoang chuyển `Maintenance` sau trả kho | `StorageUnit` | System |
| `STORAGE_UNIT_AVAILABLE` | Cron mở lại khoang sau bảo trì hoặc giải phóng khoang khi đơn bị hủy | `StorageUnit` | System |
| `UNIT_CHANGE_RELEASED` | Nhả khoang cũ khi khách đổi khoang sau khi đã cọc | `StorageUnit` | Customer |
| `ACCOUNT_REGISTERED` | Tạo tài khoản mới | `Account` | Customer/Admin |
| `RENTAL_REQUEST_CONVERTED` | Request đã duyệt được convert thành đơn + proposal | `RentalRequest` | FM/System |
| `RENTAL_REQUEST_EXPIRED` | Cron chuyển request `Pending`/`Approved` quá `expires_at` sang `Expired` | `RentalRequest` | System |
| `RENTAL_ORDER_CREATED` | Tạo `RentalOrder` khi convert | `RentalOrder` | FM/System |
| `PROPOSAL_CREATED` | Tạo proposal đầu tiên khi convert | `ProposalFeedback` | FM/System |
| `CONTRACT_EXTENDED` | Hoá đơn gia hạn `Paid` → cộng kỳ thuê | `RentalContract` | System |
| `CONTRACT_ACCESS_SUSPENDED` | Cron tạm khoá truy cập (thu hồi mã khoá số) do quá hạn kéo dài | `RentalContract` | System |
| `CONTRACT_ACCESS_RESTORED` | Cron mở lại truy cập khi không còn phí quá hạn chưa trả và hợp đồng hết quá hạn hoặc có yêu cầu trả kho mở | `RentalContract` | System |
| `OVERDUE_FEE_ACCRUED` | Cron tạo / cập nhật hoá đơn phí quá hạn | `Invoice` | System |
| `RETURN_REQUESTED` | Khách gửi yêu cầu trả kho | `ReturnRequest` | Customer |
| `RETURN_ASSIGNED` | FM phân công FS, tạo lịch `RETURN` | `ReturnRequest` | FM |
| `RETURN_CANCELED` | Khách huỷ yêu cầu trả kho | `ReturnRequest` | Customer |
| `RETURN_COMPLETED` | Trả kho hoàn tất | `ReturnRequest` | System |
| `RETURN_NO_SHOW` | Cron: khách không đến buổi trả kho — yêu cầu về `Pending` hoặc bị huỷ khi chạm ngưỡng | `ReturnRequest` | System |
| `SUPPORT_REQUEST_CREATED` | Khách gửi yêu cầu hỗ trợ | `SupportRequest` | Customer |
| `SUPPORT_REQUEST_ASSIGNED` | FM giao FS xử lý | `SupportRequest` | FM |
| `SUPPORT_REQUEST_RESOLVED` | FS / FM ghi kết quả xử lý | `SupportRequest` | FS/FM |
| `SUPPORT_REQUEST_CANCELED` | Khách huỷ yêu cầu hỗ trợ | `SupportRequest` | Customer |
| `FACILITY_CREATED` | BOM tạo cơ sở | `Facility` | BOM |
| `FACILITY_UPDATED` | BOM sửa / gán FM, FM/BOM đổi hình thức truy cập | `Facility` | BOM/FM |
| `STORAGE_UNIT_CREATED` | FM thêm khoang | `StorageUnit` | FM |
| `STORAGE_UNIT_UPDATED` | FM/BOM sửa loại / giá khoang | `StorageUnit` | FM/BOM |
| `POLICY_UPDATED` | BOM đổi policy | `Policy` | BOM |
| `UNIT_TYPE_UPDATED` | BOM đổi giá loại khoang | `UnitType` | BOM |
| `ACCOUNT_CREATED` | Admin tạo tài khoản | `Account` | Admin |
| `ACCOUNT_UPDATED` | Admin đổi role / trạng thái / thông tin | `Account` | Admin |
| `ROLE_PERMISSIONS_UPDATED` | Admin thay quyền của một role | `Role` | Admin |
| `STAFF_SHIFT_CREATED` / `_UPDATED` / `_DELETED` | FM thêm / sửa / xoá ca của FS; duyệt nghỉ phép xoá ca trong khoảng nghỉ | `StaffShift` | FM |
| `STAFF_LEAVE_REQUESTED` / `_CANCELED` | FS gửi / huỷ đơn nghỉ phép | `StaffLeave` | FS |
| `STAFF_LEAVE_APPROVED` / `_REJECTED` | FM duyệt / từ chối đơn nghỉ phép | `StaffLeave` | FM |
| `STAFF_PROFILE_UPDATED` | ADMIN / BOM sửa hồ sơ nhân viên (số giấy tờ che, giữ 4 ký tự cuối) | `StaffProfile` | Admin/BOM |

- Entity type (`audit_logs.entity_type`, CHECK theo migration): `RentalRequest`, `ProposalFeedback`, `Invoice`,
  `PaymentTransaction`, `RentalOrder`, `StorageUnit`, `Appointment`, `HandoverRecord`, `RentalContract`,
  `UnitAccessKey`, `CheckoutRecord`, `Account`, `ReturnRequest`, `SupportRequest`, `Facility`, `Policy`, `UnitType`,
  `Role`, `StaffShift`, `StaffLeave`, `StaffProfile`.

- Xác minh email không tạo `AuditLog`; bản ghi là `Account.email_verified_at`. Nếu sau này có đường
  verify khác self-service (admin verify hộ, đổi email re-verify), phải bổ sung action tương ứng.

# SupportRequest
**Overview:** yêu cầu hỗ trợ / sự cố của khách (Flow 7). Bảng `support_requests` (V26); hành vi ở
`backend/docs/support/requests.md`.
- customer_id (N - 1: Account)
- facility_id (N - 1: Facility)
- contract_id (N - 1: RentalContract, nullable) - bắt buộc với `LOST_KEY`
- category (`LOST_KEY`/`ACCESS_CODE`/`UNIT_ISSUE`/`BILLING`/`OTHER`)
- title (≤ 255), description
- photos - List<String>
- status (`Open`/`Assigned`/`Resolved`/`Canceled`) - default `Open`
- assigned_staff_id (N - 1: Account, nullable)
- resolution (nullable)
- fee_invoice_id (N - 1: Invoice, nullable) - hoá đơn `Service` khi xử lý có thu phí
- created_at, assigned_at, resolved_at, updated_at

**CONSTRAINTS:**
- Index `(facility_id, status)` cho hàng chờ FM, `(customer_id)` cho danh sách của khách.

# StaffShift
**Overview:** ca làm việc của FS tại một cơ sở trong một ngày (Flow 5). Bảng `staff_shifts` (V32); hành vi ở
`backend/docs/backoffice/hr.md`.
- facility_id (N - 1: Facility)
- staff_id (N - 1: Account, role FS)
- shift_date (Date), start_time, end_time (Time, giờ VN)
- note (≤ 500, nullable)
- created_by (N - 1: Account, nullable), created_at, updated_at

**CONSTRAINTS:**
- `start_time < end_time` (ca trong một ngày). Index `(staff_id, shift_date)`, `(facility_id, shift_date)`.
- Không chồng giờ với ca khác của cùng FS cùng ngày ở mọi cơ sở — kiểm ở service (khoá advisory theo FS).

# StaffLeave
**Overview:** đơn nghỉ phép cả ngày của FS (Flow 5). Bảng `staff_leaves` (V32).
- staff_id (N - 1: Account, role FS)
- from_date, to_date (Date, tính cả hai đầu)
- reason (≤ 500)
- status (`Pending`/`Approved`/`Rejected`/`Canceled`) - default `Pending`
- reviewed_by (N - 1: Account, nullable), reviewed_at, review_note (≤ 500)
- created_at

**CONSTRAINTS:**
- `from_date <= to_date`. Index `(staff_id, from_date, to_date)`, `(status)`.
- Một FS không có hai đơn `Pending`/`Approved` chồng ngày — kiểm ở service.

# StaffProfile
**Overview:** hồ sơ nhân viên của tài khoản FS / FM (Flow 5). Bảng `staff_profiles` (V32).
- account_id (PK, 1 - 1: Account)
- position (≤ 100), hire_date, id_number (≤ 20 — chỉ ADMIN / BOM đọc), emergency_contact_name (≤ 100),
  emergency_contact_phone (≤ 20), note (≤ 1000) — đều nullable
- updated_by (N - 1: Account, nullable), updated_at

# LoginEvent
**Overview:** lịch sử đăng nhập cho Admin — mọi lần thử, thành công hay thất bại. Bảng `login_events` (V26).
- account_id (N - 1: Account, nullable) - null khi email không khớp tài khoản nào
- email
- success (Boolean)
- reason (nullable) - tên lỗi khi thất bại (`InvalidCredentials`, `AccountNotAllowed`, `EmailNotVerified`,
  `TooManyLoginAttempts`…)
- ip_address (nullable)
- created_at

**CONSTRAINTS:**
- Index `(created_at DESC)` và `(account_id, created_at DESC)`.
- Ghi trong transaction riêng: login lỗi vẫn để lại bản ghi.

# Policy
**Owner:** Flow 4 (business rules & phí); các flow chỉ đọc qua key.
- key (unique) - khoá dạng namespace, ví dụ `request.pending_expiry_days`
- value - giá trị lưu dạng chuỗi; nơi đọc tự ép kiểu
- description
- updated_at

**NOTES:**
- Bảng key-value để BOM cấu hình qua `GET`/`PUT /api/bom/policies` (policy đang là số chỉ nhận số ≥ 0).
- Danh sách key + giá trị mặc định đang dùng nằm trong `draft.md` (Flow 1 §1.1, Flow 2 §2.1); key "chờ BOM" chưa seed.
- V27: thêm `rent.invoice_lead_days = 5` (lập hoá đơn tiền thuê tháng kế tiếp trước ngày bắt đầu tháng N ngày),
  `overdue.escalation_days = 30` (quá hạn N ngày từ `end_date` → tạm khoá mã khoá số, báo FM); xoá
  `order.deposit_expiry_days` (không có code đọc — hạn giữ đơn sau cọc dùng `order.expiry_days`); đổi mô tả
  `appointment.booking_window_days` (chỉ áp cho lần đặt lịch check-in đầu tiên; đặt lại sau khi lịch bị huỷ chỉ cần
  còn trong hạn giữ đơn).
- V28: thêm `return.no_show_grace_minutes = 120` (lịch trả kho quá giờ kết thúc N phút mà khách chưa đến → no-show),
  `return.max_no_shows = 2` (no-show N lần → huỷ yêu cầu trả kho).
- V32: thêm `staff.shift_required = true` (phân công FS bắt buộc ca bao trọn giờ hẹn; policy boolean chỉ nhận
  `true`/`false`).
- `appointment.checkin_reschedule_enabled` không được seed — khách không đổi được lịch check-in `Pending`.

# OrderNotification
**Overview:** liên kết RentalOrder với 1 Notification, chủ ý rằng thông báo dành cho đơn hàng.
- order_id (N - 1: RentalOrder)
- notification_id (1 -1: Notification)

**NOTES:**
- Tạo kèm mỗi notification gắn với một đơn (nhắc hạn, hủy đơn, lịch hẹn, ...).
- Một `Notification` có tối đa một liên kết đơn.
- Dùng để truy vấn thông báo theo đơn — ví dụ nhắc hết hạn chỉ gửi một lần cho mỗi đơn.

# Notification
**Overview:** thông báo web cho người dùng. Email chỉ gửi đi (không lưu) — fail không rollback nghiệp vụ.
- account_id (N - 1: Account) - người nhận
- type - loại thông báo theo catalog trong NOTES
- title
- body
- read_at (nullable)
- created_at

**NOTES:**
- Email: dev dùng MailHog. Khi fail chỉ log + vẫn lưu thông báo web.
- Email và thông báo web dùng cùng `title`/`body`. Cách trình bày có thể khác nhau.
- `type` là catalog riêng, tách khỏi audit catalog. Khi thêm một luồng notification đã biết, bổ sung type tương ứng.
- Nhiều type notification trùng tên audit event (ví dụ `APPOINTMENT_CANCELED_NO_SHOW`, `RENTAL_ORDER_CANCELED`). Notification catalog tách khỏi audit catalog; khi code phải tách enum/type tương ứng, không nhầm hai bên.
- `OTHER` chỉ dùng cho thông báo khẩn cấp thủ công khi chưa có type phù hợp. Không dùng thay cho một type nghiệp vụ đã biết. `title` và `body` phải mô tả cụ thể sự việc.
- Quyền tạo `OTHER` phải được giới hạn cho người có quyền.
- Chưa có bảng template. MVP lưu template theo `type` trong code. Template dùng placeholder `{{key}}`, caller phải truyền đủ giá trị để render.
- `RENTAL_REQUEST_APPROVED` chỉ phát khi `RentalOrder` được tạo, tức khi account đã tồn tại. Khách chưa có account chỉ nhận email duyệt ở Flow 1.1, notification phát ở Flow 1.2 sau khi xác minh email. Nội dung email duyệt khác nhau tùy khách đã có tài khoản hay chưa. Các type còn lại áp dụng sau khi account đã tồn tại.
- Index `(account_id, read_at)` cho lọc và đếm chưa đọc, và index `(account_id, created_at DESC)` cho list sắp xếp và phân trang.
- Một số type render khác nhau theo recipient. Ví dụ `APPOINTMENT_CREATED` gửi khách ngày/giờ, địa chỉ cơ sở và hướng dẫn giấy tờ. Gửi FM mã đơn, thời gian hẹn và cơ sở.
- Nhắc gần hết hạn: cron gửi `RENTAL_ORDER_EXPIRING_SOON` trước `RentalOrder.expires_at` theo `order.expiry_reminder_days`, mỗi đơn một lần. `RentalRequest`/`ProposalFeedback` chưa có notification nhắc.
- Notification type catalog:

| Type | Khi nào dùng | Recipient |
|---|---|---|
| `RENTAL_REQUEST_APPROVED` | FM duyệt yêu cầu thuê kho, nội dung gồm link trỏ tới trang để duyệt proposal và thông tin khoang theo Flow 1.1 | Customer |
| `RENTAL_REQUEST_REJECTED` | FM từ chối yêu cầu thuê kho, nội dung gồm lý do từ chối | Customer |
| `PROPOSAL_REJECTED` | Khách từ chối proposal, nội dung gồm mã khoang và lý do (`note`) | FM |
| `PROPOSAL_REPROPOSAL_REQUIRED` | FM cần đề xuất lại khoang khác (nội dung gồm mã đơn và mã khoang cũ) | FM |
| `PROPOSAL_REPROPOSED` | FM tạo proposal mới, nội dung gồm link xác nhận | Customer |
| `DEPOSIT_PAYMENT_SUCCEEDED` | Đặt cọc thành công | Customer |
| `PAYMENT_DUPLICATE_REFUND_REQUIRED` | Giao dịch Success thứ hai cho cùng invoice — FM cần hoàn tiền thủ công | FM |
| `PAYMENT_PENDING_REVIEW_REQUIRED` | Giao dịch Pending quá hạn — ADMIN cần tra portal và đối soát | ADMIN |
| `PAYMENT_REFUND_REQUIRED` | Tiền cọc đã vào nhưng unit không còn giữ được — FM cần hoàn tiền | FM |
| `PAYMENT_REFUND_PENDING` | Khoản cọc sẽ được hoàn do unit không còn giữ được | Customer |
| `APPOINTMENT_CREATED` | Tạo lịch hẹn check-in, nội dung khác nhau theo recipient (xem NOTES) | Customer, FM |
| `APPOINTMENT_CANCELED_NO_SHOW` | Cron hủy lịch do khách không đến (nội dung gồm thời gian hẹn cũ và link đặt lịch mới) | Customer |
| `FS_ASSIGNED` | FM phân công FS cho lịch hẹn, nội dung gồm thông tin lịch hẹn | FS |
| `FS_ASSIGNMENT_REQUIRED` | FM cần phân công FS cho lịch check-in chưa có nhân viên phụ trách (nội dung gồm mã đơn, thời gian hẹn, cơ sở) | FM |
| `HANDOVER_REJECTED` | Khách từ chối khoang tại check-in và muốn re-propose, nội dung gồm mã khoang và lý do | FM |
| `HANDOVER_OVERDUE` | Cron đóng biên bản quá `due_at` khi khách đã đến, nội dung có link đặt lịch mới | Customer |
| `RENTAL_ORDER_EXPIRING_SOON` | Cron nhắc trước khi `RentalOrder` hết hạn, nội dung gồm mã đơn, hạn đơn và số ngày còn lại | Customer |
| `RENTAL_ORDER_CANCELED` | Đơn thuê bị hủy hoặc quá hạn, title theo `cancel_reason` (xem Cancellation Cascade) | Customer, FM |
| `UNIT_CHANGE_PAYMENT_REQUIRED` | Khách đổi khoang sau cọc và cần thanh toán phần thiếu để giữ khoang mới | Customer |
| `DEPOSIT_REFUND_REQUIRED` | Đổi khoang sau cọc dư — FM cần hoàn tiền | FM |
| `DEPOSIT_REFUND_PENDING` | Cọc dư sẽ được hoàn | Customer |
| `HANDOVER_COMPLETED` | Hoàn tất check-in và bàn giao khoang (link biên bản + kho đang thuê; kiểu `Both` kèm nhắc lấy mã khoá số) | Customer |
| `RENT_INVOICE_ISSUED` | Cron lập hoá đơn tiền thuê tháng kế tiếp (kỳ, số tiền, hạn, link hoá đơn) | Customer |
| `RENT_UNPAID` | Hoá đơn tiền thuê tháng (không phải tháng đầu) quá hạn chưa trả | FM |
| `CONTRACT_ACCESS_SUSPENDED` | Hợp đồng quá hạn kéo dài, mã khoá số tạm khoá | Customer, FM |
| `CONTRACT_ACCESS_RESTORED` | Quyền truy cập mở lại (khách lấy mã mới): hợp đồng hết quá hạn, hoặc mở để dọn đồ khi đã gửi yêu cầu trả kho | Customer |
| `RETURN_DATE_SHIFTED` | Ngày trả kho khách chọn đã kín, lịch được dời sang ngày giờ khác | Customer |
| `RETURN_NO_SHOW` | Khách không đến buổi trả kho — chờ phân công lại hoặc yêu cầu bị huỷ | Customer, FM |
| `CHECKOUT_SHORTFALL_UNPAID` | Trả kho đã đóng nhưng hoá đơn phần còn thiếu quá hạn chưa trả — cần thu thủ công | FM |
| `STAFF_SHIFT_CHANGED` | FM thêm / sửa / xoá ca ở tương lai của FS | FS |
| `STAFF_LEAVE_REQUESTED` | FS gửi đơn nghỉ phép | FM (các cơ sở FS được gán) |
| `STAFF_LEAVE_REVIEWED` | Đơn nghỉ phép được duyệt / bị từ chối (kèm ghi chú) | FS |
| `OTHER` | Thông báo khẩn cấp thủ công khi chưa có type phù hợp | Recipient do người tạo chọn |

# MediaUpload

- id
- account_id (N - 1: Account)
- batch_id (UUID; các file cùng một đợt upload dùng chung)
- public_id (nullable)
- url (nullable)
- bytes (nullable)
- format (nullable)
- status (`Uploading` / `Pending` / `Attached` / `Canceled` / `Deleted`)
- created_at
- updated_at

**NOTES:**
- Row `Uploading` tạo trước khi gọi storage; `Pending` sau khi lưu xong.
- `DELETE /api/media/uploads/batch/{batchId}` mark `Canceled` + xoá asset `Uploading`/`Pending`;
  `POST .../attach` mark `Attached` để Hủy không xoá.
- Row `Uploading` chưa có `public_id`: upload completion thấy `Canceled` thì xoá asset vừa lưu.
- Unique `public_id` khi khác null; index `(account_id, batch_id, status)`. Không audit upload/delete;
  audit nằm ở flow gắn URL vào entity.
