package com.abc.danmakuservice.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/**
 * Danmaku entity persisted in MongoDB.
 * Each danmaku is a single bullet comment tied to a specific video timestamp.
 *
 * <p>统一标识（P0-B）：{@code animeKey}（远程 "bgm:123" / 本地 "123"）+ {@code episodeNum}（集号）。
 * 不再依赖易混淆的 MySQL 自增 id，播放与弹幕共用同一套键。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "danmaku")
@CompoundIndex(name = "idx_anime_episode_time", def = "{'animeKey': 1, 'episodeNum': 1, 'timestamp': 1}")
public class Danmaku {

    @Id
    private String id;

    /** 统一番剧标识：远程 "bgm:123" / 本地 "123" */
    private String animeKey;

    /** 第几集（稳定） */
    private Integer episodeNum;

    /** Bangumi 原始 id（纯数字，便于 dandanplay 匹配与溯源），可空 */
    private String bangumiId;

    /** 弹幕来源：USER=用户发送 / DANDANPLAY=弹弹play 拉取 */
    private String source;

    /** 用户发送者 ID（拉取的弹幕可为空） */
    private Long userId;

    /** 用户名（拉取的弹幕可为来源站 userId） */
    private String username;

    /** Danmaku text content */
    private String text;

    /** Text color (hex, e.g. "#FFFFFF") */
    private String color;

    /** Display position: 0=scroll, 1=top, 2=bottom */
    private Integer position;

    /** Font size (px) */
    private Integer fontSize;

    /** When this danmaku appears in the video (seconds) */
    private Double timestamp;

    /** When this danmaku was sent / pulled */
    private LocalDateTime sendTime;
}
