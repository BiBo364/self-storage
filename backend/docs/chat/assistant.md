---
purpose: Chatbot tư vấn as-built — POST /api/chat gọi Google Gemini (REST `generateContent`), kiến thức lấy từ catalog / policy công khai.
---
# Chatbot tư vấn

Code: `chat/application/ChatService.java`, `chat/application/GeminiChatModelClient.java` (Spring `RestClient`, không SDK),
`chat/controller/ChatController.java`. FE: `frontend/src/components/domain/ChatWidget.tsx` (nút nổi ở trang công khai
và trang khách hàng).

## Route

`POST /api/chat` — public (không cần đăng nhập). Body `{ "messages": [{ "role": "user"|"assistant", "content": "..." }] }`
(toàn bộ hội thoại; FE giữ trong `sessionStorage`). Trả `{ text, refused }`.

| Mã | Khi |
|---|---|
| 400 | rỗng / quá 20 lượt / lượt nào > 2000 ký tự / role khác `user`,`assistant` / lượt cuối không phải `user` |
| 429 | quá 30 lần / 10 phút theo IP (bộ đếm trong bộ nhớ, mất khi khởi động lại) |
| 502 | gọi mô hình lỗi (mạng, key sai, hết quota / rate limit phía Google…) — lý do cụ thể chỉ ghi log |
| 503 | chưa cấu hình `GEMINI_API_KEY` |

## Hành vi

- **Mô hình:** `gemini-3.6-flash` (đổi qua `CHAT_MODEL`), `thinkingConfig.thinkingLevel = LOW` (đổi qua
  `CHAT_THINKING_LEVEL`, để trống thì không gửi), `maxOutputTokens = 8192`. Lượt `assistant` gửi đi với role `model`;
  system prompt nằm trong `systemInstruction`; phần `thought: true` trong câu trả lời bị bỏ qua.
- **Bị chặn / rỗng:** `promptFeedback.blockReason` hoặc `finishReason` thuộc bộ lọc an toàn (`SAFETY`, `BLOCKLIST`,
  `PROHIBITED_CONTENT`, `SPII`, `RECITATION`, `OTHER`, `IMAGE_*`) → `refused = true`; bị chặn hoặc trả rỗng → câu trả
  lời lịch sự cố định.
- **System prompt:** dựng mỗi lần gọi từ:
  - cơ sở đang hoạt động (`FacilityCatalogQuery.listActive`);
  - loại khoang + giá (`PricingAdminCommands.unitTypes`);
  - một danh sách policy công khai (cọc, hạn, gia hạn, quá hạn, phí trả kho, ngưỡng từ chối) và mô tả quy trình thuê.

  Thứ tự cố định. Prompt yêu cầu trả lời văn bản thuần (widget không render markdown) và không nêu tên key policy.
- **Không đọc dữ liệu cá nhân:** câu hỏi về đơn / hoá đơn cụ thể được hướng về "Kho của tôi", "Hoá đơn" hoặc "Hỗ trợ".

## Cấu hình

`.env`: `GEMINI_API_KEY=...` (key từ aistudio.google.com/apikey), tuỳ chọn `CHAT_MODEL`, `CHAT_THINKING_LEVEL`.

## Test

`chat/application/ChatServiceTest.java`, `chat/application/GeminiChatModelClientTest.java`,
`chat/controller/ChatControllerTest.java`. Chưa có test gọi API thật.
