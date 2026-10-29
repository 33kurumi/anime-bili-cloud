package com.abc.aiservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 一轮流式对话的结果统计，用于 SSE 结束后的 usage 事件。
 *
 * <p>LangChain4j 的流式回调不返回 token 用量，这里按「字符数 / 1.5」粗略估算，
 * 前端展示本轮消耗与上下文长度，答辩时也能说明 RAG 真的塞了多少内容。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatStreamResult {

    private Long sessionId;
    private int promptChars;
    private int completionChars;
    private String model;
    private int estimatedPromptTokens;
    private int estimatedCompletionTokens;

    /** 粗略 token 估算：中文约 1 token / 1.5 字符。 */
    public static int estimateTokens(int chars) {
        return (int) Math.ceil(chars / 1.5d);
    }
}
