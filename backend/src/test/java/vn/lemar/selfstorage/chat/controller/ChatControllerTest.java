package vn.lemar.selfstorage.chat.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import vn.lemar.selfstorage.SecurityConfig;
import vn.lemar.selfstorage.chat.application.ChatModelClient.Reply;
import vn.lemar.selfstorage.chat.application.ChatService;
import vn.lemar.selfstorage.identity.application.AccountJwtAuthenticationConverter;
import vn.lemar.selfstorage.identity.config.JwtConfiguration;
import vn.lemar.selfstorage.identity.repository.AccountRepository;

/** `/api/chat` công khai, map lỗi của ChatService sang HTTP. */
@WebMvcTest(controllers = ChatController.class)
@Import({SecurityConfig.class, JwtConfiguration.class, AccountJwtAuthenticationConverter.class})
@TestPropertySource(properties = "security.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=")
class ChatControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ChatService chatService;
    @MockBean
    private AccountRepository accountRepository;

    @Test
    void anonymousVisitorGetsAReply() throws Exception {
        when(chatService.reply(anyList(), any())).thenReturn(new Reply("Xin chào!", false));
        mockMvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messages\":[{\"role\":\"user\",\"content\":\"Chào\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.text").value("Xin chào!"));
    }

    @Test
    void serviceErrorsKeepTheirStatus() throws Exception {
        ChatService.ChatException unavailable = org.mockito.Mockito.mock(ChatService.ChatException.class);
        when(unavailable.status()).thenReturn(503);
        when(unavailable.getMessage()).thenReturn("Trợ lý tư vấn chưa được cấu hình");
        when(chatService.reply(anyList(), any())).thenThrow(unavailable);
        mockMvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messages\":[{\"role\":\"user\",\"content\":\"Chào\"}]}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("Trợ lý tư vấn chưa được cấu hình"));
        mockMvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }
}
