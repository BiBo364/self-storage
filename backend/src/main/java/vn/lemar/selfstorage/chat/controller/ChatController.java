package vn.lemar.selfstorage.chat.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import vn.lemar.selfstorage.ApiEnvelope;
import vn.lemar.selfstorage.ApiError;
import vn.lemar.selfstorage.chat.application.ChatModelClient.Reply;
import vn.lemar.selfstorage.chat.application.ChatModelClient.Turn;
import vn.lemar.selfstorage.chat.application.ChatService;

/** Chatbot tư vấn công khai (khách chưa đăng nhập cũng hỏi được). */
@RestController
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @SecurityRequirements
    @Operation(summary = "Hỏi trợ lý tư vấn",
            description = "Gửi toàn bộ hội thoại (tối đa 20 lượt, mỗi lượt ≤ 2000 ký tự, lượt cuối là `user`). "
                    + "503 khi chưa cấu hình GEMINI_API_KEY, 429 khi hỏi quá nhanh.")
    @PostMapping("/api/chat")
    public ResponseEntity<ApiEnvelope<Reply>> chat(@Valid @RequestBody ChatBody body, HttpServletRequest request) {
        return ResponseEntity.ok(ApiEnvelope.ok("Trả lời", chatService.reply(body.messages(),
                request.getRemoteAddr())));
    }

    @ExceptionHandler(ChatService.ChatException.class)
    public ResponseEntity<ApiError> handle(ChatService.ChatException ex) {
        return ResponseEntity.status(ex.status()).body(ApiError.now(ex.getMessage()));
    }

    public record ChatBody(@NotNull List<Turn> messages) {
    }
}
