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

    private volatile ChatModel chatModel;
    private volatile StreamingChatModel streamingModel;

    @Override
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
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

    @Override
    public void streamChat(List<Map<String, String>> messages, Consumer<String> onToken) {
        if (!isConfigured()) {
            String userMsg = lastUser(messages);
            String placeholder = "（未配置大模型 API Key，这是占位回复）你说的是：" + userMsg
                    + "。配置 abc.ai.api-key 后即可接入真实大模型。";
            for (String seg : split(placeholder, 3)) onToken.accept(seg);
            return;
        }
        List<ChatMessage> lc = toMessages(messages);
        CountDownLatch latch = new CountDownLatch(1);
        try {
            streamingModel().chat(lc, new StreamingChatResponseHandler() {
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
        if (!isConfigured()) return "（未配置大模型 API Key）" + lastUser(messages);
        try {
            ChatResponse resp = chatModel().chat(toMessages(messages));
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
