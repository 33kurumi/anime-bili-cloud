package com.abc.aiservice.controller;

import com.abc.aiservice.entity.ChatMessage;
import com.abc.aiservice.entity.ChatSession;
import com.abc.aiservice.service.AiChatService;
import com.abc.common.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * AI 桌宠对话接口（SSE 流式）。
 */
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiController {

    private final AiChatService aiChatService;
    private final ExecutorService ssePool = Executors.newCachedThreadPool();

    /** 会话列表。 */
    @GetMapping("/sessions")
    public R<List<ChatSession>> sessions(@RequestHeader("X-User-Id") Long userId) {
        return R.ok(aiChatService.listSessions(userId));
    }

    /** 某会话的历史消息。 */
    @GetMapping("/messages")
    public R<List<ChatMessage>> messages(@RequestParam Long sessionId) {
        return R.ok(aiChatService.getHistory(sessionId));
    }

    /**
     * 流式对话（SSE）。前端用 EventSource 或 fetch 流读取。
     * GET /api/ai/chat/stream?message=xxx&sessionId=1
     */
    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestHeader(value = "X-User-Id", required = false) Long userId,
                                 @RequestParam String message,
                                 @RequestParam(required = false) Long sessionId) {
        SseEmitter emitter = new SseEmitter(180_000L);
        ssePool.submit(() -> {
            try {
                Long sid = aiChatService.streamChat(sessionId, userId, message, tok -> {
                    try {
                        emitter.send(SseEmitter.event().name("token").data(tok));
                    } catch (Exception e) {
                        // 客户端断开
                    }
                });
                emitter.send(SseEmitter.event().name("done").data("{\"sessionId\":" + sid + "}"));
                emitter.complete();
            } catch (Exception e) {
                try { emitter.send(SseEmitter.event().name("error").data(String.valueOf(e.getMessage()))); } catch (Exception ignored) { }
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }
}
