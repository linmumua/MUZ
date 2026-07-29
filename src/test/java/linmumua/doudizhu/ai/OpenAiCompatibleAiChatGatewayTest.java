package linmumua.doudizhu.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OpenAiCompatibleAiChatGatewayTest {
    @Test
    void providerConfigBuildsChatCompletionUriFromRootBaseUrl() {
        AiChatGateway.ProviderConfig config = new AiChatGateway.ProviderConfig(
            true,
            "DeepSeek",
            "https://api.deepseek.com",
            "/chat/completions",
            "/models",
            "test-key",
            "deepseek-chat",
            10000,
            45000,
            0.7,
            512,
            ""
        );

        assertEquals(URI.create("https://api.deepseek.com/chat/completions"), config.chatCompletionsUri());
    }

    @Test
    void providerConfigKeepsV1BaseUrlWhenAppendingEndpoint() {
        AiChatGateway.ProviderConfig config = new AiChatGateway.ProviderConfig(
            true,
            "CompatibleAI",
            "https://example.com/v1/",
            "/chat/completions",
            "/models",
            "test-key",
            "model-x",
            10000,
            45000,
            0.7,
            512,
            ""
        );

        assertEquals(URI.create("https://example.com/v1/chat/completions"), config.chatCompletionsUri());
    }

    @Test
    void requestBodyPrependsConfiguredSystemPromptAndUsesOverrides() {
        AiChatGateway.ProviderConfig config = new AiChatGateway.ProviderConfig(
            true,
            "DeepSeek",
            "https://api.deepseek.com",
            "/chat/completions",
            "/models",
            "test-key",
            "deepseek-chat",
            10000,
            45000,
            0.7,
            512,
            "你是 MUZ 的游戏助手"
        );
        AiChatGateway.ChatRequest request = new AiChatGateway.ChatRequest(
            List.of(AiChatGateway.Message.user("你好")),
            "deepseek-reasoner",
            0.2,
            128
        );

        JsonObject body = OpenAiCompatibleAiChatGateway.buildRequestBody(config, request);
        JsonArray messages = body.getAsJsonArray("messages");

        assertEquals("deepseek-reasoner", body.get("model").getAsString());
        assertEquals(0.2, body.get("temperature").getAsDouble());
        assertEquals(128, body.get("max_tokens").getAsInt());
        assertEquals(2, messages.size());
        assertEquals("system", messages.get(0).getAsJsonObject().get("role").getAsString());
        assertEquals("你是 MUZ 的游戏助手", messages.get(0).getAsJsonObject().get("content").getAsString());
        assertEquals("user", messages.get(1).getAsJsonObject().get("role").getAsString());
    }
}
