package com.abc.danmakuservice.controller;

import com.abc.danmakuservice.dto.HeatBucket;
import com.abc.danmakuservice.entity.Danmaku;
import com.abc.danmakuservice.repository.DanmakuRepository;
import com.abc.danmakuservice.service.DanmakuFlushTask;
import com.abc.danmakuservice.service.DanmakuQueryService;
import com.abc.danmakuservice.source.dandanplay.DanmakuSourceService;
import com.abc.danmakuservice.websocket.DanmakuWebSocketServer;
import com.abc.common.result.R;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 弹幕 REST 接口（统一标识 animeKey + episodeNum）。
 *
 * <p>懒加载：{@code GET /api/danmaku/list} 先查本地 Mongo；若为空则从 dandanplay 拉取并缓存，
 * 因此新番也能立刻拿到弹幕（冷启动）。
 */
@Slf4j
@RestController
@RequestMapping("/api/danmaku")
@RequiredArgsConstructor
public class DanmakuController {

    private final DanmakuRepository danmakuRepository;
    private final DanmakuSourceService danmakuSourceService;
    private final DanmakuQueryService danmakuQueryService;
    private final DanmakuFlushTask danmakuFlushTask;

    /**
     * 获取某集弹幕（用于打开视频时的初始加载）。
     * 本地没有时自动从 dandanplay 拉取（异步失败不影响返回）。
     *
     * @param animeKey   统一番剧标识（"bgm:123" / "123"）
     * @param ep         集号
     * @param title      番剧标题（用于 dandanplay 匹配，可空）
     * @param bangumiId  Bangumi 纯数字 id（可选，优先用于匹配）
     * @param pullRemote 是否允许从上游拉取（默认 true）
     */
    @GetMapping("/list")
    public R<List<Danmaku>> list(@RequestParam String animeKey,
                                 @RequestParam Integer ep,
                                 @RequestParam(required = false) String title,
                                 @RequestParam(required = false) String bangumiId,
                                 @RequestParam(defaultValue = "true") boolean pullRemote) {
        List<Danmaku> danmakuList =
                danmakuRepository.findByAnimeKeyAndEpisodeNumOrderByTimestampAsc(animeKey, ep);

        if (danmakuList.isEmpty() && pullRemote) {
            try {
                danmakuSourceService.fetchAndCache(animeKey, ep, title, bangumiId);
                danmakuList = danmakuRepository.findByAnimeKeyAndEpisodeNumOrderByTimestampAsc(animeKey, ep);
            } catch (Exception e) {
                log.warn("dandanplay 拉取失败，返回已有弹幕: {}", e.getMessage());
            }
        }
        return R.ok(danmakuList);
    }

    /**
     * 时间窗增量拉取（播放器核心接口）：只取 [startTime, endTime] 秒内的弹幕。
     *
     * <p>为什么要有它：整集弹幕可能有几千条，播放器打开时一次性拉全量既慢又浪费带宽；
     * 播放到哪一段就拉哪一段（通常 30~60 秒一个窗），配合前端预取可以做到"弹幕永远不断流"。
     * 服务端读取链路为 L1 本地缓存 → L2 Redis → ZSET 时间索引 → Mongo 回源，见 {@link DanmakuQueryService}。
     *
     * @param animeKey  统一番剧标识
     * @param ep        集号
     * @param startTime 起始秒，缺省 0
     * @param endTime   结束秒，缺省 start + 默认跨度（60s）
     * @param limit     最大条数，缺省且上限由 danmaku.window.max-limit 控制
     */
    @GetMapping("/window")
    public R<List<Danmaku>> window(@RequestParam String animeKey,
                                   @RequestParam Integer ep,
                                   @RequestParam(required = false) Double startTime,
                                   @RequestParam(required = false) Double endTime,
                                   @RequestParam(required = false) Integer limit) {
        return R.ok(danmakuQueryService.window(animeKey, ep, startTime, endTime, limit));
    }

    /**
     * 弹幕热度分桶（进度条上的弹幕密度曲线）。
     *
     * <p>返回只包含"非空桶"，前端按 index * bucketSeconds 定位到进度条位置。
     * 数据来源 ZSET 时间索引（O(M) 扫描），ZSET 为空时回源 Mongo。
     */
    @GetMapping("/heat")
    public R<List<HeatBucket>> heat(@RequestParam String animeKey, @RequestParam Integer ep) {
        return R.ok(danmakuQueryService.heat(animeKey, ep));
    }

    /**
     * 运行状态与缓存命中率（答辩演示用）：flush 统计 + L1 缓存命中率。
     */
    @GetMapping("/stats")
    public R<Map<String, Object>> stats(@RequestParam String animeKey, @RequestParam Integer ep) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("flush", danmakuFlushTask.stats());
        data.put("cache", danmakuQueryService.cacheStats());
        data.put("online", DanmakuWebSocketServer.getOnlineCount(animeKey, ep));
        return R.ok(data);
    }

    /**
     * 兼容旧路径：GET /api/danmaku/{episodeId}
     * 将 episodeId 当作 episodeNum 处理（animeKey 缺省用 "0"）。
     * @deprecated 请改用 {@link #list}
     */
    @Deprecated
    @GetMapping("/{episodeId}")
    public R<List<Danmaku>> legacy(@PathVariable Integer episodeId,
                                   @RequestParam(required = false) String animeKey) {
        String key = (animeKey == null || animeKey.isBlank()) ? "0" : animeKey;
        return R.ok(danmakuRepository.findByAnimeKeyAndEpisodeNumOrderByTimestampAsc(key, episodeId));
    }

    /** 在线人数（按房间键） */
    @GetMapping("/online")
    public R<Integer> onlineCount(@RequestParam String animeKey, @RequestParam Integer ep) {
        return R.ok(DanmakuWebSocketServer.getOnlineCount(animeKey, ep));
    }

    /** 某用户发送的弹幕（个人中心"我的弹幕"）。 */
    @GetMapping("/my")
    public R<List<Danmaku>> myDanmaku(@RequestParam String username) {
        if (username == null || username.isBlank()) return R.ok(List.of());
        return R.ok(danmakuRepository.findByUsernameAndSourceOrderBySendTimeDesc(username, "USER"));
    }
}
