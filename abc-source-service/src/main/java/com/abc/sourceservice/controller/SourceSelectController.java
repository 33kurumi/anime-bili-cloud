package com.abc.sourceservice.controller;

import com.abc.common.result.R;
import com.abc.sourceservice.select.SelectEvent;
import com.abc.sourceservice.select.SelectPipeline;
import com.abc.sourceservice.service.SourceSelectService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 选源对外接口：SSE 分阶段选源 + 同步选源。
 *
 * <p>两个接口走的是同一套状态机（{@link SourceSelectService#select}），
 * 差别只在「事件往哪写」：
 * <ul>
 *   <li>{@code GET /api/source/select/stream}：每阶段开始/结束、每个候选、终态都推给前端，
 *       前端可以画「正在尝试 的源 3/8」这种进度；</li>
 *   <li>{@code GET /api/source/play/{animeKey}/{ep}}：等全部跑完一次性返回，
 *       给不支持 SSE 的播放器/第三方调用方。</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/source")
@RequiredArgsConstructor
public class SourceSelectController {

    private static final long SSE_TIMEOUT_MS = 60_000L;

    /** SSE 推流线程池：SSE 是长连接，不能占 Tomcat 的请求线程。 */
    private static final ExecutorService SSE_POOL = Executors.newFixedThreadPool(8, new java.util.concurrent.ThreadFactory() {
        private final AtomicInteger seq = new AtomicInteger();

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "source-select-sse-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    });

    private final SourceSelectService selectService;

    /**
     * SSE 分阶段选源。
     * 事件类型与 data 字段见 {@link SelectEvent}：
     * {@code stage} / {@code candidate} / {@code final} / {@code error}。
     */
    @GetMapping(value = "/select/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter selectStream(@RequestParam String animeKey,
                                   @RequestParam(defaultValue = "1") int ep,
                                   @RequestParam(required = false) String title,
                                   @RequestParam(required = false) String keyword,
                                   @RequestParam(required = false) Long ruleId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        emitter.onTimeout(() -> log.info("SSE 选源超时: {} 第{}集", animeKey, ep));
        emitter.onError(e -> log.info("SSE 选源断开: {} 第{}集, {}", animeKey, ep, e.getMessage()));

        CompletableFuture.runAsync(() -> {
            SelectPipeline.Listener listener = event -> {
                try {
                    emitter.send(SseEmitter.event().name(event.type())
                            .data(event.data(), MediaType.APPLICATION_JSON));
                } catch (Exception e) {
                    throw new IllegalStateException("SSE 发送失败: " + e.getMessage(), e);
                }
            };
            try {
                SelectPipeline.Result result = selectService.select(animeKey, ep, title, keyword, ruleId, listener);
                emitter.send(SseEmitter.event().name("complete")
                        .data(result.payload(), MediaType.APPLICATION_JSON));
                emitter.complete();
            } catch (Exception e) {
                log.warn("SSE 选源异常: {} 第{}集, {}", animeKey, ep, e.getMessage());
                try {
                    Map<String, Object> err = new LinkedHashMap<>();
                    err.put("message", String.valueOf(e.getMessage()));
                    emitter.send(SseEmitter.event().name("error")
                            .data(err, MediaType.APPLICATION_JSON));
                } catch (Exception ignored) {
                    // 客户端已断开，无需再报
                }
                emitter.completeWithError(e);
            }
        }, SSE_POOL);
        return emitter;
    }

    /**
     * 同步选源（路径版）：{@code GET /api/source/play/{animeKey}/{ep}}。
     *
     * <p>返回 data 字段：{@code url / proxyUrl / headers / format / sourceName / ruleId /
     * matchedTitle / matchScore / resolvedBy / candidates[] / stageUsed / stageUsedLabel / hit / elapsedMs}。
     * 失败时 {@code hit=false}、{@code url=null}、{@code message} 给原因，HTTP 仍是 200（前端好处理）。
     */
    @GetMapping("/play/{animeKey}/{ep}")
    public R<Map<String, Object>> play(@PathVariable String animeKey,
                                       @PathVariable("ep") int ep,
                                       @RequestParam(required = false) String title,
                                       @RequestParam(required = false) String keyword,
                                       @RequestParam(required = false) Long ruleId) {
        return R.ok(selectService.playSync(animeKey, ep, title, keyword, ruleId));
    }
}
