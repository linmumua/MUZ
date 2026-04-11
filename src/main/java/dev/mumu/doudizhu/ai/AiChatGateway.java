package dev.mumu.doudizhu.ai;

import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public interface AiChatGateway {
    boolean isEnabled();

    String providerName();

    String model();

    URI baseUri();

    CompletableFuture<ChatResponse> chatAsync(ChatRequest request);

    default ChatResponse chat(ChatRequest request) {
        try {
            return chatAsync(request).join();
        } catch (CompletionException exception) {
            if (exception.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw exception;
        }
    }

    record Message(String role, String content) {
        public Message {
            role = normalizeRequired(role, "role");
            content = normalizeRequired(content, "content");
        }

        public static Message system(String content) {
            return new Message("system", content);
        }

        public static Message user(String content) {
            return new Message("user", content);
        }

        public static Message assistant(String content) {
            return new Message("assistant", content);
        }
    }

    record ChatRequest(List<Message> messages, String modelOverride, Double temperature, Integer maxTokens) {
        public ChatRequest {
            messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
            if (messages.isEmpty()) {
                throw new IllegalArgumentException("messages 不能为空");
            }
            modelOverride = normalizeOptional(modelOverride);
            temperature = temperature == null ? null : Math.max(0.0, Math.min(2.0, temperature));
            maxTokens = maxTokens == null ? null : Math.max(0, maxTokens);
        }

        public static ChatRequest singleTurn(String prompt) {
            return new ChatRequest(List.of(Message.user(prompt)), null, null, null);
        }
    }

    record Usage(int promptTokens, int completionTokens, int totalTokens, int reasoningTokens) {
    }

    record ChatResponse(
        String id,
        String model,
        String content,
        String reasoningContent,
        String finishReason,
        Usage usage
    ) {
    }

    record ProviderConfig(
        boolean enabled,
        String providerName,
        String baseUrl,
        String chatCompletionsPath,
        String modelsPath,
        String apiKey,
        String model,
        int connectTimeoutMs,
        int requestTimeoutMs,
        double temperature,
        int maxTokens,
        String systemPrompt
    ) {
        public ProviderConfig {
            providerName = normalizeOptional(providerName);
            if (providerName.isBlank()) {
                providerName = "ThirdPartyAI";
            }
            baseUrl = normalizeOptional(baseUrl);
            if (baseUrl.isBlank()) {
                baseUrl = "https://api.deepseek.com";
            }
            chatCompletionsPath = normalizeOptional(chatCompletionsPath);
            if (chatCompletionsPath.isBlank()) {
                chatCompletionsPath = "/chat/completions";
            }
            modelsPath = normalizeOptional(modelsPath);
            if (modelsPath.isBlank()) {
                modelsPath = "/models";
            }
            apiKey = normalizeOptional(apiKey);
            model = normalizeOptional(model);
            if (model.isBlank()) {
                model = "deepseek-chat";
            }
            connectTimeoutMs = Math.max(1000, connectTimeoutMs);
            requestTimeoutMs = Math.max(1000, requestTimeoutMs);
            temperature = Math.max(0.0, Math.min(2.0, temperature));
            maxTokens = Math.max(0, maxTokens);
            systemPrompt = normalizeOptional(systemPrompt);
        }

        public URI baseUri() {
            String normalized = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
            return URI.create(normalized);
        }

        public URI chatCompletionsUri() {
            return endpointUri(chatCompletionsPath);
        }

        public URI modelsUri() {
            return endpointUri(modelsPath);
        }

        public boolean hasApiKey() {
            return !apiKey.isBlank();
        }

        private URI endpointUri(String path) {
            String normalizedPath = path.startsWith("/") ? path.substring(1) : path;
            return baseUri().resolve(normalizedPath);
        }
    }

    private static String normalizeRequired(String value, String label) {
        String normalized = normalizeOptional(value);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(label + " 不能为空");
        }
        return normalized;
    }

    private static String normalizeOptional(String value) {
        return value == null ? "" : value.trim();
    }
}
