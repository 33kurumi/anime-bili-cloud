package com.abc.aiservice.llm;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 大模型客户端抽象（便于以后换 Spring AI / LangChain4j）。
 */
public interface LlmClient {

    /** 是否配置了可用的 API Key。 */
    boolean isConfigured();

    /**
     * 流式对话：每收到一个增量片段调用 onToken。
     * @param messages OpenAI 格式：[{role:system/user/assistant, content:...}]
     */
    void streamChat(List<Map<String, String>> messages, Consumer<String> onToken);

    /** 非流式（一次返回全文）。 */
    String chat(List<Map<String, String>> messages);
}
