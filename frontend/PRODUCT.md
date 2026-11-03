# Product

<!-- impeccable:product-schema 1 -->

## Platform

web

## Users

Frontend phục vụ năm nhóm người dùng; không loại role nào khỏi phạm vi UI:

- **Customer:** tìm kho, so sánh lựa chọn, gửi yêu cầu thuê, theo dõi phản hồi, xem hợp đồng/hóa đơn, đặt lịch và gửi hỗ trợ.
- **Facility Manager (FM):** xử lý hàng đợi yêu cầu, chọn kho phù hợp, theo dõi hợp đồng, lịch hẹn, khoang chứa, hóa đơn và nhân sự trong phạm vi cơ sở.
- **Facility Staff (FS):** xem lịch trong ngày và hoàn thành checklist bàn giao, trả kho, hỗ trợ hoặc ghi nhận sự cố tại chỗ.
- **Business Operation Manager (BOM):** xem và cập nhật chính sách, phí, discount, giá, cơ sở và báo cáo toàn hệ thống.
- **System Administrator:** quản lý tài khoản, role, permission, lịch sử đăng nhập và audit log.

Các role đều quan trọng. Khi thiết kế một surface cụ thể, agent phải xác định actor, nhiệm vụ hiện tại và quyết định mà actor cần đưa ra trên surface đó; không dùng một layout hoặc mật độ thông tin cho mọi role.

## Product Purpose

Đây là frontend web cho dịch vụ self-storage Kho Mộc. Giao diện phải giúp người dùng hiểu dịch vụ, chọn đúng phương án, xử lý công việc theo role và biết bước tiếp theo trong quy trình.

Trải nghiệm cần làm cho quy trình **nhanh chóng, thuận tiện và minh bạch**. Thành công nghĩa là người dùng có thể trả lời được ba câu hỏi ở mỗi surface: tôi đang ở đâu, thông tin nào quan trọng, và tôi có thể làm gì tiếp theo.

## Positioning

Điểm cốt lõi của frontend là biến quy trình self-storage thành các luồng UI dễ hiểu và có thể hành động: từ xem kho, gửi yêu cầu, nhận phản hồi, đặt lịch đến bàn giao, trả kho và hỗ trợ. Trạng thái, lý do và hành động giữa các role phải được trình bày nhất quán.

## UI/UX Context

- Public/customer surfaces ưu tiên hiểu dịch vụ, tìm kho, so sánh kích thước/giá tham khảo, gửi yêu cầu và theo dõi kết quả.
- Operations surfaces ưu tiên scan nhanh, status rõ, filter đúng ngữ cảnh, action gần dữ liệu và hỗ trợ xử lý hàng đợi/checklist.
- Mỗi page có một primary action; action phụ không được cạnh tranh với action chính.
- Chi tiết dài nên được mở theo progressive disclosure bằng detail page, drawer hoặc dialog thay vì nhồi vào bảng.
- Customer cần copy ngắn, giải thích vừa đủ và cảm giác an tâm; FM/FS/BOM/Admin cần mật độ thông tin và công cụ thao tác phù hợp với công việc.
- Flow, status và business rule chưa chốt phải được biểu diễn trung tính, không biến giả định backend thành lời hứa UI.

## Capabilities and Constraints

- Page inventory hiện có 42 page cho Customer, Shared/Auth, FM, FS, BOM và Admin.
- Frontend hiện là prototype React + TypeScript + Vite với dữ liệu mock.
- Styling dùng Tailwind CSS v4 qua `@tailwindcss/vite`. Design token runtime nằm trong `src/styles.css` và được bridge sang Tailwind trong `src/tailwind.css`.
- Prototype hiện có public browsing, rental request và auth demo; các surface vận hành khác được mô tả trong `../fe-pages/`.
- Chưa có API, authentication/JWT thật, persistence, phân quyền bảo mật, thanh toán thật, notification hoặc upload thật trong prototype.
- Action chưa có backend phải hiển thị rõ là demo/TODO, không giả vờ đã ghi dữ liệu hoặc thanh toán thành công.
- UI dùng tiếng Việt; nội dung phải chịu được độ dài thực tế của tiếng Việt.
- Status luôn có text; màu và icon chỉ hỗ trợ scan nhanh.
- Loading, empty, error, forbidden, pending, success và destructive state là một phần của UI, không phải phần bổ sung sau cùng.

## Architecture Boundary

Kiến trúc frontend hiện tại được xem là baseline tốt và phải được giữ nguyên trong các task UI/UX:

- Giữ React + TypeScript + Vite, routing hiện tại, Tailwind CSS và cách tổ chức trong `src/app`, `src/pages`, `src/components`, `src/mocks`.
- Ưu tiên mở rộng hoặc tái sử dụng component, layout, mock data và token hiện tại thay vì tạo một architecture song song.
- `DESIGN.md` là visual source of truth; Tailwind chỉ là cách hiện thực hoá nó. Không tự thêm token màu, spacing hoặc typography ngoài `DESIGN.md`.
- Không tự ý đổi framework, thay routing, di chuyển hàng loạt file, thay styling approach khác Tailwind, thêm state/data layer hoặc cài thêm UI library chỉ vì một visual task.
- Nếu một thay đổi UI cần tác động đến architecture, agent phải đề xuất trước: lý do, phạm vi, ảnh hưởng, phương án thay thế và migration cost; chỉ thực hiện sau khi được xác nhận.

