package vn.lemar.selfstorage.chat.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import vn.lemar.selfstorage.chat.application.ChatModelClient.Reply;
import vn.lemar.selfstorage.chat.application.ChatModelClient.Turn;

/** Adapter Gemini: dựng body generateContent, đọc text / bị chặn — không gọi mạng (MockRestServiceServer). */
class GeminiChatModelClientTest {

    private static final String URL = GeminiChatModelClient.BASE_URL
            + "/v1beta/models/gemini-3.6-flash:generateContent";

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void availabilityFollowsApiKey() {
        assertThat(client("", "LOW", RestClient.builder()).available()).isFalse();
        assertThat(client(null, "LOW", RestClient.builder()).available()).isFalse();
        assertThat(client(" key ", "LOW", RestClient.builder()).available()).isTrue();
    }

    @Test
    void sendsConversationAndReadsTextSkippingThoughts() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("x-goog-api-key", "key"))
                .andExpect(jsonPath("$.systemInstruction.parts[0].text").value("SYS"))
                .andExpect(jsonPath("$.contents[0].role").value("user"))
                .andExpect(jsonPath("$.contents[1].role").value("model"))
                .andExpect(jsonPath("$.contents[2].parts[0].text").value("Giá?"))
                .andExpect(jsonPath("$.generationConfig.thinkingConfig.thinkingLevel").value("LOW"))
                .andRespond(withSuccess("""
                        {"candidates":[{"finishReason":"STOP","content":{"role":"model","parts":[
                          {"text":"nghĩ...","thought":true},{"text":"Giá từ "},{"text":"500.000đ"}]}}]}
                        """, MediaType.APPLICATION_JSON));

        Reply reply = client("key", "LOW", builder).complete("SYS", List.of(
                new Turn("user", "Chào"), new Turn("assistant", "Xin chào"), new Turn("user", "Giá?")));

        assertThat(reply).isEqualTo(new Reply("Giá từ 500.000đ", false));
        server.verify();
    }

    @Test
    void blankThinkingLevelIsOmitted() {
        assertThat(client("key", " ", RestClient.builder()).requestBody("SYS", List.of(new Turn("user", "hi")))
                .get("generationConfig")).isEqualTo(java.util.Map.of("maxOutputTokens", 8192));
    }

    @Test
    void safetyBlocksAreRefusals() throws Exception {
        assertThat(GeminiChatModelClient.parse(json.readTree(
                "{\"promptFeedback\":{\"blockReason\":\"PROHIBITED_CONTENT\"}}"))).isEqualTo(new Reply("", true));
        assertThat(GeminiChatModelClient.parse(json.readTree(
                "{\"candidates\":[{\"finishReason\":\"SAFETY\"}]}"))).isEqualTo(new Reply("", true));
        assertThat(GeminiChatModelClient.parse(json.readTree("{}"))).isEqualTo(new Reply("", false));
        assertThat(GeminiChatModelClient.parse(null)).isEqualTo(new Reply("", false));
    }

    @Test
    void httpErrorPropagatesForServiceToMap() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> client("key", "LOW", builder).complete("SYS", List.of(new Turn("user", "hi"))))
                .isInstanceOf(RestClientResponseException.class);
    }

    private static GeminiChatModelClient client(String key, String thinking, RestClient.Builder builder) {
        return new GeminiChatModelClient(key, "gemini-3.6-flash", thinking, builder);
    }
}
