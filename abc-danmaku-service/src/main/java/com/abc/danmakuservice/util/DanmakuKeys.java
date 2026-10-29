package com.abc.danmakuservice.util;

/**
 * 弹幕相关 Redis / 缓存键的集中定义。
 *
 * <p>设计意图：键名是「隐性契约」，散落在各处最容易写错（比如 animeKey 本身含冒号
 * {@code bgm:10380}）。集中到本类后，所有 key 只有一套拼装逻辑，并统一用 {@code :} 分隔，
 * 便于运维用 {@code SCAN danmaku:*} 排查。
 */
public final class DanmakuKeys {

    /** 房间缓冲 List 前缀：danmaku:buffer:{animeKey}:{ep} */
    private static final String BUFFER_PREFIX = "danmaku:buffer:";
    /** 有缓冲待落库的房间注册表（SET），避免使用 KEYS 全库扫描 */
    public static final String BUFFER_ROOMS = "danmaku:buffer:rooms";
    /** 时间窗索引 ZSET 前缀：danmaku:zset:{animeKey}:{ep} */
    private static final String ZSET_PREFIX = "danmaku:zset:";
    /** 频率限制计数器前缀：danmaku:rate:{userId}:{windowIndex} */
    private static final String RATE_PREFIX = "danmaku:rate:";
    /** 5 秒去重窗口计数器前缀：danmaku:dedup:{animeKey}:{ep}:{hash} */
    private static final String DEDUP_PREFIX = "danmaku:dedup:";
    /** 老版本兼容：每集最近 N 条弹幕 List 前缀 */
    private static final String RECENT_PREFIX = "danmaku:episode:";

    private DanmakuKeys() {
    }

    /** 房间键：animeKey + ":" + episodeNum，与 WebSocket 房间、Redis 键保持一致 */
    public static String room(String animeKey, Integer episodeNum) {
        return animeKey + ":" + episodeNum;
    }

    public static String buffer(String animeKey, Integer episodeNum) {
        return BUFFER_PREFIX + room(animeKey, episodeNum);
    }

    /** 由注册表成员（房间键）还原 List 键 */
    public static String bufferByRoom(String roomKey) {
        return BUFFER_PREFIX + roomKey;
    }

    public static String zset(String animeKey, Integer episodeNum) {
        return ZSET_PREFIX + room(animeKey, episodeNum);
    }

    public static String rate(Long userId, long windowIndex) {
        return RATE_PREFIX + userId + ":" + windowIndex;
    }

    public static String dedup(String animeKey, Integer episodeNum, String hash) {
        return DEDUP_PREFIX + animeKey + ":" + episodeNum + ":" + hash;
    }

    public static String recent(String animeKey, Integer episodeNum) {
        return RECENT_PREFIX + room(animeKey, episodeNum);
    }

    /** L1 缓存键：时间窗查询 */
    public static String windowCache(String animeKey, Integer episodeNum, double start, double end, int limit) {
        return "w:" + animeKey + ":" + episodeNum + ":" + start + "-" + end + ":" + limit;
    }

    /** L1 缓存键：热度 */
    public static String heatCache(String animeKey, Integer episodeNum) {
        return "h:" + animeKey + ":" + episodeNum;
    }

    /** L1 失效前缀：某集所有时间窗（新弹幕到来时整体失效，简单可靠） */
    public static String windowCachePrefix(String animeKey, Integer episodeNum) {
        return "w:" + animeKey + ":" + episodeNum + ":";
    }
}
