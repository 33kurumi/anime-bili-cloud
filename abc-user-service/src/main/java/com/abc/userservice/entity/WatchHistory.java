package com.abc.userservice.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("watch_history")
public class WatchHistory {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long episodeId;

    /** 统一番剧标识：远程 "bgm:123" / 本地 "123" */
    private String animeKey;

    /** 集号 */
    private Integer episodeNum;

    /** 番剧标题（冗余，便于展示） */
    private String title;

    /** 封面 */
    private String coverUrl;

    private Long animeId;

    private Long progress;           // 观看进度(秒)

    // 无自动填充器：为 null 时 MP 排除该列，由 DB 默认 CURRENT_TIMESTAMP 填充
    private LocalDateTime watchedAt;
}
