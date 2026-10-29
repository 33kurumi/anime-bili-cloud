package com.abc.danmakuservice.source.dandanplay;

import cn.hutool.json.JSONObject;
import com.abc.danmakuservice.entity.Danmaku;
import com.abc.danmakuservice.repository.DanmakuRepository;
import com.abc.danmakuservice.source.dandanplay.DandanplayClient.DandanEpisode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 从弹弹play 拉取弹幕并落库。
 *
 * <p>匹配流程（对齐 Animeko {@code DandanplayDanmakuProvider}，task-6 升级为三级匹配）：
 * <ol>
 *   <li>用 Bangumi subject id 调 {@code /api/v2/bangumi/bgmtv/{id}} 拿 dandan 剧集表（最可靠）</li>
 *   <li>降级：按标题搜 dandan 番剧，再取剧集表</li>
 *   <li>三级匹配（{@link DanmakuEpisodeMatcher}）：BANGUMI_ID → TITLE_EXACT(±1集) → TITLE_FUZZY(≥0.6)</li>
 *   <li>调 {@code /api/v2/comment/{episodeId}} 拉弹幕并按字段映射为 {@link Danmaku}，写入 matchMethod</li>
 *   <li>幂等入库：<b>一次</b>查出该集已有弹幕构建指纹集合，而不是逐条 exists 查询（修 N+1）</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DanmakuSourceService {

    private final DandanplayClient client;
    private final DanmakuRepository danmakuRepository;

    /**
     * 拉取某集弹幕并落库（幂等）。返回本次新入库的条数。
     *
     * @param animeKey  统一番剧标识（远程 "bgm:123" / 本地 "123"）
     * @param episodeNum 集号
     * @param title      番剧标题（用于匹配，可空）
     * @param bangumiId  Bangumi 纯数字 id（优先用；可从 animeKey 解析）
     */
    public int fetchAndCache(String animeKey, int episodeNum, String title, String bangumiId) {
        if (!client.isConfigured()) {
            log.warn("dandanplay 未配置，跳过拉取: {}/{}", animeKey, episodeNum);
            return 0;
        }
        // 若该集已有拉取过的弹幕，直接跳过（避免重复请求上游）
        if (danmakuRepository.existsByAnimeKeyAndEpisodeNum(animeKey, episodeNum)) {
            return 0;
        }

        String bgmId = resolveBangumiId(animeKey, bangumiId);
        List<DandanEpisode> episodes = null;
        boolean viaBangumi = false;
        if (bgmId != null) {
            episodes = client.getEpisodesByBgmtvSubjectId(Long.parseLong(bgmId));
            viaBangumi = episodes != null && !episodes.isEmpty();
        }
        if ((episodes == null || episodes.isEmpty()) && title != null && !title.isBlank()) {
            // 降级：按名字搜 dandan 番剧，再取剧集表
            List<java.util.Map<String, Object>> animes = client.searchAnime(title);
            if (!animes.isEmpty()) {
                Object animeId = animes.get(0).get("animeId");
                if (animeId instanceof Long id) {
                    episodes = client.getEpisodesByAnimeId(id);
                }
            }
        }
        if (episodes == null || episodes.isEmpty()) {
            log.info("dandanplay 未找到剧集: animeKey={} ep={} title={}", animeKey, episodeNum, title);
            return 0;
        }

        DanmakuMatchResult matched = DanmakuEpisodeMatcher.match(episodes, viaBangumi, episodeNum, title);
        if (!matched.matched()) {
            log.info("dandanplay 三级匹配失败: animeKey={} ep={} title={}", animeKey, episodeNum, title);
            return 0;
        }
        log.info("dandanplay 匹配成功: animeKey={} ep={} 层级={} 分值={} 集='{}'",
                animeKey, episodeNum, matched.method(), matched.score(),
                matched.episode().episodeTitle());

        List<JSONObject> raw = client.getDanmakuList(matched.episode().episodeId());
        if (raw.isEmpty()) {
            return 0;
        }

        // 修 N+1：一次性查出该集已有的 (source|text|timestamp) 指纹，而不是逐条 exists 查库
        Set<String> existing = loadExistingFingerprints(animeKey, episodeNum);
        Set<String> batchFingerprints = new HashSet<>();
        List<Danmaku> batch = new ArrayList<>(raw.size());
        int duplicated = 0;
        for (JSONObject d : raw) {
            Danmaku dm = mapToEntity(d, animeKey, episodeNum, bgmId, matched.method());
            if (dm == null) {
                continue;
            }
            String fingerprint = fingerprintOf(dm);
            if (existing.contains(fingerprint) || !batchFingerprints.add(fingerprint)) {
                duplicated++;
                continue;
            }
            batch.add(dm);
        }
        if (!batch.isEmpty()) {
            danmakuRepository.saveAll(batch);
        }
        log.info("dandanplay 拉取完成: animeKey={} ep={} 上游 {} 条, 入库 {} 条, 去重 {} 条",
                animeKey, episodeNum, raw.size(), batch.size(), duplicated);
        return batch.size();
    }

    // ─── 幂等辅助 ───────────────────────────────────────────────

    /** 该集已有弹幕的去重指纹集合（一次查询，O(1) 判重） */
    private Set<String> loadExistingFingerprints(String animeKey, int episodeNum) {
        List<Danmaku> existing =
                danmakuRepository.findByAnimeKeyAndEpisodeNumOrderByTimestampAsc(animeKey, episodeNum);
        Set<String> set = new HashSet<>(Math.max(16, existing.size() * 2));
        for (Danmaku d : existing) {
            set.add(fingerprintOf(d));
        }
        return set;
    }

    /** 幂等指纹：来源 + 文本 + 时间戳（时间戳保留 1 位小数，容忍上游浮点抖动） */
    private String fingerprintOf(Danmaku d) {
        double ts = d.getTimestamp() == null ? 0d : Math.round(d.getTimestamp() * 10d) / 10d;
        return d.getSource() + "|" + d.getText() + "|" + ts;
    }

    // ─── 字段映射（dandanplay p 串 → Danmaku）─────────────────────

    /**
     * dandanplay 弹幕字段映射。
     * <p>p 格式："出现时间,模式,颜色,用户ID"，逗号分隔。
     * <p>模式：1-普通(滚动) / 4-底部 / 5-顶部 → 本项目 position：0滚动/1顶部/2底部。
     */
    private Danmaku mapToEntity(JSONObject d, String animeKey, int episodeNum, String bgmId,
                                DanmakuMatchMethod method) {
        String p = d.getStr("p");
        String m = d.getStr("m");
        if (p == null || m == null) {
            return null;
        }
        String[] parts = p.split(",");
        if (parts.length < 4) {
            return null;
        }
        try {
            double timeSec = Double.parseDouble(parts[0].trim());
            int mode = Integer.parseInt(parts[1].trim());
            int colorInt = Integer.parseInt(parts[2].trim());
            String userId = parts[3].trim();

            Integer position = switch (mode) {
                case 1 -> 0;  // 普通 → 滚动
                case 5 -> 1;  // 顶部
                case 4 -> 2;  // 底部
                default -> null;
            };
            if (position == null) {
                return null; // 不支持的模式（如高级弹幕）丢弃
            }

            String colorHex = String.format("#%06X", colorInt & 0xFFFFFF);
            Long uid = null;
            try {
                uid = Long.parseLong(userId);
            } catch (Exception ignored) {
                // 上游 userId 不一定是数字，忽略即可
            }

            return Danmaku.builder()
                    .animeKey(animeKey)
                    .episodeNum(episodeNum)
                    .bangumiId(bgmId)
                    .source("DANDANPLAY")
                    .userId(uid)
                    .username(userId)
                    .text(m)
                    .color(colorHex)
                    .position(position)
                    .fontSize(18)
                    .timestamp(timeSec)
                    .sendTime(LocalDateTime.now())
                    .count(1)
                    .blocked(false)
                    .matchMethod(method == null ? null : method.name())
                    .build();
        } catch (Exception e) {
            return null;
        }
    }

    // ─── 工具 ──────────────────────────────────────────────────

    /** 从 animeKey 解析纯数字 Bangumi id；本地数字 id 场景返回传入的 bangumiId。 */
    private String resolveBangumiId(String animeKey, String bangumiId) {
        if (bangumiId != null && bangumiId.matches("[0-9]+")) {
            return bangumiId;
        }
        if (animeKey != null) {
            String k = animeKey;
            while (k.startsWith("bgm:")) {
                k = k.substring(4);
            }
            if (k.matches("[0-9]+")) {
                return k;
            }
        }
        return null;
    }
}
