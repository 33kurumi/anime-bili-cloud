package com.abc.aiservice.llm;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 基于 LangChain4j 的大模型客户端（OpenAI 兼容：DeepSeek / 通义千问 / Ollama 等）。
 *
 * <p>用 LangChain4j 的**核心库**（框架无关），与 Spring Boot 3.2 无版本冲突。
 * 未配置 api-key 时优雅降级为占位回复。
 *
 * <p>@Primary：优先使用本实现（替代 OpenAiCompatibleClient）。
 */
@Slf4j
@Component
@Primary
public class LangChain4jClient implements LlmClient {

    @Value("${abc.ai.base-url:https://api.deepseek.com}")
    private String baseUrl;

    @Value("${abc.ai.api-key:}")
    private String apiKey;

    @Value("${abc.ai.model:deepseek-chat}")
    private String model;

    @Value("${abc.ai.timeout-ms:60000}")
    private int timeoutMs;

    /** 免 key 免费兜底（用户未配置时用；Pollinations，OpenAI 兼容，无需 Key）。留空则禁用兜底。 */
    @Value("${abc.ai.free-fallback-base-url:https://text.pollinations.ai/openai}")
    private String freeFallbackBaseUrl;

    @Value("${abc.ai.free-fallback-model:openai}")
    private String freeFallbackModel;

    private volatile ChatModel chatModel;
    private volatile StreamingChatModel streamingModel;
    private volatile ChatModel freeChatModel;            // 兜底（无 key）
    private volatile StreamingChatModel freeStreamingModel;

    /** 是否有用户/全局 key。 */
    @Override
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** 有无免费兜底。 */
    private boolean hasFreeFallback() {
        return freeFallbackBaseUrl != null && !freeFallbackBaseUrl.isBlank();
    }

    private ChatModel chatModel() {
        if (chatModel == null) {
            synchronized (this) {
                if (chatModel == null) {
                    chatModel = OpenAiChatModel.builder()
                            .baseUrl(baseUrl)
                            .apiKey(apiKey)
                            .modelName(model)
                            .build();
                }
            }
        }
        return chatModel;
    }

    private StreamingChatModel streamingModel() {
        if (streamingModel == null) {
            synchronized (this) {
                if (streamingModel == null) {
                    streamingModel = OpenAiStreamingChatModel.builder()
                            .baseUrl(baseUrl)
                            .apiKey(apiKey)
                            .modelName(model)
                            .build();
                }
            }
        }
        return streamingModel;
    }

    private ChatModel freeChatModel() {
        if (freeChatModel == null) {
            synchronized (this) {
                if (freeChatModel == null) {
                    freeChatModel = OpenAiChatModel.builder()
                            .baseUrl(freeFallbackBaseUrl).apiKey("none").modelName(freeFallbackModel).build();
                }
            }
        }
        return freeChatModel;
    }

    private StreamingChatModel freeStreamingModel() {
        if (freeStreamingModel == null) {
            synchronized (this) {
                if (freeStreamingModel == null) {
                    freeStreamingModel = OpenAiStreamingChatModel.builder()
                            .baseUrl(freeFallbackBaseUrl).apiKey("none").modelName(freeFallbackModel).build();
                }
            }
        }
        return freeStreamingModel;
    }

    /** 用指定 key 创建流式模型（用户自带 key，不入缓存）。 */
    private StreamingChatModel streamingModel(String userApiKey) {
        return OpenAiStreamingChatModel.builder()
                .baseUrl(baseUrl).apiKey(userApiKey).modelName(model).build();
    }

    /** 当前生效的模型名 + 来源，用于前端展示。 */
    public String currentModelInfo(String userApiKey) {
        if (userApiKey != null && !userApiKey.isBlank()) return model + "（你的 Key）";
        if (isConfigured()) return model + "（站点默认）";
        if (hasFreeFallback()) return freeFallbackModel + "（免费兜底）";
        return "未配置";
    }

    @Override
    public void streamChat(List<Map<String, String>> messages, Consumer<String> onToken) {
        streamChat(messages, null, onToken);
    }

    @Override
    public void streamChat(List<Map<String, String>> messages, String userApiKey, Consumer<String> onToken) {
        boolean hasUserKey = userApiKey != null && !userApiKey.isBlank();
        if (!hasUserKey && !isConfigured() && !hasFreeFallback()) {
            String userMsg = lastUser(messages);
            String placeholder = "（未配置大模型 API Key，这是占位回复）你说的是：" + userMsg
                    + "。在个人中心填入你的 API Key 即可接入真实大模型。";
            for (String seg : split(placeholder, 3)) onToken.accept(seg);
            return;
        }
        // 用户 key > 全局 key > 免费兜底
        StreamingChatModel model = hasUserKey ? streamingModel(userApiKey)
                : (isConfigured() ? streamingModel() : freeStreamingModel());
        List<ChatMessage> lc = toMessages(messages);
        CountDownLatch latch = new CountDownLatch(1);
        try {
            model.chat(lc, new StreamingChatResponseHandler() {
                @Override
                public void onPartialResponse(String partial) {
                    if (partial != null && !partial.isEmpty()) onToken.accept(partial);
                }

                @Override
                public void onCompleteResponse(ChatResponse response) {
                    latch.countDown();
                }

                @Override
                public void onError(Throwable error) {
                    log.error("LangChain4j 流式调用失败", error);
                    onToken.accept("\n[AI 服务异常：" + error.getMessage() + "]");
                    latch.countDown();
                }
            });
            latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("LangChain4j 调用异常", e);
            onToken.accept("\n[AI 服务异常：" + e.getMessage() + "]");
        }
    }

    @Override
    public String chat(List<Map<String, String>> messages) {
        if (!isConfigured() && !hasFreeFallback()) return "（未配置大模型 API Key）" + lastUser(messages);
        try {
            ChatModel m = isConfigured() ? chatModel() : freeChatModel();
            ChatResponse resp = m.chat(toMessages(messages));
            return resp.aiMessage().text();
        } catch (Exception e) {
            log.error("LangChain4j 调用失败", e);
            return "[AI 服务异常：" + e.getMessage() + "]";
        }
    }

    // ─── helpers ───────────────────────────────────────────────

    private List<ChatMessage> toMessages(List<Map<String, String>> messages) {
        List<ChatMessage> list = new ArrayList<>();
        for (Map<String, String> m : messages) {
            String role = m.getOrDefault("role", "user");
            String content = m.getOrDefault("content", "");
            switch (role) {
                case "system" -> list.add(SystemMessage.from(content));
                case "assistant" -> list.add(AiMessage.from(content));
                default -> list.add(UserMessage.from(content));
            }
        }
        return list;
    }

    private String lastUser(List<Map<String, String>> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("user".equals(messages.get(i).get("role"))) return messages.get(i).get("content");
        }
        return "";
    }

    private String[] split(String s, int n) {
        int len = s.length();
        int size = Math.max(1, len / n);
        java.util.List<String> parts = new java.util.ArrayList<>();
        for (int i = 0; i < len; i += size) parts.add(s.substring(i, Math.min(len, i + size)));
        return parts.toArray(new String[0]);
    }
}
