package com.abc.common.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Danmaku message DTO transported across WebSocket and RocketMQ.
 * 采用统一标识（P0-B）：animeKey + episodeNum。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DanmakuMessageDto {
    private String animeKey;       // 远程 "bgm:123" / 本地 "123"
    private Integer episodeNum;     // 第几集
    private Long userId;
    private String username;
    private String text;
    private String color;          // hex e.g. "#FFFFFF"
    private Integer position;      // 0=scroll, 1=top, 2=bottom
    private Integer fontSize;      // default 18
    private Double timestamp;      // seconds in video
}
