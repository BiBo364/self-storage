---
auth-model: mixed
purpose: Bản hợp đồng (HTML để in) và FM duyệt đề nghị đổi mốc tính tiền (Flow 2.3).
---

# /api/contracts/{id}/document, /api/fm/contracts

- **Contract chi tiết:** annotation tại `handover/controller/ContractController.java`; behavior tại
  [handover/records.md](../../handover/records.md).

| Method | Path | Quyền | Kết quả |
|---|---|---|---|
| GET | `/api/contracts/{contractId}/document` | Bearer; khách của hợp đồng, hoặc `rental_order.read` (`Access.canGlobalOrAt`: ADMIN/BOM mọi cơ sở, FM/FS theo facility scope) | `text/html` — mẫu `builtin-v1`. Chưa ký: render động, đề nghị đổi mốc `Pending` → 409. Đã ký: trả bản chốt lúc ký (`document_html`); hợp đồng ký trước V22 vẫn render động |
| GET | `/api/fm/contracts/start-date-overrides` | role `FM` | Đề nghị đổi mốc `Pending` của các cơ sở FM phụ trách |
| POST | `/api/fm/contracts/{contractId}/start-date-override/approve` | role `FM` + `rental_contract.start_date_override.review` + facility scope | `start_date` = ngày đề nghị, mở lại bước ký |
| POST | `/api/fm/contracts/{contractId}/start-date-override/reject` | như trên | Giữ ngày chính sách, mở lại bước ký |

Body duyệt / từ chối tuỳ chọn: `{ "reason": "..." }` (ghi vào audit). Không có đề nghị `Pending` → 409.

## Ví dụ

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"fm1@lemar.vn","password":"Test@1234"}' | jq -r .data.token)

curl -s http://localhost:8080/api/fm/contracts/start-date-overrides -H "Authorization: Bearer $TOKEN"
# {"message":"Đề nghị đổi mốc tính tiền đang chờ","data":[{"contractId":2,"contractCode":"HD-Q7-261106-7XK2PD",
#  "orderId":5,"unitCode":"A-01","customerName":"Khách Demo","startDate":"2026-11-06",
#  "requestedDate":"2026-11-11","reason":"Khách chuyển đồ sau 5 ngày","facilityName":"Self Storage Quận 7"}]}

curl -s -X POST http://localhost:8080/api/fm/contracts/2/start-date-override/approve \
  -H "Authorization: Bearer $TOKEN"
# {"message":"Đã duyệt mốc tính tiền mới","data":null}
```

## Liên quan

- [staff-handovers.md](staff-handovers.md) · [customer-handovers.md](customer-handovers.md) ·
  [routes.md](../../routes.md)
