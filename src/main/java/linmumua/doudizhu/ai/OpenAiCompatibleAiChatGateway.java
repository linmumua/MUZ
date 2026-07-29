package linmumua.doudizhu.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

public final class OpenAiCompatibleAiChatGateway implements AiChatGateway {
    private final ProviderConfig config;
    private final Logger logger;
    private final HttpClient httpClient;

    public OpenAiCompatibleAiChatGateway(ProviderConfig config, Logger logger) {
        this.config = Objects.requireNonNull(config, "config");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(config.connectTimeoutMs()))
            .build();
    }

    @Override
    public boolean isEnabled() {
        return config.enabled() && config.hasApiKey();
    }

    @Override
    public String providerName() {
        return config.providerName();
    }

    @Override
    public String model() {
        return config.model();
    }

    @Override
    public URI baseUri() {
        return config.baseUri();
    }

    @Override
    public CompletableFuture<ChatResponse> chatAsync(ChatRequest request) {
        Objects.requireNonNull(request, "request");
        if (!config.enabled()) {
            return CompletableFuture.failedFuture(new AiGatewayException("第三方 AI 当前已关闭"));
        }
        if (!config.hasApiKey()) {
            return CompletableFuture.failedFuture(new AiGatewayException("第三方 AI 已开启，但未配置 API Key"));
        }

        JsonObject body = buildRequestBody(config, request);
        HttpRequest httpRequest = HttpRequest.newBuilder(config.chatCompletionsUri())
            .timeout(Duration.ofMillis(config.requestTimeoutMs()))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + config.apiKey())
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build();

        return httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString())
            .thenApply(response -> parseResponse(response.statusCode(), response.body()));
    }

    static JsonObject buildRequestBody(ProviderConfig config, ChatRequest request) {
        JsonObject body = new JsonObject();
        body.addProperty("model", request.modelOverride() == null || request.modelOverride().isBlank() ? config.model() : request.modelOverride());
        body.addProperty("stream", false);
        body.addProperty("temperature", request.temperature() == null ? config.temperature() : request.temperature());
        int maxTokens = request.maxTokens() == null ? config.maxTokens() : request.maxTokens();
        if (maxTokens > 0) {
            body.addProperty("max_tokens", maxTokens);
        }

        JsonArray messages = new JsonArray();
        if (!config.systemPrompt().isBlank()) {
            messages.add(messageJson(AiChatGateway.Message.system(config.systemPrompt())));
        }
        List<AiChatGateway.Message> requestedMessages = new ArrayList<>(request.messages());
        for (AiChatGateway.Message message : requestedMessages) {
            messages.add(messageJson(message));
        }
        body.add("messages", messages);
        return body;
    }

    private static JsonObject messageJson(AiChatGateway.Message message) {
        JsonObject json = new JsonObject();
        json.addProperty("role", message.role());
        json.addProperty("content", message.content());
        return json;
    }

    private ChatResponse parseResponse(int statusCode, String rawBody) {
        try {
            JsonObject root = JsonParser.parseString(rawBody).getAsJsonObject();
            if (statusCode < 200 || statusCode >= 300) {
                throw responseError(statusCode, root);
            }
            JsonArray choices = root.getAsJsonArray("choices");
            if (choices == null || choices.size() == 0) {
                throw new AiGatewayException("第三方 AI 未返回 choices");
            }
            JsonObject choice = choices.get(0).getAsJsonObject();
            JsonObject message = choice.has("message") && choice.get("message").isJsonObject()
                ? choice.getAsJsonObject("message")
                : new JsonObject();
            JsonObject usage = root.has("usage") && root.get("usage").isJsonObject()
                ? root.getAsJsonObject("usage")
                : new JsonObject();
            JsonObject completionDetails = usage.has("completion_tokens_details") && usage.get("completion_tokens_details").isJsonObject()
                ? usage.getAsJsonObject("completion_tokens_details")
                : new JsonObject();
            return new ChatResponse(
                getString(root, "id", ""),
                getString(root, "model", config.model()),
                getString(message, "content", ""),
                getString(message, "reasoning_content", ""),
                getString(choice, "finish_reason", ""),
                new Usage(
                    getInt(usage, "prompt_tokens", 0),
                    getInt(usage, "completion_tokens", 0),
                    getInt(usage, "total_tokens", 0),
                    getInt(completionDetails, "reasoning_tokens", 0)
                )
            );
        } catch (AiGatewayException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            logger.warning("解析第三方 AI 响应失败: " + exception.getMessage());
            throw new AiGatewayException("第三方 AI 响应格式无效", exception);
        }
    }

    private AiGatewayException responseError(int statusCode, JsonObject root) {
        JsonObject error = root.has("error") && root.get("error").isJsonObject()
            ? root.getAsJsonObject("error")
            : new JsonObject();
        String message = getString(error, "message", "HTTP " + statusCode);
        return new AiGatewayException("第三方 AI 请求失败: " + message);
    }

    private static String getString(JsonObject json, String key, String fallback) {
        if (json == null || !json.has(key) || json.get(key).isJsonNull()) {
            return fallback;
        }
        return json.get(key).getAsString();
    }

    private static int getInt(JsonObject json, String key, int fallback) {
        if (json == null || !json.has(key) || json.get(key).isJsonNull()) {
            return fallback;
        }
        return json.get(key).getAsInt();
    }
}
