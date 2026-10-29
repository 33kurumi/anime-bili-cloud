package com.abc.aiservice.controller;

import cn.hutool.json.JSONUtil;
import com.abc.aiservice.dto.ChatStreamResult;
import com.abc.aiservice.entity.ChatMessage;
import com.abc.aiservice.entity.ChatSession;
import com.abc.aiservice.service.AiChatService;
import com.abc.common.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * AI 桌宠对话接口（SSE 流式）。
 *
 * <p>SSE 事件序列：多个 token → done（sessionId）→ usage（本轮用量估算）。
 * 会话读写都校验归属，避免拿到 sessionId 就能读别人对话。</p>
 */
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiController {

    private final AiChatService aiChatService;
    private final ExecutorService ssePool = Executors.newCachedThreadPool();

    /** 会话列表（只返回当前用户自己的）。 */
    @GetMapping("/sessions")
    public R<List<ChatSession>> sessions(@RequestHeader("X-User-Id") Long userId) {
        return R.ok(aiChatService.listSessions(userId));
    }

    /** 某会话的历史消息（校验归属）。 */
    @GetMapping("/messages")
    public R<List<ChatMessage>> messages(@RequestParam Long sessionId,
                                         @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        return R.ok(aiChatService.getHistoryForUser(sessionId, userId));
    }

    /** 当前生效的大模型（供桌宠/个人中心展示）。 */
    @GetMapping("/model")
    public R<Map<String, Object>> model(@RequestHeader(value = "X-User-Id", required = false) Long userId) {
        return R.ok(Map.of("model", aiChatService.modelInfo(userId)));
    }

    /**
     * 流式对话（SSE）。前端用 EventSource 或 fetch 流读取。
     * GET /api/ai/chat/stream?message=xxx&sessionId=1
     */
    // 显式声明 charset=UTF-8：SSE 默认 StringHttpMessageConverter 可能是 ISO-8859-1，中文会乱码
    @GetMapping(value = "/chat/stream", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter chatStream(@RequestHeader(value = "X-User-Id", required = false) Long userId,
                                 @RequestParam String message,
                                 @RequestParam(required = false) Long sessionId) {
        SseEmitter emitter = new SseEmitter(180_000L);
        ssePool.submit(() -> {
            try {
                ChatStreamResult result = aiChatService.streamChat(sessionId, userId, message, tok -> {
                    try {
                        emitter.send(SseEmitter.event().name("token").data(tok));
                    } catch (Exception e) {
                        // 客户端断开，忽略后续发送失败
                    }
                });
                emitter.send(SseEmitter.event().name("done")
                        .data("{\"sessionId\":" + result.getSessionId() + "}"));

                // 本轮用量：LangChain4j 流式不返回 usage，这里按字符数估算，前端可显示「本轮上下文 ~N tokens」
                Map<String, Object> usage = new LinkedHashMap<>();
                usage.put("sessionId", result.getSessionId());
                usage.put("model", result.getModel());
                usage.put("promptChars", result.getPromptChars());
                usage.put("completionChars", result.getCompletionChars());
                usage.put("estimatedPromptTokens", result.getEstimatedPromptTokens());
                usage.put("estimatedCompletionTokens", result.getEstimatedCompletionTokens());
                emitter.send(SseEmitter.event().name("usage").data(JSONUtil.toJsonStr(usage)));

                emitter.complete();
            } catch (Exception e) {
                try {
                    emitter.send(SseEmitter.event().name("error").data(String.valueOf(e.getMessage())));
                } catch (Exception ignored) {
                }
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }
}
