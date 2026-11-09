package vn.lemar.selfstorage.chat.application;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Gọi Google Gemini qua REST {@code generateContent} (Spring {@link RestClient}, không thêm SDK). Lịch sử hội thoại
 * map {@code assistant} → {@code model}; system prompt (catalog + chính sách) đi trong {@code systemInstruction}.
 * Hội thoại tư vấn ngắn → {@code thinkingLevel} thấp (để trống thì không gửi, cho model không hỗ trợ thinking).
 */
@Component
class GeminiChatModelClient implements ChatModelClient {

    static final String BASE_URL = "https://generativelanguage.googleapis.com";
    static final int MAX_OUTPUT_TOKENS = 8192;

    /** {@code finishReason} / {@code blockReason} nghĩa là bị bộ lọc an toàn chặn. */
    private static final Set<String> BLOCKED = Set.of("SAFETY", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII",
            "RECITATION", "OTHER", "IMAGE_SAFETY", "IMAGE_PROHIBITED_CONTENT");

    private final String apiKey;
    private final String model;
    private final String thinkingLevel;
    private final RestClient restClient;

    @Autowired
    GeminiChatModelClient(@Value("${chat.gemini.api-key:}") String apiKey,
                          @Value("${chat.model:gemini-3.6-flash}") String model,
                          @Value("${chat.gemini.thinking-level:LOW}") String thinkingLevel,
                          ObjectProvider<RestClient.Builder> builder) {
        this(apiKey, model, thinkingLevel, builder.getIfAvailable(RestClient::builder));
    }

    GeminiChatModelClient(String apiKey, String model, String thinkingLevel, RestClient.Builder builder) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model;
        this.thinkingLevel = thinkingLevel == null ? "" : thinkingLevel.trim();
        this.restClient = builder.baseUrl(BASE_URL).build();
    }

    @Override
    public boolean available() {
        return !apiKey.isEmpty();
    }

    @Override
    public Reply complete(String systemPrompt, List<Turn> turns) {
        JsonNode response = restClient.post()
                .uri("/v1beta/models/{model}:generateContent", model)
                .header("x-goog-api-key", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestBody(systemPrompt, turns))
                .retrieve()
                .body(JsonNode.class);
        return parse(response);
    }

    Map<String, Object> requestBody(String systemPrompt, List<Turn> turns) {
        List<Map<String, Object>> contents = new ArrayList<>();
        for (Turn turn : turns) {
            String role = "assistant".equals(turn.role()) ? "model" : "user";
            contents.add(Map.of("role", role, "parts", List.of(Map.of("text", turn.content()))));
        }
        Map<String, Object> generationConfig = new LinkedHashMap<>();
        generationConfig.put("maxOutputTokens", MAX_OUTPUT_TOKENS);
        if (!thinkingLevel.isEmpty()) {
            generationConfig.put("thinkingConfig", Map.of("thinkingLevel", thinkingLevel));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("systemInstruction", Map.of("parts", List.of(Map.of("text", systemPrompt))));
        body.put("contents", contents);
        body.put("generationConfig", generationConfig);
        return body;
    }

    static Reply parse(JsonNode response) {
        if (response == null) {
            return new Reply("", false);
        }
        if (BLOCKED.contains(response.path("promptFeedback").path("blockReason").asText())) {
            return new Reply("", true);
        }
        JsonNode candidate = response.path("candidates").path(0);
        boolean refused = BLOCKED.contains(candidate.path("finishReason").asText());
        String text = StreamSupport.stream(candidate.path("content").path("parts").spliterator(), false)
                .filter(part -> !part.path("thought").asBoolean(false))
                .map(part -> part.path("text").asText(""))
                .collect(Collectors.joining())
                .trim();
        return new Reply(text, refused);
    }
}