## Evidence on Hand

- `README.md`: phạm vi prototype hiện tại, cách chạy và boundary frontend/backend.
- `DESIGN.md`: visual source of truth cho hướng **Bãi kho** (concrete + signal yellow + Saira/Source Sans 3); token set đã áp dụng trong `src/`.
- `../fe-pages/README.md` và các page spec trong `../fe-pages/`: route, actor, mục đích, dữ liệu, actions, states và edge cases.
- `../fe-pages/README.md` và các page spec trong `../fe-pages/`: route, actor, mục đích, dữ liệu, actions, states và edge cases.
- `../draft.md` và `../db-table-draft.md`: nguồn tham khảo cho terminology và dữ liệu hiển thị, không phải giấy phép tự khóa business rule.
- `src/mocks/`: dữ liệu mock dùng để kiểm tra hierarchy, wrapping và các state của UI.

Không được tự tạo testimonials, customer proof, số liệu kinh doanh, facility thật, giá trị thanh toán thật hoặc business rule chưa có nguồn xác nhận.

## Source-of-truth and Branch Policy

Các tài liệu nghiệp vụ và UI spec đang được phát triển rải rác trên nhiều branch. `draft.md`, `db-table-draft.md`, `fe-pages/` và các flow spec ở branch hiện tại không mặc nhiên là source-of-truth.

- `flow-1` đã được merge vào `main` và là baseline đã được xác nhận ở thời điểm hiện tại.
- Các flow/spec khác chỉ được xem là branch-scoped hoặc provisional cho đến khi được merge vào `main` hoặc được xác nhận thống nhất.
- Trước khi lấy context để làm UI/UX, agent phải rà soát branch hiện tại, `main` và các branch flow liên quan; cần kiểm tra cả nội dung spec lẫn trạng thái merge.
- Khi các branch có khác biệt, ưu tiên product decisions đã được xác nhận và nội dung đã merge vào `main`. Không tự chọn một branch làm business truth.
- Status, field, workflow, API contract, copy hoặc UI state chỉ xuất hiện ở branch chưa thống nhất phải được giữ ở mức provisional; nếu cần dùng cho prototype, phải ghi rõ giả định và phạm vi áp dụng.
- Nếu không thể kiểm tra branch hoặc lịch sử merge, agent phải nói rõ giới hạn context trước khi đưa ra quyết định UI/UX.

## Product Principles

- **Nhanh chóng:** giảm bước thừa và làm rõ hành động tiếp theo.
- **Thuận tiện:** đặt đúng thông tin và công cụ ngay tại nơi người dùng cần.
- **Minh bạch:** status, lý do, điều kiện và kết quả của action phải dễ kiểm tra.
- **Tập trung:** ít lore, ít decoration, ít lựa chọn cạnh tranh; nội dung chính và primary action phải nổi bật.
- **Quen thuộc cho prototype:** ưu tiên pattern UI phổ thông, dễ nhận diện và dễ đánh giá hơn là cố tạo một visual metaphor khác biệt.
- **Nhất quán giữa role:** cùng một workflow phải dùng cùng vocabulary và mapping status, nhưng mật độ và shell có thể khác.
- **Không bịa product truth:** mock, provisional rule và TODO phải được thể hiện trung thực.

## Accessibility & Inclusion

- Web UI cần hỗ trợ keyboard navigation, focus rõ ràng, semantic structure, label và error message có liên kết đúng với form field.
- Status không được truyền tải chỉ bằng màu; cần có text và ngữ cảnh rõ ràng.
- Dark theme phải giữ contrast, hierarchy và khả năng đọc; không dùng nền tối để biện minh cho chữ mờ hoặc accent quá yếu.
- Tiếng Việt là ngôn ngữ UI chính; nội dung phải chịu được độ dài thực tế của tiếng Việt.

## Brand Commitments

Tên **Kho Mộc** đang được dùng trong design context và tài liệu frontend hiện tại. Đây là repository evidence cần được giữ nhất quán trong prototype, trừ khi có quyết định sản phẩm mới.

Hướng hình ảnh đã được duyệt: **Bãi kho** — concrete lạnh, signal yellow hi-vis, Saira Semi Condensed + Source Sans 3, light-first kèm dark theme. Đây là brand commitment cho các surface mới; không quay lại hệ cream/terracotta hoặc dark teal/lime cũ.

## Open Decisions

- Business rule, status và API chưa chốt phải được giữ ở mức provisional trong UI.
- Phạm vi và thứ tự triển khai các slice sau public/auth chưa được xem là cam kết release.
- Prototype ưu tiên một visual system phổ thông, polished, light-first và dễ nhận diện; dark theme là biến thể đầy đủ, không phải mặc định.
- Hướng **Bãi kho** đã được duyệt và migrate token/component trong `src/`; không trộn hai hệ trong cùng một surface.
