package com.abc.danmakuservice.controller;

import com.abc.danmakuservice.entity.Danmaku;
import com.abc.danmakuservice.repository.DanmakuRepository;
import com.abc.danmakuservice.source.dandanplay.DanmakuSourceService;
import com.abc.danmakuservice.websocket.DanmakuWebSocketServer;
import com.abc.common.result.R;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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
