package com.abc.danmakuservice.source.dandanplay;

import cn.hutool.json.JSONObject;
import com.abc.danmakuservice.entity.Danmaku;
import com.abc.danmakuservice.repository.DanmakuRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 从弹弹play 拉取弹幕并落库。
 *
 * <p>匹配流程（对齐 Animeko {@code DandanplayDanmakuProvider}）：
 * <ol>
 *   <li>用 Bangumi subject id 调 {@code /api/v2/bangumi/bgmtv/{id}} 拿 dandan 剧集表（最可靠）</li>
 *   <li>在剧集表里按集号匹配 → 失败则按标题归一化精确匹配</li>
 *   <li>调 {@code /api/v2/comment/{episodeId}} 拉弹幕并按字段映射为 {@link Danmaku}</li>
 *   <li>幂等入库（已存在则跳过），避免重复导入</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DanmakuSourceService {

    private final DandanplayClient client;
    private final DanmakuRepository danmakuRepository;

    /** 去掉 "第x话/第x集" 前缀（归一化用） */
    private static final Pattern EP_PREFIX = Pattern.compile("^第\\s*\\d+\\s*[话話集]");

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
        List<DandanplayClient.DandanEpisode> episodes = null;
        if (bgmId != null) {
            episodes = client.getEpisodesByBgmtvSubjectId(Long.parseLong(bgmId));
        }
        if ((episodes == null || episodes.isEmpty()) && title != null && !title.isBlank()) {
            // 降级：按名字搜 dandan 番剧，再取剧集表
            var animes = client.searchAnime(title);
            if (!animes.isEmpty()) {
                Long animeId = (Long) animes.get(0).get("animeId");
                if (animeId != null) episodes = client.getEpisodesByAnimeId(animeId);
            }
        }
        if (episodes == null || episodes.isEmpty()) {
            log.info("dandanplay 未找到剧集: animeKey={} ep={} title={}", animeKey, episodeNum, title);
            return 0;
        }

        DandanplayClient.DandanEpisode matched = matchEpisode(episodes, episodeNum, title);
        if (matched == null) {
            log.info("dandanplay 未匹配到第 {} 集: animeKey={}", episodeNum, animeKey);
            return 0;
        }

        List<JSONObject> raw = client.getDanmakuList(matched.episodeId());
        int inserted = 0;
        List<Danmaku> batch = new ArrayList<>();
        for (JSONObject d : raw) {
            Danmaku dm = mapToEntity(d, animeKey, episodeNum, bgmId);
            if (dm == null) continue;
            // 幂等：同源同文本同时间戳视为重复
            if (danmakuRepository.existsByAnimeKeyAndEpisodeNumAndSourceAndTextAndTimestamp(
                    animeKey, episodeNum, dm.getSource(), dm.getText(), dm.getTimestamp())) {
                continue;
            }
            batch.add(dm);
        }
        if (!batch.isEmpty()) {
            danmakuRepository.saveAll(batch);
            inserted = batch.size();
        }
        log.info("dandanplay 拉取完成: animeKey={} ep={} 匹配集='{}' 入库 {} 条",
                animeKey, episodeNum, matched.episodeTitle(), inserted);
        return inserted;
    }

    // ─── 匹配逻辑 ───────────────────────────────────────────────

    /**
     * 集匹配：优先按 dandan 的 episodeNumber 等于集号；否则按标题归一化精确匹配。
     */
    private DandanplayClient.DandanEpisode matchEpisode(
            List<DandanplayClient.DandanEpisode> episodes, int episodeNum, String title) {
        String epStr = String.valueOf(episodeNum);
        // 1) 按集号（dandan episodeNumber 可能是 "1" / "01" / "1.5" 等）
        for (var e : episodes) {
            String n = e.episodeNumber();
            if (n != null && n.trim().equalsIgnoreCase(epStr)) return e;
        }
        // 2) 按序号兜底：第 N 个 = 第 N 集（当 episodeNumber 缺失时）
        if (episodeNum >= 1 && episodeNum <= episodes.size()) {
            var byIndex = episodes.get(episodeNum - 1);
            if (byIndex.episodeNumber() == null || byIndex.episodeNumber().isBlank()) return byIndex;
        }
        // 3) 标题归一化匹配（用于标题带 "第x话 xxx" 的情况，展示辅助）
        if (title != null && !title.isBlank()) {
            String normTitle = normalizeTitle(title);
            for (var e : episodes) {
                if (normTitle.equals(normalizeTitle(e.episodeTitle()))) return e;
            }
        }
        return null;
    }

    // ─── 字段映射（dandanplay p 串 → Danmaku）─────────────────────

    /**
     * dandanplay 弹幕字段映射。
     * <p>p 格式："出现时间,模式,颜色,用户ID"，逗号分隔。
     * <p>模式：1-普通(滚动) / 4-底部 / 5-顶部 → 本项目 position：0滚动/1顶部/2底部。
     */
    private Danmaku mapToEntity(JSONObject d, String animeKey, int episodeNum, String bgmId) {
        String p = d.getStr("p");
        String m = d.getStr("m");
        if (p == null || m == null) return null;
        String[] parts = p.split(",");
        if (parts.length < 4) return null;
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
            if (position == null) return null; // 不支持的模式（如高级弹幕）丢弃

            String colorHex = String.format("#%06X", colorInt & 0xFFFFFF);
            Long uid = null;
            try { uid = Long.parseLong(userId); } catch (Exception ignored) { }

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
                    .build();
        } catch (Exception e) {
            return null;
        }
    }

    // ─── 工具 ──────────────────────────────────────────────────

    /** 从 animeKey 解析纯数字 Bangumi id；本地数字 id 场景返回传入的 bangumiId。 */
    private String resolveBangumiId(String animeKey, String bangumiId) {
        if (bangumiId != null && bangumiId.matches("\\d+")) return bangumiId;
        if (animeKey != null) {
            String k = animeKey;
            while (k.startsWith("bgm:")) k = k.substring(4);
            if (k.matches("\\d+")) return k;
        }
        return null;
    }

    /** 标题归一化：去 "第x话" 前缀 + 全角转半角 + 去空白 + 转小写。 */
    private String normalizeTitle(String title) {
        if (title == null) return "";
        String stripped = EP_PREFIX.matcher(title.trim()).replaceFirst("");
        StringBuilder sb = new StringBuilder(stripped.length());
        for (char c : stripped.toCharArray()) {
            char half = (c >= '！' && c <= '～') ? (char) (c - 0xFEE0) : c;
            if (Character.isWhitespace(half)) continue;
            sb.append(Character.toLowerCase(half));
        }
        return sb.toString();
    }
}
